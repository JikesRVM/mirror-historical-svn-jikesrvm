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
import com.ibm.JikesRVM.VM_ObjectModel;
import com.ibm.JikesRVM.VM_JavaHeader;
/*
 * Each instance of this class corresponds to one mark-sweep *space*.
 * Each of the instance methods of this class may be called by any
 * thread (i.e. synchronization must be explicit in any instance or
 * class method).  This contrasts with the MarkSweepAllocator, where
 * instances correspond to *plan* instances and therefore to kernel
 * threads.  Thus unlike this class, synchronization is not necessary
 * in the instance methods of MarkSweepAllocator.
 */
final class MarkSweepCollector implements Constants, VM_Uninterruptible {
  public final static String Id = "$Id$"; 

  /**
   * Constructor
   *
   * @param vmr The virtual memory resource from which this bump
   * pointer will acquire virtual memory.
   */
  MarkSweepCollector(NewFreeListVMResource vmr, MemoryResource mr) {
    vmResource = vmr;
    memoryResource = mr;
    treadmillLock = new Lock("MarkSweep.treadmillLock");
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Public instance methos (i.e. methods whose scope is limited to a
  // particular space that is collected under a mark-sweep policy).
  //

  /**
   * Prepare for a new collection increment.  For the mark-sweep
   * collector we must flip the state of the mark bit between
   * collections.
   *
   * @param vm (unused)
   * @param mr (unused)
   */
  public void prepare(VMResource vm, MemoryResource mr) { 
    treadmillToHead = VM_Address.zero();
    markState = MarkSweepHeader.MARK_BIT_MASK - markState;
  }

  /**
   * A new collection increment has completed.  For the mark-sweep
   * collector this means we can perform the sweep phase.
   *
   * @param vm (unused)
   * @param mr (unused)
   */
  public void release(MarkSweepAllocator allocator) { 
    sweep(allocator);
  }

  /**
   * A new collection increment has completed.  For the mark-sweep
   * collector this means we can perform the sweep phase.
   *
   * @param obj The object in question
   * @return True if this object is known to be live (i.e. it is marked)
   */
   public boolean isLive(VM_Address obj)
    throws VM_PragmaInline {
     return MarkSweepHeader.testMarkBit(obj, markState);
   }

  /**
   * Trace a reference to an object under a mark sweep collection
   * policy.  If the object header is not already marked, mark the
   * object in either the bitmap or by moving it off the treadmill,
   * and enqueue the object for subsequent processing. The object is
   * marked as (an atomic) side-effect of checking whether already
   * marked.
   *
   * @param object The object to be traced.
   * @return The object (there is no object forwarding in this
   * collector, so we always return the same object: this could be a
   * void method but for compliance to a more general interface).
   */
  public final VM_Address traceObject(VM_Address object)
    throws VM_PragmaInline {
    if (MarkSweepHeader.testAndMark(object, markState)) {
      internalMarkObject(object);
      VM_Interface.getPlan().enqueue(object);
    }
    return object;
  }

  public final int getInitialHeaderValue(int size) 
    throws VM_PragmaInline {
    if (size <= MAX_SMALL_SIZE)
      return markState | MarkSweepHeader.SMALL_OBJECT_MASK;
    else
      return markState;
  }

  public final NewFreeListVMResource getVMResource() 
    throws VM_PragmaInline {
    return vmResource;
  }

  public final MemoryResource getMemoryResource() 
    throws VM_PragmaInline {
    return memoryResource;
  }

  public final void sweep(MarkSweepAllocator allocator) {
    // sweep the small objects
    allocator.sweepSuperPage();
    // sweep the large objects
    sweepLarge();
  }

  public final void sweepSuperPage(MarkSweepAllocator allocator,
				   VM_Address sp, int szclass, 
					int cellSize)
    throws VM_PragmaInline {
//     if (!MarkSweepAllocator.isSmall(szclass))
//       VM.sysWrite("------------ sweep -----------\n");
    VM_Address base = sp.add(BITMAP_BASE);
    boolean small = MarkSweepAllocator.isSmall(szclass);
    int bitmapPairs = small ? SMALL_BITMAP_PAIRS : MID_BITMAP_PAIRS;
    for (int pair = 0; pair < bitmapPairs; pair++) {
      if (VM.VerifyAssertions)
	VM._assert((INUSE_BITMAP_OFFSET == 0) 
		   && (MARK_BITMAP_OFFSET == WORD_SIZE));
      VM_Address inUseBitmap = base;
      base = base.add(WORD_SIZE);
      VM_Address markBitmap = base;
      base = base.add(WORD_SIZE);
      int inuse = VM_Magic.getMemoryWord(inUseBitmap);
      int mark = VM_Magic.getMemoryWord(markBitmap);
      int free = mark ^ inuse;
      if (free != 0) {
	// free them up
// 	if (!small) {
// 	  VM.sysWrite("--->");
// 	  VM.sysWrite(inUseBitmap); VM.sysWrite(": ");
// 	  VM.sysWriteHex(inuse); VM.sysWrite(" ");
// 	  VM.sysWriteHex(mark); VM.sysWrite(" ");
// 	  VM.sysWriteHex(free); VM.sysWrite("\n");
// 	}
	freeFromBitmap(allocator, sp, free, szclass, cellSize, pair, small);
	VM_Magic.setMemoryWord(inUseBitmap, mark); 
      }
      if (mark != 0)
	VM_Magic.setMemoryWord(markBitmap, 0);
    }
//     if (!MarkSweepAllocator.isSmall(szclass))
//       VM.sysWrite("============ sweep ===========\n");
  }
  private final void freeFromBitmap(MarkSweepAllocator allocator, 
				    VM_Address sp, int free, int szclass,
				    int cellSize, int pair, boolean small)
    throws VM_PragmaInline {
    int index = (pair<<LOG_WORD_BITS);
    VM_Address base = sp.add(small ? SMALL_OBJ_BASE : MID_OBJ_BASE);
    for(int i=0; i < WORD_BITS; i++) {
      if ((free & (1<<i)) != 0) {
	int offset = (index + i)*cellSize + (small ? 0 : MarkSweepAllocator.NON_SMALL_OBJ_HEADER_SIZE);
	VM_Address cell = base.add(offset);
// 	if (!MarkSweepAllocator.isSmall(szclass)) {
// 	  VM.sysWrite("freeing "); VM.sysWrite(cell); VM.sysWrite(" "); VM.sysWrite(index+i); VM.sysWrite(" "); VM.sysWrite(szclass); VM.sysWrite("...\n");
// 	}
	allocator.free(cell, sp, szclass);
// 	if (!MarkSweepAllocator.isSmall(szclass)) {
// 	  VM.sysWrite(cell); VM.sysWrite(" f "); VM.sysWrite(sp); VM.sysWrite("\n");
// 	}
      }
    }
  }

  private final void sweepLarge() {
  }
  private final void internalMarkObject(VM_Address object) 
    throws VM_PragmaInline {
    VM_Address ref = VM_JavaHeader.getPointerInMemoryRegion(object);
//      VM.sysWrite(cell); VM.sysWrite(" m ");
//    if (bytes <= MarkSweepAllocator.MAX_SMALL_SIZE) {
//       VM.sysWrite(MarkSweepAllocator.getSuperPage(cell, true)); VM.sysWrite("\n");
    if (MarkSweepHeader.isSmallObject(VM_Magic.addressAsObject(object))) {
      setMarkBit(ref, MarkSweepAllocator.getSuperPage(ref, true), true);
//       if (VM.VerifyAssertions)
// 	VM._assert(MarkSweepHeader.isSmallObject(VM_Magic.addressAsObject(object)));
    } else {
      VM_Address cell = VM_JavaHeader.objectStartRef(object);
      VM_Address sp = MarkSweepAllocator.getSuperPage(cell, false);
//       VM.sysWrite(cell); VM.sysWrite(" m "); VM.sysWrite(sp); VM.sysWrite("\n");
      int sizeClass = MarkSweepAllocator.getSizeClass(sp);
      if (MarkSweepAllocator.isLarge(sizeClass))
	moveToTreadmill(cell, true);
      else
	setMarkBit(cell, sp, false);
//       if (VM.VerifyAssertions)
// 	VM._assert(!MarkSweepHeader.isSmallObject(VM_Magic.addressAsObject(object)));
    }
  }

  public final boolean isOnTreadmill(VM_Address cell) {
    VM_Address next = treadmillFromHead;
//     VM.sysWrite("Treadmill: ");
//     VM.sysWrite(cell);
//     VM.sysWrite("? (");
    while (next.NE(VM_Address.zero())) {
//       VM.sysWrite(next);
      if (next.EQ(cell)) {
// 	VM.sysWrite(")\n");
	return true;
      }
//       VM.sysWrite(", ");
      next = getNextTreadmill(next);
    }
//     VM.sysWrite(")\n");
    return false;
  }
  
  public void addToTreadmill(VM_Address cell) 
    throws VM_PragmaInline {
    moveToTreadmill(cell, false);
  }

  private void moveToTreadmill(VM_Address cell, boolean to) 
    throws VM_PragmaInline {
    treadmillLock.acquire();
    if (to) {
      // remove from "from" treadmill
      VM_Address prev = getPrevTreadmill(cell);
      VM_Address next = getNextTreadmill(cell);
      //      VM.sysWrite("mtt: "); VM.sysWrite(cell); VM.sysWrite(", "); VM.sysWrite(prev); VM.sysWrite(", "); VM.sysWrite(next); VM.sysWrite("\n");
      if (!prev.EQ(VM_Address.zero()))
	setNextTreadmill(prev, next);
      else
	treadmillFromHead = next;
      if (!next.EQ(VM_Address.zero()))
	setPrevTreadmill(next, prev);
    }

    // add to treadmill
    VM_Address head = (to ? treadmillToHead : treadmillFromHead);
    //    VM.sysWrite("at: "); VM.sysWrite(cell); VM.sysWrite(", "); VM.sysWrite(head); VM.sysWrite("\n");
    setNextTreadmill(cell, head);
    setPrevTreadmill(cell, VM_Address.zero());
    if (!head.EQ(VM_Address.zero()))
      setPrevTreadmill(head, cell);
    if (to)
      treadmillToHead = cell;
    else
      treadmillFromHead = cell;

    treadmillLock.release();
  }

  public static void setInUseBit(VM_Address ref, VM_Address sp, boolean small)
    throws VM_PragmaInline {
    changeBit(ref, sp, small, true, true, false);
  }
  private static void unsetInUseBit(VM_Address ref, VM_Address sp,
				    boolean small)
    throws VM_PragmaInline {
    changeBit(ref, sp, small, false, true, false);
  }
  private static void setMarkBit(VM_Address ref, VM_Address sp, boolean small)
    throws VM_PragmaInline {
    changeBit(ref, sp, small, true, false, true);
  }
  public static boolean getInUseBit(VM_Address ref, VM_Address sp,
				    boolean small)
    throws VM_PragmaInline {
    return getBit(ref, sp, small, true);
  }
  private static boolean getMarkBit(VM_Address ref, VM_Address sp, 
				    boolean small)
    throws VM_PragmaInline {
    return getBit(ref, sp, small, false);
  }
  private static void changeBit(VM_Address ref, VM_Address sp, boolean small,
				boolean set, boolean inuse, boolean sync)
    throws VM_PragmaInline {
//      VM.sysWrite("word: "); VM.sysWrite(ref); VM.sysWrite(", "); 
    int index = getCellIndex(ref, sp, small);
    VM_Word mask = getBitMask(index);
    VM_Address addr = getBitMapWord(index, sp, inuse, small);
//     VM.sysWrite("modifying word: "); VM.sysWrite(addr); VM.sysWrite("\n");
    if (sync)
      syncSetBit(addr, mask, set);
    else
      unsyncSetBit(addr, mask, set);
//     VM.sysWrite("--->"); VM.sysWrite(addr); VM.sysWrite(": "); VM.sysWrite(VM_Magic.getMemoryAddress(addr)); VM.sysWrite("\n");
  }
  private static boolean getBit(VM_Address ref, VM_Address sp, boolean small,
				boolean inuse)
    throws VM_PragmaInline {
    int index = getCellIndex(ref, sp, small);
    VM_Word mask = getBitMask(index);
    VM_Address addr = getBitMapWord(index, sp, inuse, small);
    VM_Word value = VM_Word.fromInt(VM_Magic.getMemoryWord(addr));
    return mask.EQ(value.and(mask));
  }
  private static int getCellIndex(VM_Address ref, VM_Address sp, boolean small)
    throws VM_PragmaInline {
    int cellSize = MarkSweepAllocator.getCellSize(sp);
    if (small) {
      sp = sp.add(SMALL_OBJ_BASE);
    } else {
      //      VM._assert(false);  ///not ready yet
      sp = sp.add(MID_OBJ_BASE);
      //    VM.sysWrite("index "); VM.sysWrite(ref); VM.sysWrite(" "); VM.sysWrite(ref.diff(sp).toInt()); VM.sysWrite(" "); VM.sysWrite(cellSize); VM.sysWrite(" "); VM.sysWrite((ref.diff(sp).toInt()/cellSize)); VM.sysWrite("\n");
    }
    return ref.diff(sp).toInt()/cellSize;
  }
  private static VM_Word getBitMask(int index)
    throws VM_PragmaInline {
    int bitnumber = index & (WORD_BITS - 1);
    if (VM.VerifyAssertions)
      VM._assert((bitnumber >= 0) && (bitnumber < WORD_BITS));
    return VM_Word.fromInt(1<<bitnumber);
  }
  private static VM_Address getBitMapWord(int index, VM_Address sp,
					  boolean inuse, boolean small)
    throws VM_PragmaInline {
    int offset = (index>>LOG_WORD_BITS)<<(LOG_WORD_SIZE + 1);
    if (inuse)
      offset += INUSE_BITMAP_OFFSET;
    else
      offset += MARK_BITMAP_OFFSET;
//     VM.sysWrite("word: "); VM.sysWrite(cell); VM.sysWrite(", "); 
//     VM.sysWrite(bitmapIndex);  VM.sysWrite(", "); VM.sysWrite(offset); VM.sysWrite(", "); VM.sysWrite(SMALL_BITMAP_SIZE); VM.sysWrite("\n");
//      VM.sysWrite(" "); VM.sysWrite(sp); VM.sysWrite(" ");VM.sysWrite(index);  VM.sysWrite(", "); VM.sysWrite(offset); VM.sysWrite(", "); VM.sysWrite(SMALL_BITMAP_SIZE); VM.sysWrite("\n");
    if (VM.VerifyAssertions)
      VM._assert((small && (offset < SMALL_BITMAP_SIZE))
		 || (!small && (offset < MID_BITMAP_SIZE)));
    return sp.add(BITMAP_BASE + offset);
  }
  private static void unsyncSetBit(VM_Address bitMapWord, VM_Word mask, 
				   boolean set) 
    throws VM_PragmaInline {
    VM_Word wd = VM_Word.fromInt(VM_Magic.getMemoryWord(bitMapWord));
    if (set)
      wd = wd.or(mask);
    else
      wd = wd.and(mask.not());

    VM_Magic.setMemoryWord(bitMapWord, wd.toInt());
  }
  private static void syncSetBit(VM_Address bitMapWord, VM_Word mask, 
				 boolean set) 
    throws VM_PragmaInline {
    Object tgt = VM_Magic.addressAsObject(bitMapWord);
    VM_Word oldValue, newValue;
    do {
      oldValue = VM_Word.fromInt(VM_Magic.prepare(tgt, 0));
      newValue = (set) ? oldValue.or(mask) : oldValue.and(mask.not());
    } while(!VM_Magic.attempt(tgt, 0, oldValue.toInt(), newValue.toInt()));
  }
  private static void setNextTreadmill(VM_Address cell, VM_Address value)
    throws VM_PragmaInline {
    setTreadmillLink(cell, value, false);
  }
  private static void setPrevTreadmill(VM_Address cell, VM_Address value)
    throws VM_PragmaInline {
    setTreadmillLink(cell, value, true);
  }
  private static void setTreadmillLink(VM_Address cell, VM_Address value,
				       boolean prev)
    throws VM_PragmaInline {
    int offset = (prev) ? TREADMILL_PREV_OFFSET : TREADMILL_NEXT_OFFSET;
    VM_Magic.setMemoryAddress(cell.add(offset), value);
  }
  private static VM_Address getNextTreadmill(VM_Address cell)
    throws VM_PragmaInline {
    return getTreadmillLink(cell, false);
  }
  private static VM_Address getPrevTreadmill(VM_Address cell)
    throws VM_PragmaInline {
    return getTreadmillLink(cell, true);
  }
  private static VM_Address getTreadmillLink(VM_Address cell, boolean prev)
    throws VM_PragmaInline {
    int offset = (prev) ? TREADMILL_PREV_OFFSET : TREADMILL_NEXT_OFFSET;
    return VM_Magic.getMemoryAddress(cell.add(offset));
  }

  
  ////////////////////////////////////////////////////////////////////////////
  //
  // The following methods, declared as abstract in the superclass, do
  // nothing in this implementation, so they have empty bodies.
  //
  private VM_Address treadmillFromHead;
  private VM_Address treadmillToHead;
  private Lock treadmillLock;
  private int markState;
  private NewFreeListVMResource vmResource;
  private MemoryResource memoryResource;

  private static final int LOG_BITMAP_GRAIN = 3;
  //  private static final int LOG_BITMAP_GRAIN = 4;
  private static final int BITMAP_GRAIN = 1<<LOG_BITMAP_GRAIN;
  private static final int BITMAP_ENTRIES = PAGE_SIZE>>LOG_BITMAP_GRAIN;
  private static final int SMALL_BITMAP_PAIRS = BITMAP_ENTRIES>>LOG_WORD_BITS;
  public static final int SMALL_BITMAP_SIZE = 2*(SMALL_BITMAP_PAIRS<<LOG_WORD_SIZE);
  private static final int MID_BITMAP_PAIRS = 1;
  public static final int MID_BITMAP_SIZE = 2*(MID_BITMAP_PAIRS<<LOG_WORD_SIZE);
  public static final int MAX_MID_OBJECTS = MID_BITMAP_PAIRS<<LOG_WORD_BITS;
  private static final int BITMAP_BASE = MarkSweepAllocator.BASE_SP_HEADER_SIZE;
  private static final int MAX_SMALL_SIZE = MarkSweepAllocator.MAX_SMALL_SIZE;
  private static final int SMALL_OBJ_BASE = BITMAP_BASE + SMALL_BITMAP_SIZE;
  private static final int MID_OBJ_BASE = BITMAP_BASE + MID_BITMAP_SIZE;
  private static final int LOG_PAIR_GRAIN = LOG_BITMAP_GRAIN + LOG_WORD_BITS;
  private static final int INUSE_BITMAP_OFFSET = 0;
  private static final int MARK_BITMAP_OFFSET = WORD_SIZE;
//   private static final int TREADMILL_PREV_OFFSET = -1 * WORD_SIZE;
//   private static final int TREADMILL_NEXT_OFFSET = -2 * WORD_SIZE;
  private static final int TREADMILL_PREV_OFFSET = -2 * WORD_SIZE;
  private static final int TREADMILL_NEXT_OFFSET = -3 * WORD_SIZE;
  public static final int TREADMILL_HEADER_SIZE = 2*WORD_SIZE;
}
