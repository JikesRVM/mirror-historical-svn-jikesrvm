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
public final class MonotoneVMResource extends VMResource implements Constants {
  public final static String Id = "$Id$"; 

  ////////////////////////////////////////////////////////////////////////////
  //
  // Public instance methods
  //
  /**
   * Constructor
   */
  MonotoneVMResource(String vmName, VM_Address vmStart, EXTENT bytes, byte status) {
    super(vmName, vmStart, bytes, status);
    cursor = start;
    sentinel = start.add(bytes);
  }


 /**
   * Acquire a number of contigious blocks from the virtual memory resource.
   *
   * @param request The number of blocks requested
   * @return The address of the start of the virtual memory region, or
   * zero on failure.
   */
  public final VM_Address acquire(int request) {
    VM_Address tmpCursor = cursor.add(Conversions.blocksToBytes(request));
    
    if (tmpCursor.GE(sentinel)) {
      // FIXME Is this really how we want to deal with failure?
      return VM_Address.zero();
    } else {
      VM_Address oldCursor = cursor;
      cursor = tmpCursor;
      LazyMmapper.ensureMapped(oldCursor, request);
      return oldCursor;
    }
  }

  public final void release() {
    cursor = start;
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Private fields and methods
  //

  private VM_Address cursor;
  private VM_Address sentinel;
}
