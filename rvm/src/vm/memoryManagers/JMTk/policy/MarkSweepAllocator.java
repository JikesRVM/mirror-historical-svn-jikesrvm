/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 */
package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;
import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Offset;
import com.ibm.JikesRVM.VM_Word;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_PragmaInline;
import com.ibm.JikesRVM.VM_PragmaNoInline;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;
import com.ibm.JikesRVM.VM_Uninterruptible;

final class MarkSweepAllocator extends BaseFreeList implements Constants, VM_Uninterruptible {
  public final static String Id = "$Id$"; 

  /**
   * Constructor
   *
   * @param collector_ The mark-sweep collector to which this
   * allocator instances is bound.
   */
  MarkSweepAllocator(MarkSweepCollector collector_) {
    super(collector_.getVMResource(), collector_.getMemoryResource());
    collector = collector_;
    treadmillLock = new Lock("MarkSweepAllocator.treadmillLock");
  }

  public final void prepare(VMResource vm, NewMemoryResource mr) { 
    sweepSanity();
    treadmillToHead = VM_Address.zero();
    collector.prepare(vm, mr);
  }

  public final void sweepSuperPages() {
    for (int sizeClass = 1; sizeClass < SIZE_CLASSES; sizeClass++) {
//       VM.sysWrite("\n\nSweep free\n");
      sweepSuperPages(superPageFreeList[sizeClass], sizeClass, true);
      if (useSuperPageFreeLists()) {
// 	VM.sysWrite("\n\nSweep used\n");
	sweepSuperPages(superPageUsedList[sizeClass], sizeClass, false);
      }
    }
    sweepSanity();
  }

  private final void sweepSuperPages(VM_Address sp, int sizeClass, boolean free)
    throws VM_PragmaInline {
    if (!sp.EQ(VM_Address.zero())) {
      int cellSize = cellSize(sizeClass);
      while (!sp.EQ(VM_Address.zero())) {
	VM_Address next = getNextSuperPage(sp);
	collector.sweepSuperPage(this, sp, sizeClass, cellSize, free);
	//	superPageSanity(sp, sizeClass);
	sp = next;
      }
    }
  }
  
  private final void sweepSanity() {
    for (int sizeClass = 1; sizeClass < SIZE_CLASSES; sizeClass++) {
      sweepSanity(superPageFreeList[sizeClass], sizeClass);
      if (useSuperPageFreeLists())
	sweepSanity(superPageUsedList[sizeClass], sizeClass);
    }
  }
  private final void sweepSanity(VM_Address sp, int sizeClass) {
    if (!sp.EQ(VM_Address.zero())) {
      int cellSize = cellSize(sizeClass);
      while (!sp.EQ(VM_Address.zero())) {
// 	VM.sysWrite(sizeClass); VM.sysWrite(" "); VM.sysWrite(sp);
	superPageSanity(sp, sizeClass);
// 	VM.sysWrite(" sane\n");
	sp = getNextSuperPage(sp);
      }
    }
  }

  public final void sweepLargePages() {
    VM_Address cell = treadmillFromHead;
    while (!cell.isZero()) {
      VM_Address next = MarkSweepCollector.getNextTreadmill(cell);
      freeSuperPage(getSuperPage(cell, false), LARGE_SIZE_CLASS, true);
      cell = next;
    }
    treadmillFromHead = treadmillToHead;
    treadmillToHead = VM_Address.zero();
  }

  public final void setTreadmillFromHead(VM_Address cell)
    throws VM_PragmaInline {
    treadmillFromHead = cell;
  }
  public final void setTreadmillToHead(VM_Address cell)
    throws VM_PragmaInline {
    treadmillToHead = cell;
  }
  public final VM_Address getTreadmillFromHead()
    throws VM_PragmaInline {
    return treadmillFromHead;
  }
  public final VM_Address getTreadmillToHead()
    throws VM_PragmaInline {
    return treadmillToHead;
  }
  public final void lockTreadmill()
    throws VM_PragmaInline {
    treadmillLock.acquire();
  }
  public final void unlockTreadmill()
    throws VM_PragmaInline {
    treadmillLock.release();
  }
  /**
   * Return the number of pages used by a superpage of a given size
   * class.
   *
   * @param sizeClass The size class of the superpage
   * @return The number of pages used by a superpage of this sizeclass
   */
  protected final int pagesForClassSize(int sizeClass) 
    throws VM_PragmaInline {
    if (VM.VerifyAssertions) 
      VM._assert(!isLarge(sizeClass));

    return sizeClassPages[sizeClass];
  }

  /**
   * Return the size of the per-superpage header required by this
   * system.  In this case it is just the underlying superpage header
   * size.
   *
   * @param sizeClass The size class of the cells contained by this
   * superpage.
   * @return The size of the per-superpage header required by this
   * system.
   */
  protected final int superPageHeaderSize(int sizeClass)
    throws VM_PragmaInline {
    if (isLarge(sizeClass))
      return BASE_SP_HEADER_SIZE + TREADMILL_HEADER_SIZE;
    else if (isSmall(sizeClass))
      return BASE_SP_HEADER_SIZE + SMALL_BITMAP_SIZE;
    else 
      return BASE_SP_HEADER_SIZE + MID_BITMAP_SIZE;
  }

  public static int getCellSize(VM_Address sp) {
    return getCellSize(getSizeClass(sp));
  }
 
  private static int getCellSize(int sizeClass) {
    if (VM.VerifyAssertions) 
      VM._assert(!isLarge(sizeClass));

    return cellSize[sizeClass];
  }
  /**
   * Return the size of a cell for a given class size, *including* any
   * per-cell header space.
   *
   * @param sizeClass The size class in question
   * @return The size of a cell for a given class size, *including*
   * any per-cell header space
   */
  protected final int cellSize(int sizeClass) 
    throws VM_PragmaInline {
    return getCellSize(sizeClass);
  }

  /**
   * Return the size of the per-cell header for cells of a given class
   * size.
   *
   * @param sizeClass The size class in question.
   * @return The size of the per-cell header for cells of a given class
   * size.
   */
  protected final int cellHeaderSize(int sizeClass)
    throws VM_PragmaInline {
    return cellHeaderSize(isSmall(sizeClass));
  }

  /**
   * Return the size of the per-cell header for cells of a given class
   * size.
   *
   * @param isSmall True if the cell is a small cell
   * @return The size of the per-cell header for cells of a given class
   * size.
   */
  protected final int cellHeaderSize(boolean small)
    throws VM_PragmaInline {
    return small ? 0 : NON_SMALL_OBJ_HEADER_SIZE;
  }

  /**
   * Initialize a new cell and return the address of the first useable
   * word.  This is called only when the cell is first created, not
   * each time it is reused via a call to alloc.<p>
   *
   * In this system, small cells require no header, but all other
   * cells require a single word that points to the first word of the
   * superpage.
   *
   * @param cell The address of the first word of the allocated cell.
   * @param sp The address of the first word of the superpage
   * containing the cell.
   * @param small True if the cell is a small cell (single page
   * superpage).
   * @return The address of the first useable word.
   */
  protected final VM_Address initializeCell(VM_Address cell, VM_Address sp,
					    boolean small, boolean large)
    throws VM_PragmaInline {
    if (!small) {
      //      VM.sysWrite("i: "); VM.sysWrite(cell); VM.sysWrite("->"); VM.sysWrite(sp); VM.sysWrite("\n");
      
//       if (large)
// 	cell = cell.add(TREADMILL_HEADER_SIZE);
      VM_Magic.setMemoryAddress(cell, sp);
      return cell.add(NON_SMALL_OBJ_HEADER_SIZE);
    } else 
      return cell;
  }

  /**
   *  This is called each time a cell is alloced (i.e. if a cell is
   *  reused, this will be called each time it is reused in the
   *  lifetime of the cell, by contrast to initializeCell, which is
   *  called exactly once.).
   */
  protected final void postAlloc(VM_Address cell, boolean isScalar,
				 EXTENT bytes, boolean small, boolean large) {
    //    sanity();
    if (!large) {
//       if (!small) {
// 	VM.sysWrite("pa: "); VM.sysWrite(cell); VM.sysWrite((small ? " small\n" : " non-small\n"));
//       }
      collector.setInUseBit(cell, getSuperPage(cell, small), small);
    } else {
      //      VM.sysWrite("pa: "); VM.sysWrite(cell); VM.sysWrite((small ? " small\n" : " non-small\n"));
      collector.addToTreadmill(cell, this);
    }
//     if (!small && !large) {
//         VM.sysWrite(cell); VM.sysWrite(" a "); VM.sysWrite(getSuperPage(cell, small)); VM.sysWrite("\n");
//     }
    //    sanity();
  };

  protected final void superPageSanity(VM_Address sp, int sizeClass) {
    if (isLarge(sizeClass)) {
    } else {
      boolean small = isSmall(sizeClass);
      VM_Address sentinal;
      sentinal = sp.add(pagesForClassSize(sizeClass)<<LOG_PAGE_SIZE);
      int cellSize = cellSize(sizeClass);
      VM_Address cursor = sp.add(superPageHeaderSize(sizeClass));
      int inUse = 0;
 //       VM.sysWrite(sp); VM.sysWrite(" "); VM.sysWrite(sizeClass); VM.sysWrite("\n--------------\n");
      while (cursor.add(cellSize).LE(sentinal)) {
	VM_Address cell = cursor;
//  	VM.sysWrite(cell); VM.sysWrite(" ");
//  	VM.sysWrite(cell.add(cellHeaderSize(small)));
	boolean free = isFree(cell.add(cellHeaderSize(small)), sp, sizeClass);
	if (MarkSweepCollector.getInUseBit(cell, sp, small)) {
//  	  VM.sysWrite(" u\n");
	  if (free) {
	    VM.sysWrite("--->"); VM.sysWrite(cell); VM.sysWrite(" "); VM.sysWrite(sp); VM.sysWrite("\n");
	    VM.sysWrite("    "); VM.sysWrite(freeList[sizeClass]); VM.sysWrite(" "); VM.sysWrite(getSuperPageFreeList(sp));  VM.sysWrite("\n");
	  }
	  VM._assert(!free);
	  inUse++;
	} else {
//  	  VM.sysWrite(" f\n");
// 	  if (!free) {
// 	    VM.sysWrite(sp);
// 	    VM.sysWrite(" ");
// 	    VM_Address tmp = superPageFreeList[sizeClass];
// 	    while (!tmp.isZero()) {
// 	      VM.sysWrite(tmp); VM.sysWrite(" ");
// 	      tmp = getNextSuperPage(tmp);
// 	    }
// 	    VM.sysWrite("\n");
// 	    tmp = getSuperPageFreeList(sp);
// 	    while (!tmp.isZero()) {
// 	      VM.sysWrite(tmp); VM.sysWrite(" ");
// 	      tmp = getNextCell(tmp);
// 	    }
// 	    VM.sysWrite("\n");
// 	  }
	  VM._assert(free);
	}
	cursor = cursor.add(cellSize);
      }
//       VM.sysWrite(sp); VM.sysWrite(" "); VM.sysWrite(sizeClass); VM.sysWrite("\n--------------\n\n");
      if (inUse != getInUse(sp)) {
	VM.sysWrite("****** ");
	VM.sysWrite(inUse); VM.sysWrite(" != "); VM.sysWrite(getInUse(sp));
	VM.sysWrite(" ******\n");
      }
      VM._assert(inUse == getInUse(sp));
    }
  }
    

//   protected final void superPageSanity(VM_Address sp) {
//     int sizeClass = getSizeClass(sp);
//     if (isLarge(sizeClass)) {
//       VM.sysWrite("    sp: "); VM.sysWrite(sp); VM.sysWrite(" cell: ");
//       VM_Address cell = sp.add(superPageHeaderSize(LARGE_SIZE_CLASS)+TREADMILL_HEADER_SIZE+NON_SMALL_OBJ_HEADER_SIZE);
//       VM.sysWrite(cell);
//       VM.sysWrite("\n");
//       VM._assert(collector.isOnTreadmill(cell, getTreadmillFromHead()));
//     } else if (!isSmall(sizeClass)) {
//       VM_Address sentinal = sp.add(pagesForClassSize(sizeClass)<<LOG_PAGE_SIZE);
//       int cellSize = cellSize(sizeClass);
//       VM_Address cursor = sp.add(superPageHeaderSize(sizeClass));
//       int inUse = 0;
//       while (cursor.add(cellSize).LE(sentinal)) {
// 	VM_Address cell = cursor.add(TREADMILL_HEADER_SIZE
// 				     + NON_SMALL_OBJ_HEADER_SIZE);
// 	if(collector.isOnTreadmill(cell, getTreadmillFromHead()))
// 	  inUse++;
// 	else
// 	  VM._assert(isFree(cell, sizeClass));
// 	cursor = cursor.add(cellSize);
//       }
//       VM._assert(inUse == getInUse(sp));
//     } else {
//       VM_Address sentinal = sp.add(PAGE_SIZE);
//       int cellSize = cellSize(sizeClass);
//       VM_Address cursor = sp.add(superPageHeaderSize(sizeClass));
//       int inUse = 0;
//       while (cursor.add(cellSize).LE(sentinal)) {
// 	VM_Address cell = cursor;
// 	if(MarkSweepCollector.getInUseBit(cell, sp, true))
// 	  inUse++;
// 	else
// 	  VM._assert(isFree(cell, sizeClass));
// 	cursor = cursor.add(cellSize);
//       }
//       if (inUse != getInUse(sp)) {
// 	VM.sysWrite("****** ");
// 	VM.sysWrite(inUse); VM.sysWrite(" != "); VM.sysWrite(getInUse(sp));
// 	VM.sysWrite(" ******\n");
//       }
//       VM._assert(inUse == getInUse(sp));
//     }
    
//   }
  

  ////////////////////////////////////////////////////////////////////////////
  //
  // The following methods, declared as abstract in the superclass, do
  // nothing in this implementation, so they have empty bodies.
  //
  protected final void postFreeCell(VM_Address cell, VM_Address sp, 
				    int szClass) {};
  protected final void postExpandSizeClass(VM_Address sp, int sizeClass) {};
  
  private MarkSweepCollector collector;
  private Lock treadmillLock;
  private VM_Address treadmillFromHead;
  private VM_Address treadmillToHead;
  private static int cellSize[];
  private static int sizeClassPages[];

  private static final int SMALL_BITMAP_SIZE = MarkSweepCollector.SMALL_BITMAP_SIZE;
  private static final int MID_BITMAP_SIZE = MarkSweepCollector.MID_BITMAP_SIZE;
  private static final int MAX_MID_OBJECTS = MarkSweepCollector.MAX_MID_OBJECTS;
  private static final int TREADMILL_HEADER_SIZE = MarkSweepCollector.TREADMILL_HEADER_SIZE;

  private static final boolean SUPER_PAGE_FREE_LISTS = false;
  protected final boolean useSuperPageFreeLists() 
    throws VM_PragmaInline {
    return SUPER_PAGE_FREE_LISTS;
  }

  public static final int MAX_SMALL_SIZE = 512;  // statically verified below..
  static {
    cellSize = new int[SIZE_CLASSES];
    sizeClassPages = new int[SIZE_CLASSES];
    for(int sc = 1; sc < SIZE_CLASSES; sc++) {
      int size = getBaseCellSize(sc);
      if (isSmall(sc)) {
	cellSize[sc] = size;
	sizeClassPages[sc] = 1;
      } else {
	cellSize[sc] = size + NON_SMALL_OBJ_HEADER_SIZE;
	sizeClassPages[sc] = optimalPagesForSuperPage(sc, cellSize[sc],
						      BASE_SP_HEADER_SIZE);
	int cells = (sizeClassPages[sc]/cellSize[sc]);
	VM._assert(cells <= MAX_MID_OBJECTS);
      }
      VM.sysWrite("sc: "+sc+" bcs: "+size+" cs: "+cellSize[sc]+" pages: "+sizeClassPages[sc]+"\n");
      if (sc == MAX_SMALL_SIZE_CLASS)
	VM._assert(size == MAX_SMALL_SIZE);
    }
  }


}
