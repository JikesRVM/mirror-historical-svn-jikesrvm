/*
 * (C) Copyright IBM Corp. 2002
 */
//$Id$

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Memory;
import com.ibm.JikesRVM.VM_Entrypoints;
import com.ibm.JikesRVM.VM_Synchronization;

public class Lock {

  private int lock;
  private static int lockFieldOffset = VM_Entrypoints.lockField.getOffset();
  private static int UNLOCKED = 0;
  private static int LOCKED = 1;

  Lock() { 
    lock = UNLOCKED; 
  }

  // Will spin-wait
  //
  void acquire() {
    while (!VM_Synchronization.tryCompareAndSwap(this, lockFieldOffset, UNLOCKED, LOCKED))
      ;
  }


  void release() {
    if (VM.VerifyAssertions) VM._assert(lock == LOCKED);
    lock = UNLOCKED;
  }

}
