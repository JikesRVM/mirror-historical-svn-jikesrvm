/*
 * (C) Copyright IBM Corp. 2002
 */
//$Id$

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;
import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_AllocatorHeader;

import com.ibm.JikesRVM.VM_Constants;
import com.ibm.JikesRVM.VM_ProcessorLock;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Memory;
import com.ibm.JikesRVM.VM_ObjectModel;
import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_Array;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;

/**
 *  A mark-sweep area to hold "large" objects (typically at least 2K).
 *  The large space code is obtained by factoring out the code in various
 *  collectors.
 *
 *  @author Perry Cheng
 */
public class LargeObjectSpace implements Constants {

  public final static String Id = "$Id$"; 

  public void prepare (VMResource _vm, MemoryResource _mr) throws VM_PragmaUninterruptible {
      VM_Memory.zero(VM_Magic.objectAsAddress(largeSpaceMark), 
		     VM_Magic.objectAsAddress(largeSpaceMark).add(2*largeSpaceMark.length));
  }

  public void release (VMResource _vm, MemoryResource _mr) throws VM_PragmaUninterruptible {
      short[] temp    = largeSpaceAlloc;
      largeSpaceAlloc = largeSpaceMark;
      largeSpaceMark  = temp;
      large_last_allocated = 0;
  }

  // Internal management
  private MemoryResource mr;
  private MonotoneVMResource mvm;
  private VM_Address start;          
  private VM_Address end;
  private VM_Address lastAcquired;
  private VM_ProcessorLock spaceLock;        // serializes access to large space
  private final int pageSize = 4096;         // large space allocated in 4K chunks
  private final int GC_LARGE_SIZES = 20;           // for statistics  
  private final int GC_INITIAL_LARGE_SPACE_PAGES = 200; // for early allocation of large objs
  private int           largeSpacePages;
  private int		large_last_allocated;   // where to start search for free space
  private short[]	largeSpaceAlloc;	// used to allocate in large space
  private short[]	largeSpaceMark;		// used to mark large objects
  private int[]	        countLargeAlloc;	//  - count sizes of large objects alloc'ed


  /**
   * Initialize for boot image - called from init of various collectors
   */
  LargeObjectSpace(MonotoneVMResource _mvm, MemoryResource _mr) throws VM_PragmaUninterruptible {
    mvm = _mvm;
    mr = _mr;
    start = mvm.getStart();
    end = mvm.getEnd();
    lastAcquired = start;
    spaceLock       = new VM_ProcessorLock();      // serializes access to large space
    large_last_allocated = 0;
    largeSpacePages = GC_INITIAL_LARGE_SPACE_PAGES;
    countLargeAlloc = new int[GC_LARGE_SIZES];
  }


  /**
   * Initialize for execution.
   */
  public void setup () throws VM_PragmaUninterruptible {

    int size = end.diff(start).toInt();
    largeSpacePages = size / VM_Memory.getPagesize();
    
    // Get the (full sized) arrays that control large object space
    largeSpaceAlloc = VM_Interface.newImmortalShortArray(largeSpacePages + 1);
    largeSpaceMark  = VM_Interface.newImmortalShortArray(largeSpacePages + 1);
  }


  /**
   * Allocate size bytes of zeroed memory.
   * Size is a multiple of wordsize, and the returned memory must be word aligned
   * 
   * @param size Number of bytes to allocate
   * @return Address of allocated storage
   */
  protected VM_Address alloc (boolean isScalar, int size) throws VM_PragmaUninterruptible {

    if (largeSpaceAlloc == null) setup();  // not a good way to do it XXXXXX
    int count = 0;
    while (true) {

VM.sysWriteln("LOS.alloc 1");
      int num_pages = (size + (pageSize - 1)) / pageSize;    // Number of pages needed
      int last_possible = largeSpacePages - num_pages;

      spaceLock.lock();
VM.sysWriteln("LOS.alloc 2");
      while (largeSpaceAlloc[large_last_allocated] != 0) {
	large_last_allocated += largeSpaceAlloc[large_last_allocated];
      }
VM.sysWriteln("LOS.alloc 3");
      int first_free = large_last_allocated;
      while (first_free <= last_possible) {
	// Now find contiguous pages for this object
	// first find the first available page
	// i points to an available page: remember it
	int i;
	for (i = first_free + 1; i < first_free + num_pages ; i++) {
	  if (largeSpaceAlloc[i] != 0) break;
	}
VM.sysWriteln("LOS.alloc 50");
	if (i == (first_free + num_pages )) {  
	  // successful: found num_pages contiguous pages
	  // mark the newly allocated pages
	  // mark the beginning of the range with num_pages
	  // mark the end of the range with -num_pages
	  // so that when marking (ref is input) will know which extreme 
	  // of the range the ref identifies, and then can find the other
	  
	  largeSpaceAlloc[first_free + num_pages - 1] = (short)(-num_pages);
	  largeSpaceAlloc[first_free] = (short)(num_pages);
	       
	  spaceLock.unlock();  //release lock *and synch changes*
	  VM_Address result = start.add(VM_Memory.getPagesize() * first_free);
	  VM_Address resultEnd = result.add(size);
	  if (resultEnd.GT(lastAcquired)) {
	    int bytes = resultEnd.diff(lastAcquired).toInt();
	    int blocks = Conversions.bytesToBlocks(bytes);
	    VM_Address newArea = mvm.acquire(blocks);
	  }
	  VM_Memory.zero(result, resultEnd);
	  return result;
	} else {  
	  // free area did not contain enough contig. pages
	  first_free = i + largeSpaceAlloc[i]; 
	  while (largeSpaceAlloc[first_free] != 0) 
	    first_free += largeSpaceAlloc[first_free];
	}
VM.sysWriteln("LOS.alloc 90");
      }
VM.sysWriteln("LOS.alloc 100");
      spaceLock.release();  //release lock: won't keep change to large_last_alloc'd

      // Couldn't find space; inform allocator (which will either trigger GC or 
      // throw out of memory exception)
      // VM_Allocator.heapExhausted(this, size, count++);
      VM._assert(false); // XXXXX
    }
  }


  /**
   * Hook to allow heap to perform post-allocation processing of the object.
   * For example, setting the GC state bits in the object header.
   */
  protected void postAlloc(Object newObj) throws VM_PragmaUninterruptible { 
    if (VM_Interface.NEEDS_WRITE_BARRIER) {
      VM_ObjectModel.initializeAvailableByte(newObj); 
      Header.setBarrierBit(newObj);
    } 
  }


  boolean isLive (VM_Address ref) throws VM_PragmaUninterruptible {
      VM_Address addr = VM_ObjectModel.getPointerInMemoryRegion(ref);
      if (VM.VerifyAssertions) VM._assert(start.LE(addr) && addr.LE(end));
      int page_num = addr.diff(start).toInt() >> 12;
      return (largeSpaceMark[page_num] != 0);
  }

  VM_Address traceObject (VM_Address ref) throws VM_PragmaUninterruptible {

    VM_Address tref = VM_ObjectModel.getPointerInMemoryRegion(ref);
    // if (VM.VerifyAssertions) VM._assert(addrInHeap(tref));

    int ij;
    int page_num = tref.diff(start).toInt() >>> 12;
    boolean result = (largeSpaceMark[page_num] != 0);
    if (result) return ref;	// fast, no synch case
    
    spaceLock.lock();		// get sysLock for large objects
    result = (largeSpaceMark[page_num] != 0);
    if (result) {	// need to recheck
      spaceLock.release();
      return ref;
    }
    int temp = largeSpaceAlloc[page_num];
    if (temp == 1) 
      largeSpaceMark[page_num] = 1;
    else {
      // mark entries for both ends of the range of allocated pages
      if (temp > 0) {
	ij = page_num + temp -1;
	largeSpaceMark[ij] = (short)-temp;
      }
      else {
	ij = page_num + temp + 1;
	largeSpaceMark[ij] = (short)-temp;
      }
      largeSpaceMark[page_num] = (short)temp;
    }

    spaceLock.unlock();	// INCLUDES sync()
    return ref;
  }



  /*
  private void countObjects () throws VM_PragmaUninterruptible {
    int i,num_pages,countLargeOld;
    int contiguousFreePages,maxContiguousFreePages;

    for (i =  0; i < GC_LARGE_SIZES; i++) countLargeAlloc[i] = 0;
    countLargeOld = contiguousFreePages = maxContiguousFreePages = 0;

    for (i =  0; i < largeSpacePages;) {
      num_pages = largeSpaceAlloc[i];
      if (num_pages == 0) {     // no large object found here
	countLargeAlloc[0]++;   // count free pages in entry[0]
	contiguousFreePages++;
	i++;
      }
      else {    // at beginning of a large object
	if (num_pages < GC_LARGE_SIZES-1) countLargeAlloc[num_pages]++;
	else countLargeAlloc[GC_LARGE_SIZES - 1]++;
	if ( contiguousFreePages > maxContiguousFreePages )
	  maxContiguousFreePages = contiguousFreePages;
	contiguousFreePages = 0;
	i = i + num_pages;       // skip to next object or free page
      }
    }
    if ( contiguousFreePages > maxContiguousFreePages )
      maxContiguousFreePages = contiguousFreePages;

    VM.sysWrite("Large Objects Allocated - by num pages\n");
    for (i = 0; i < GC_LARGE_SIZES-1; i++) {
      VM.sysWrite("pages ");
      VM.sysWrite(i);
      VM.sysWrite(" count ");
      VM.sysWrite(countLargeAlloc[i]);
      VM.sysWrite("\n");
    }
    VM.sysWrite(countLargeAlloc[GC_LARGE_SIZES-1]);
    VM.sysWrite(" large objects ");
    VM.sysWrite(GC_LARGE_SIZES-1);
    VM.sysWrite(" pages or more.\n");
    VM.sysWrite(countLargeAlloc[0]);
    VM.sysWrite(" Large Object Space pages are free.\n");
    VM.sysWrite(maxContiguousFreePages);
    VM.sysWrite(" is largest block of contiguous free pages.\n");
    VM.sysWrite(countLargeOld);
    VM.sysWrite(" large objects are old.\n");
    
  }  // countLargeObjects()

  public int freeSpace () throws VM_PragmaUninterruptible {
    int total = 0;
    for (int i = 0 ; i < largeSpacePages;) {
      if (largeSpaceAlloc[i] == 0) {
	total++;
	i++;
      }
      else i = i + largeSpaceAlloc[i];
    }
    return (total * pageSize);       // number of bytes free in largespace
  }
  */

}
