/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;
import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Memory;
import com.ibm.JikesRVM.VM_Offset;
import com.ibm.JikesRVM.VM_Word;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_PragmaInline;
import com.ibm.JikesRVM.VM_PragmaNoInline;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;
import com.ibm.JikesRVM.VM_Uninterruptible;

/**
 * This abstract class implements core functionality for a generic
 * free list allocator.  Each instance of this class is intended to
 * provide fast, unsynchronized access to a free list.  Therefore
 * instances must not be shared across truely concurrent threads
 * (CPUs).  Rather, one or more instances of this class should be
 * bound to each CPU.  The shared VMResource used by each instance is
 * the point of global synchronization, and synchronization only
 * occurs at the granularity of aquiring (and releasing) chunks of
 * memory from the VMResource.  Subclasses may require finer grained
 * synchronization during a marking phase, for example.
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */

abstract class BaseFreeList implements Constants, VM_Uninterruptible {
  public final static String Id = "$Id$"; 

  
  ////////////////////////////////////////////////////////////////////////////
  //
  // Public methods
  //

 /**
   * Constructor
   *
   * @param vmr The virtual memory resource from which this bump
   * pointer will acquire virtual memory.
   */
  BaseFreeList(NewFreeListVMResource vmr, MemoryResource mr) {
    vmResource = vmr;
    memoryResource = mr;
    freeList = new VM_Address[SIZE_CLASSES];
  }

  /**
   * Allocate space for a new object
   *
   * @param isScalar Is the object to be allocated a scalar (or array)?
   * @param bytes The number of bytes allocated
   * @return The address of the first byte of the allocated cell
   */
  public final VM_Address alloc(boolean isScalar, EXTENT bytes) 
    throws VM_PragmaInline {
    
    int sizeClass = getSizeClass(isScalar, bytes);
    boolean large = (sizeClass == LARGE_SIZE_CLASS);
    boolean small = !large && (sizeClass <= MAX_SMALL_SIZE_CLASS);
    VM_Address cell;
    if (large)
      cell = allocLarge(isScalar, bytes);
    else
      cell = allocCell(isScalar, sizeClass);
    postAlloc(cell, isScalar, bytes, small);
    VM_Memory.zero(cell, bytes);
//     VM.sysWrite("alloc: "); VM.sysWrite(bytes); VM.sysWrite("->"); VM.sysWrite(cell); VM.sysWrite("\n");
    return cell;
  }

  public void show() {
  }


  ////////////////////////////////////////////////////////////////////////////
  //
  // Abstract methods
  //
  abstract protected void postAlloc(VM_Address cell, boolean isScalar,
				    EXTENT bytes, boolean isLarge);
  abstract protected void postFreeCell(VM_Address cell, VM_Address sp,
				       int szClass);
  abstract protected int pagesForClassSize(int sizeClass);
  abstract protected int superPageHeaderSize(int sizeClass);
  abstract protected int cellSize(int sizeClass);
  abstract protected int cellHeaderSize(int sizeClass);
  abstract protected void postExpandSizeClass(VM_Address sp, int sizeClass);
  abstract protected VM_Address initializeCell(VM_Address cell, VM_Address sp,
					       boolean small);
  abstract protected void superPageSanity(VM_Address sp);

  ////////////////////////////////////////////////////////////////////////////
  //
  // Protected and private methods
  //
  /**
   * Free a cell.  If the cell is large (own superpage) then release
   * the superpage, if not, and all cells on the superpage are free,
   * then release the superpage. Otherwise add to free list.
   *
   * @param cell The address of the first byte of the cell to be freed
   * @param sp The superpage containing the cell
   * @param szClass The sizeclass of the cell.
   */
  protected final void free(VM_Address cell, VM_Address sp, int szClass)
    throws VM_PragmaInline {
    if (szClass == LARGE_SIZE_CLASS)
      freeSuperPage(sp);
    else if (decInUse(sp) > 0) {
      setNextCell(cell, freeList[szClass]);
      freeList[szClass] = cell;
      postFreeCell(cell, sp, szClass);
    } else {
      releaseSuperPageCells(sp, szClass);
      freeSuperPage(sp);
    }
  }

  /**
   * Allocate a cell. Cells are maintained on free lists (as opposed
   * to large objects, which are directly allocated and freed in
   * page-grain units via the vm resource).  This routine does not
   * guarantee that the cell will be zeroed.  The caller must
   * explicitly zero the cell.
   *
   * @param isScalar True if the object to occupy this cell will be a scalar
   * @param sizeClass The size class of the cell.
   * @return The address of the start of a newly allocted cell of
   * size at least sufficient to accommodate <code>sizeClass</code>.
   */
  private final VM_Address allocCell(boolean isScalar, int sizeClass) 
    throws VM_PragmaInline {
    if (VM.VerifyAssertions) 
      VM._assert(sizeClass != LARGE_SIZE_CLASS);
    
    // grab a freelist entry, expanding if necessary
    if (freeList[sizeClass].isZero()) {
      expandSizeClass(sizeClass);
      if (VM.VerifyAssertions)
	VM._assert(!(freeList[sizeClass].isZero()));
    }
    
    // take off free list
    VM_Address cell = freeList[sizeClass];
    freeList[sizeClass] = getNextCell(cell);
//     VM.sysWrite("a "); VM.sysWrite(cell); VM.sysWrite(" ("); VM.sysWrite(getSuperPage(cell, sizeClass <= MAX_SMALL_SIZE_CLASS)); VM.sysWrite(")\n");

    incInUse(getSuperPage(cell, sizeClass <= MAX_SMALL_SIZE_CLASS));
    return cell;
  }

  /**
   * Allocate a large object.  Large objects are directly allocted and
   * freed in page-grained units via the vm resource.  This routine
   * does not guarantee that the space will have been zeroed.  The
   * caller must explicitly zero the space.
   *
   * @param isScalar True if the object to occupy this space will be a scalar.
   * @param bytes The required size of this space in bytes.
   * @return The address of the start of the newly allocated region at
   * least <code>bytes</code> bytes in size.
   */
  private final VM_Address allocLarge(boolean isScalar, EXTENT bytes) {
    bytes += superPageHeaderSize(LARGE_SIZE_CLASS) + cellHeaderSize(LARGE_SIZE_CLASS);
    int pages = (bytes + PAGE_SIZE - 1)>>LOG_PAGE_SIZE;
    VM_Address sp = allocSuperPage(pages);
    setSizeClass(sp, LARGE_SIZE_CLASS);
//     VM.sysWrite("************\n");
    return initializeCell(sp.add(superPageHeaderSize(LARGE_SIZE_CLASS)), sp, false);
  }
  
  /**
   * Repopulate the freelist with cells of size class
   * <code>sizeClass</code>.  This is done by allocating more global
   * memory from the vm resource, breaking that memory up into cells
   * and placing those cells on the free list.
   *
   * @param sizeClass The size class that needs to be repopulated.
   */
  private final void expandSizeClass(int sizeClass) {
    // grab superpage
    int pages = pagesForClassSize(sizeClass);
    VM_Address sp = allocSuperPage(pages);
    int spSize = pages<<LOG_PAGE_SIZE;
    
    // set up sizeclass info
    setSizeClass(sp, sizeClass);
    setInUse(sp, 0);
    
//     VM.sysWrite("expand size class: ");
//     VM.sysWrite(sp); VM.sysWrite("+");
//     VM.sysWrite(superPageHeaderSize(sizeClass));
//     VM.sysWrite(" (");

    // bust up and add to free list
    int cellExtent = cellSize(sizeClass);
    VM_Address sentinal = sp.add(spSize);
    VM_Address cursor = sp.add(superPageHeaderSize(sizeClass));
    while (cursor.add(cellExtent).LE(sentinal)) {
      VM_Address cell = initializeCell(cursor, sp, 
				       (sizeClass <= MAX_SMALL_SIZE_CLASS));
      setNextCell(cell, freeList[sizeClass]);
      freeList[sizeClass] = cell;
//       VM.sysWrite(cell); VM.sysWrite(", ");
      cursor = cursor.add(cellExtent);
    }
//     VM.sysWrite(")\n");
				   
    // do other sub-class specific stuff
    postExpandSizeClass(sp, sizeClass);
    //    sanity();
  }

  /**
   * Remove all of the cells in a superpage from the free list.  This
   * is necessary when all of the cells on a superpage are free (on
   * the free list), and the superpage may be freed.  But the
   * superpage must not be freed while any of its constituent cells
   * remain on the free list.<p>
   *
   * The current implementation is BRUTE FORCE, searching the entire
   * free list chain for cells in this superpage.  A smarter approach
   * would be to have the free list cells doubly linked, and to just
   * iterate through the cells on this superpage, unlinking them.
   *
   * @param sp The superpage whose free cells are to be taken off the
   * free list.
   * @param sizeClass The size class for the cells of that superpage.
   */
  private final void releaseSuperPageCells(VM_Address sp, int sizeClass) {
    // unlink all cells from the free list...
    // FIXME BRUTE FORCE 
    VM_Address spEnd = sp.add(pagesForClassSize(sizeClass)<<LOG_PAGE_SIZE);
    VM_Address cell = freeList[sizeClass];
    VM_Address last = VM_Address.zero();
    while (cell.NE(VM_Address.zero())) {
      VM_Address next = getNextCell(cell);
      if (cell.GT(sp) && cell.LT(spEnd)) {
	if (last.EQ(VM_Address.zero())) {
	  freeList[sizeClass] = next;
	} else
	  setNextCell(last, next);
      } else
	last = cell;
      cell = next;
    }
  }
  
  /**
   * Allocate a super page and link it to the list of super pages
   * owned by this freelist instance.
   *
   * @param pages The size of the superpage in pages.
   * @return The address of the first word of the superpage.
   */
  private final VM_Address allocSuperPage(int pages) {
    VM_Address sp = vmResource.acquire(pages, memoryResource);

    // link superpage
//     VM.sysWrite("super page: "); VM.sysWrite(sp); VM.sysWrite("\n");
//     VM.sysWrite("PAGE_MASK: "); VM.sysWrite(PAGE_MASK.toAddress()); VM.sysWrite("\n");
    setNextSuperPage(sp, headSuperPage);
    setPrevSuperPage(sp, VM_Address.zero());
    if (!headSuperPage.EQ(VM_Address.zero()))
      setPrevSuperPage(headSuperPage, sp);
    headSuperPage = sp;

    return sp;
  }

  /**
   * Return a superpage to the global page pool by freeing it with the
   * vm resource.  Before this is done the super page is unlinked from
   * the linked list of super pages for this free list
   * instance. Importantly, if the superpage contains free cells,
   * those cells must be removed from the freelist before the superpage is
   * freed.
   *
   * @param sp The superpage to be freed.
   * @see releaseSuperPageCells
   */
  private final void freeSuperPage(VM_Address sp) {
    // unlink superpage
    VM_Address next = getNextSuperPage(sp);
    VM_Address prev = getPrevSuperPage(sp);
    if (prev.NE(VM_Address.zero()))
      setNextSuperPage(prev, next);
    else
      headSuperPage = next;
    if (next.NE(VM_Address.zero()))
      setPrevSuperPage(next, prev);

    // free it
    vmResource.release(sp, memoryResource);
  }

  /**
   * Get the size class for a given number of bytes.<p>
   *
   * We use size classes based on a worst case fragmentation loss
   * target of 1/8.  In fact, across sizes from 8 bytes to 512 the
   * average worst case loss is 13.3%, giving an expected loss
   * (assuming uniform distribution) of about 7%.  We avoid using
   * the Lea class sizes because they were so numerous and therefore
   * liable to lead to excessive inter-class-size fragmentation.<p>
   * 
   * This routine may segregate arrays and scalars (currently it does
   * not).
   *
   * @param isScalar True if the object to occupy the allocated space
   * will be a scalar (i.e. not a array).
   * @param bytes The number of bytes required to accommodate the
   * object to be allocated.
   * @return The size class capable of accomodating the allocation
   * request.  If the request is sufficiently large then
   * <code>LARGE_SIZE_CLASS</code> will be returned, indicating that
   * the request will not be satisfied by the freelist, but must be
   * dealt with explicitly as a large object.
   */
  protected static final int getSizeClass(boolean isScalar, EXTENT bytes)
    throws VM_PragmaInline {
    int sz1 = bytes - 1;
    int offset = 0;  // = isScalar ? BASE_SIZE_CLASSES : 0;
    return ((sz1 <=   63) ? offset +      (sz1 >>  2): //    4 bytes apart
	    (sz1 <=  127) ? offset + 8  + (sz1 >>  3): //    8 bytes apart
	    (sz1 <=  255) ? offset + 16 + (sz1 >>  4): //   16 bytes apart
	    (sz1 <=  511) ? offset + 24 + (sz1 >>  5): //   32 bytes apart
	    (sz1 <= 2047) ? offset + 38 + (sz1 >>  8): //  256 bytes apart
	    (sz1 <= 8192) ? offset + 44 + (sz1 >> 10): // 1024 bytes apart
	    LARGE_SIZE_CLASS);           // large object, not on free list
  }

  /**
   * Return the size of a basic cell (i.e. not including any cell
   * header) for a given size class.
   *
   * @param sc The size class in question
   * @return The size of a basic cell (i.e. not including any cell
   * header).
   */
  protected static final int getBaseCellSize(int sc) 
    throws VM_PragmaInline {
    if (VM.VerifyAssertions) {
      VM._assert(sc != LARGE_SIZE_CLASS);
      VM._assert(sc < BASE_SIZE_CLASSES);
    }
    return ((sc < 16) ? (sc +  1) <<  2:
	    (sc < 24) ? (sc -  7) <<  3:
	    (sc < 32) ? (sc - 15) <<  4:
	    (sc < 40) ? (sc - 23) <<  5:
	    (sc < 46) ? (sc - 37) <<  8:
	                (sc - 43) << 10);
  }


  /**
   * Return the number of pages that should be used for a superpage of
   * a given class size, given some fit target,
   * <code>MID_SIZE_FIT_TARGET</code>.
   *
   * @param sizeClass The size class which will occupy the superpage
   * @param cellSize The space occupied by each cell (inclusive of
   * headers etc).
   * @param spHeaderSize The superpage header size.
   * @return The number of pages that should be used for a superpage
   * for this size class given the fit criterion
   * <code>MID_SIZE_FIT_TARGET</code>.
   */
  protected static final int optimalPagesForSuperPage(int sizeClass,
						      int cellSize,
						      int spHeaderSize) 
    throws VM_PragmaInline {
    if (VM.VerifyAssertions) {
      VM._assert(sizeClass > MAX_SMALL_SIZE_CLASS);
      VM._assert(sizeClass < BASE_SIZE_CLASSES);
    }

    int size = spHeaderSize;
    int pages = 1;
    float fit = 0;
    int cells = 0;
    while (fit < MID_SIZE_FIT_TARGET) {
      cells++;
      size += cellSize;
      pages = (size + PAGE_SIZE - 1)>>LOG_PAGE_SIZE;
      fit = (float) size/(float) (pages<<LOG_PAGE_SIZE);
    }
    VM.sysWrite("    cells: "+cells+" pages: "+pages+" size: "+size+"/"+(pages<<LOG_PAGE_SIZE)+" fit: "+fit+"\n");
    return pages;
  }

  protected void setNextCell(VM_Address cell, VM_Address next)
    throws VM_PragmaInline {
    VM_Magic.setMemoryAddress(cell, next);
  }

  protected VM_Address getNextCell(VM_Address cell)
    throws VM_PragmaInline {
    return VM_Magic.getMemoryAddress(cell);
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Private and protected methods relating to the structure of
  // superpages.
  //
  // The following illustrates the structure of small, medium and
  // large superpages.  Small superpages are a single page in size,
  // therefore the start of the superpage can be trivially established
  // by masking the low order bits of the cell address.  Medium and
  // large superpages use the first word of each cell to point to the
  // start of the superpage.  Large pages are a trivial case of medium
  // page (where there is only one cell).
  //
  // key:      prev  previous superpage in linked list
  //           next  next superpage in linked list
  //           IU    "in-use" counter (low order bits)
  //           SC    "size class"  (low order bits)
  //           ssss  *optional* per-superblock sub-class-specific metadata
  //           cccc  *optional* per-cell sub-class-specific metadata
  //           uuuu  user-usable space
  //
  //
  //          small              medium               large
  //        +-------+           +-------+           +-------+
  //        |prev|IU|        +->|prev|IU|        +->|prev|IU|
  //        +-------+        |  +-------+        |  +-------+
  //        |next|SC|        |  |next|SC|        |  |next|SC|
  //        +-------+        |  +-------+        |  +-------+
  //        |sssssss|        |  |sssssss|        |  |sssssss|
  //           ...           |     ...           |     ... 
  //        |sssssss|        |  |sssssss|        |  |sssssss|
  //        +-------+        |  +-------+        |  +-------+
  //        |ccccccc|        +--+---    |        +--+---    |
  //        |uuuuuuu|        |  +-------+           +-------+
  //        |uuuuuuu|        |  |ccccccc|           |ccccccc|
  //        +-------+        |  |uuuuuuu|           |uuuuuuu|
  //        |ccccccc|        |     ...              |uuuuuuu| 
  //        |uuuuuuu|        |  |uuuuuuu|           |uuuuuuu|
  //        |uuuuuuu|        |  +-------+           |uuuuuuu|
  //        +-------+        +--+---    |              ...   
  //        |ccccccc|           +-------+
  //        |uuuuuuu|           |ccccccc|
  //           ...              |uuuuuuu|
  //                               ...

  /**
   * Return the superpage for a given cell.  If the cell is a small
   * cell then this is found by masking the cell address to find the
   * containing page.  Otherwise the first word of the cell contains
   * the address of the page.
   *
   * @param cell The address of the first word of the cell.
   * @param small True if the cell is a small cell (single page superpage).
   * @return The address of the first word of the superpage containing
   * <code>cell</code>.
   */
  protected static final VM_Address getSuperPage(VM_Address cell, boolean small)
    throws VM_PragmaInline {
    VM_Address rtn;
    //    VM.sysWrite("getSuperPage: "); VM.sysWrite(cell); VM.sysWrite((small ? " s\n" : " b\n"));
    if (small)
      rtn = cell.toWord().and(PAGE_MASK).toAddress();
    else {
      rtn = VM_Magic.getMemoryAddress(cell.sub(WORD_SIZE));
      if (VM.VerifyAssertions)
	VM._assert(rtn.EQ(rtn.toWord().and(PAGE_MASK).toAddress()));
    }
    return rtn;
  }

  private static final void setPrevSuperPage(VM_Address sp, VM_Address prev) 
    throws VM_PragmaInline {
    //    VM.sysWrite("setting prev: ");
    setSuperPageLink(sp, prev, true);
  }
  private static final void setNextSuperPage(VM_Address sp, VM_Address next) 
    throws VM_PragmaInline {
    //    VM.sysWrite("setting next: ");
    setSuperPageLink(sp, next, false);
  }
  /**
   * Set the prev or next link fields of a superpage, taking care not
   * to overwrite the low-order bits where the "in-use" and "size
   * class" info are stored.
   *
   * @param sp The address of the first word of the super page
   * @param link The address of the super page this link is to point to
   * @param prev True if the "prev" field is to be set, false if the
   * "next" field is to be set.
   */
  private static final void setSuperPageLink(VM_Address sp, VM_Address link, 
					     boolean prev) 
    throws VM_PragmaInline {

    if (VM.VerifyAssertions) {
      VM._assert(sp.toWord().EQ(sp.toWord().and(PAGE_MASK)));
      VM._assert(link.toWord().EQ(link.toWord().and(PAGE_MASK)));
    }
    sp = sp.add(prev ? PREV_SP_OFFSET : NEXT_SP_OFFSET);
    //    VM.sysWrite("link address: "); VM.sysWrite(sp); VM.sysWrite("\n");
    VM_Word wd = VM_Word.fromInt(VM_Magic.getMemoryWord(sp));
    wd = wd.and(PAGE_MASK.not()).or(link.toWord());
    VM_Magic.setMemoryWord(sp, wd.toInt());
  }
  private static final VM_Address getPrevSuperPage(VM_Address sp) 
    throws VM_PragmaInline {
    return getSuperPageLink(sp, true);
  }
  private static final VM_Address getNextSuperPage(VM_Address sp) 
    throws VM_PragmaInline {
    return getSuperPageLink(sp, false);
  }
  private static final VM_Address getSuperPageLink(VM_Address sp, boolean prev)
    throws VM_PragmaInline {
    if (VM.VerifyAssertions)
      VM._assert(sp.toWord().EQ(sp.toWord().and(PAGE_MASK)));

    sp = sp.add(prev ? PREV_SP_OFFSET : NEXT_SP_OFFSET);
    VM_Word wd = VM_Word.fromInt(VM_Magic.getMemoryWord(sp));
    return wd.and(PAGE_MASK).toAddress();
  }
  private static final void setSizeClass(VM_Address sp, int sc)
    throws VM_PragmaInline {
    if (VM.VerifyAssertions)
      VM._assert(sp.toWord().EQ(sp.toWord().and(PAGE_MASK)));
    
    sp = sp.add(SIZE_CLASS_WORD_OFFSET);
    //    VM.sysWrite("set sizeclass address: "); VM.sysWrite(sp); VM.sysWrite("\n");
    VM_Word wd = VM_Word.fromInt(VM_Magic.getMemoryWord(sp));
    wd = wd.and(PAGE_MASK).or(VM_Word.fromInt(sc));
    VM_Magic.setMemoryWord(sp, wd.toInt());
  }
  protected static final int getSizeClass(VM_Address sp)
    throws VM_PragmaInline {
    if (VM.VerifyAssertions)
      VM._assert(sp.toWord().EQ(sp.toWord().and(PAGE_MASK)));
    
    sp = sp.add(SIZE_CLASS_WORD_OFFSET);
    //    VM.sysWrite("get sizeclass address: "); VM.sysWrite(sp); VM.sysWrite("\n");
    VM_Word wd = VM_Word.fromInt(VM_Magic.getMemoryWord(sp));
    return wd.and(PAGE_MASK.not()).toInt();
  }
  private static final void setInUse(VM_Address sp, int value)
    throws VM_PragmaInline {
    if (VM.VerifyAssertions)
      VM._assert(sp.toWord().EQ(sp.toWord().and(PAGE_MASK)));

    sp = sp.add(IN_USE_WORD_OFFSET);
    //    VM.sysWrite("set inuse address: "); VM.sysWrite(sp); VM.sysWrite("\n");
    VM_Word wd = VM_Word.fromInt(VM_Magic.getMemoryWord(sp)).and(PAGE_MASK);;
    VM_Magic.setMemoryWord(sp, wd.toInt());
  }

  protected static final int getInUse(VM_Address sp)
    throws VM_PragmaInline {
    if (VM.VerifyAssertions)
      VM._assert(sp.toWord().EQ(sp.toWord().and(PAGE_MASK)));

    //    VM.sysWrite("get inuse address: "); VM.sysWrite(sp); VM.sysWrite("\n");
    sp = sp.add(IN_USE_WORD_OFFSET);
    VM_Word wd = VM_Word.fromInt(VM_Magic.getMemoryWord(sp)).and(PAGE_MASK.not());
    return wd.toInt();
  }
  private static final int incInUse(VM_Address sp)
    throws VM_PragmaInline {
    return changeInUse(sp, 1);
  }
  private static final int decInUse(VM_Address sp)
    throws VM_PragmaInline {
    return changeInUse(sp, -1);
  }
  private static final int changeInUse(VM_Address sp, int delta)
    throws VM_PragmaInline {
    if (VM.VerifyAssertions)
      VM._assert(sp.toWord().EQ(sp.toWord().and(PAGE_MASK)));

    sp = sp.add(IN_USE_WORD_OFFSET);
    //    VM.sysWrite("change inuse address: "); VM.sysWrite(sp); VM.sysWrite("\n");
//     VM.sysWrite("change inuse delta: "); VM.sysWrite(delta); VM.sysWrite("\n");
//     VM.sysWrite("change inuse old value: "); VM.sysWrite(VM_Magic.getMemoryAddress(sp)); VM.sysWrite("\n");
    VM_Word wd = VM_Word.fromInt(VM_Magic.getMemoryWord(sp)).add(VM_Word.fromInt(delta));
//     VM.sysWrite("change inuse address: "); VM.sysWrite(sp); VM.sysWrite("\n");
    int value = wd.and(PAGE_MASK.not()).toInt();
    if (VM.VerifyAssertions)
      VM._assert(value < PAGE_SIZE);
    VM_Magic.setMemoryWord(sp, wd.toInt());
//     VM.sysWrite("change inuse new value: "); VM.sysWrite(VM_Magic.getMemoryAddress(sp)); VM.sysWrite("\n");
    return value;
  }

  private final void sanity() {
    VM_Address sp = headSuperPage;
    int pages = 0, cells = 0, inUse = 0, inUseBytes = 0, free = 0, freeBytes = 0;
    VM.sysWrite("=============\n");
    while (!sp.EQ(VM_Address.zero())) {
      VM.sysWrite("super page: ");VM.sysWrite(sp);VM.sysWrite("\n"); 
      VM.sysWrite("  next: ");VM.sysWrite(getNextSuperPage(sp));VM.sysWrite("\n");
      VM.sysWrite("  prev: ");VM.sysWrite(getPrevSuperPage(sp));VM.sysWrite("\n");
      pages += vmResource.getSize(sp);
      VM.sysWrite("  pages: ");VM.sysWrite(vmResource.getSize(sp));VM.sysWrite("\n");
      VM.sysWrite("  size class: ");VM.sysWrite(getSizeClass(sp));VM.sysWrite("\n");
      if (getSizeClass(sp) != LARGE_SIZE_CLASS) {
	VM.sysWrite("    size class bytes: ");VM.sysWrite(getBaseCellSize(getSizeClass(sp)));VM.sysWrite("\n");
	cells += cellsInSuperPage(sp);
	VM.sysWrite("    cells: ");VM.sysWrite(cellsInSuperPage(sp));VM.sysWrite("\n");
	inUse += getInUse(sp);
	inUseBytes += (getInUse(sp)*getBaseCellSize(getSizeClass(sp)));
	VM.sysWrite("    in use: ");VM.sysWrite(getInUse(sp));VM.sysWrite("\n");
	int f = countFree(sp);
	free += f;
	freeBytes += (f*getBaseCellSize(getSizeClass(sp)));
	VM.sysWrite("    free: ");VM.sysWrite(f);VM.sysWrite("\n");
	if (VM.VerifyAssertions)
	  VM._assert((getInUse(sp)+f) == cellsInSuperPage(sp));
      }
      superPageSanity(sp);
      sp = getNextSuperPage(sp);
    }
    VM.sysWrite("-------------\n");
    VM.sysWrite("pages: "); VM.sysWrite(pages); VM.sysWrite("\n");
    VM.sysWrite("cells: "); VM.sysWrite(cells); VM.sysWrite("\n");
    VM.sysWrite("inUse: "); VM.sysWrite(inUse); VM.sysWrite(" ("); VM.sysWrite(inUseBytes); VM.sysWrite(")\n");
    VM.sysWrite("free: "); VM.sysWrite(free); VM.sysWrite(" ("); VM.sysWrite(freeBytes); VM.sysWrite(")\n");
    VM.sysWrite("utilization: ");VM.sysWrite((float) inUseBytes/(float) (inUseBytes+freeBytes));VM.sysWrite("\n");
    VM.sysWrite("=============\n");
  }
  private final int countFree(VM_Address sp) {
    int sc = getSizeClass(sp);
    if (VM.VerifyAssertions)
      VM._assert(sc != LARGE_SIZE_CLASS);
    VM_Address start = sp;
    VM_Address end = start.add(vmResource.getSize(sp)<<LOG_PAGE_SIZE);
    int free = 0;
    VM_Address f = freeList[sc];
//     VM.sysWrite("free: (");
    while (f.NE(VM_Address.zero())) {
      if (f.GE(start) && f.LT(end))
	free++;
//       VM.sysWrite(f);
//       VM.sysWrite(", ");
      f = getNextCell(f);
    }
//     VM.sysWrite(")\n");
    return free;
  }
  protected final boolean isFree(VM_Address cell, int sizeClass) {
    VM_Address f = freeList[sizeClass];
    while (f.NE(VM_Address.zero()) && f.NE(cell))
      f = getNextCell(f);
    return f.EQ(cell);
  }

  private final int cellsInSuperPage(VM_Address sp) {
    int spBytes = vmResource.getSize(sp)<<LOG_PAGE_SIZE;
    spBytes -= superPageHeaderSize(getSizeClass(sp));
    return spBytes/cellSize(getSizeClass(sp));
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Static final values (aka constants)
  //
  private static final int BASE_SIZE_CLASSES = 52;
  protected static final int SIZE_CLASSES = BASE_SIZE_CLASSES;
  protected static final int LARGE_SIZE_CLASS = 0;
  protected static final int MAX_SMALL_SIZE_CLASS = 39;
  protected static final int NON_SMALL_OBJ_HEADER_SIZE = WORD_SIZE;
  private static final int PREV_SP_OFFSET = 0;
  private static final int NEXT_SP_OFFSET = WORD_SIZE;
  private static final int IN_USE_WORD_OFFSET = PREV_SP_OFFSET;
  private static final int SIZE_CLASS_WORD_OFFSET = NEXT_SP_OFFSET;
  protected static final int BASE_SP_HEADER_SIZE = 2 * WORD_SIZE;
  protected static final VM_Word PAGE_MASK = VM_Word.fromInt(~((1<<LOG_PAGE_SIZE) - 1));
  private static final float MID_SIZE_FIT_TARGET = (float) 0.85; // 20% wastage


  private VM_Address headSuperPage;
  protected NewFreeListVMResource vmResource;
  protected MemoryResource memoryResource;
  protected VM_Address[] freeList;

}
