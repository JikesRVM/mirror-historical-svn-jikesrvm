/*
 * (C) Copyright IBM Corp. 2001
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Uninterruptible;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;

/**
 * This class restricts MonotoneVMResource by preventing release of immortal memory.
 * There is functionality for boot-image support.
 *
 * 
 * @author Perry Cheng
 * @version $Revision$
 * @date $Date$
 */
public class ImmortalMonotoneVMResource extends MonotoneVMResource implements Constants {
  public final static String Id = "$Id$"; 

  ////////////////////////////////////////////////////////////////////////////
  //
  // Public instance methods
  //
  /**
   * Constructor
   */
  ImmortalMonotoneVMResource(String vmName, VM_Address vmStart, EXTENT bytes, VM_Address cursorStart, byte status) {
    super(vmName, vmStart, bytes, status);
    cursor = cursorStart;
    if (VM.VerifyAssertions) VM._assert(cursor.GE(vmStart) && cursor.LE(sentinel));
    sentinel = start.add(bytes);
  }


  public final void release() {
    if (VM.VerifyAssertions) VM._assert(false);
  }
  
}
