/*
 * (C) Copyright Department of Computer Science,
 *     Australian National University. 2002
 * (C) Copyright IBM Corp. 2002
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Uninterruptible;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;

/**
 * This class implements a monotone virtual memory resource.  The unit of
 * managment for virtual memory resources is the <code>BLOCK</code><p>
 *
 * Instances of this class respond to requests for virtual address
 * space by monotonically consuming the resource.
 * 
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
public class MonotoneVMResource extends VMResource implements Constants {
  public final static String Id = "$Id$"; 

  ////////////////////////////////////////////////////////////////////////////
  //
  // Public instance methods
  //
  /**
   * Constructor
   */
  MonotoneVMResource(String vmName, MemoryResource mr, VM_Address vmStart, EXTENT bytes, byte status) {
    super(vmName, vmStart, bytes, (byte) (VMResource.IN_VM | status));
    cursor = start;
    sentinel = start.add(bytes);
    memoryResource = mr;
  }


 /**
   * Acquire a number of contigious blocks from the virtual memory resource.
   *
   * @param request The number of blocks requested
   * @return The address of the start of the virtual memory region, or
   * zero on failure.
   */
  public final VM_Address acquire(int blockRequest) {
    lock.acquire();
    memoryResource.acquire(blockRequest);
    int bytes = Conversions.blocksToBytes(blockRequest);
    VM_Address tmpCursor = cursor.add(bytes);
    if (tmpCursor.GE(sentinel)) {
      VM.sysWrite("MonotoneVMResrouce failed to acquire ", bytes);
      VM.sysWrite(" bytes: cursor = ", cursor);
      VM.sysWrite("  start = ", start);
      VM.sysWriteln("  sentinel = ", sentinel);
      lock.release();
      VM.sysFail("MonotoneVMResource.acquire failed");
      return VM_Address.zero();
    } else {
      VM_Address oldCursor = cursor;
      cursor = tmpCursor;
      LazyMmapper.ensureMapped(oldCursor, blockRequest);
      Memory.zero(oldCursor, bytes);
      lock.release();
      return oldCursor;
    }
  }

  public void release() {
    // Unmapping is useful for being a "good citizen" and for debugging
    LazyMmapper.protect(start, Conversions.bytesToBlocks(cursor.diff(start).toInt()));
    cursor = start;
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Private fields and methods
  //

  protected VM_Address cursor;
  protected VM_Address sentinel;
  protected MemoryResource memoryResource;
  protected Lock lock = new Lock();

}
