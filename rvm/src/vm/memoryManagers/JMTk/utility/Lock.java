/*
 * (C) Copyright IBM Corp. 2002
 */
//$Id$

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Memory;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_Entrypoints;
import com.ibm.JikesRVM.VM_Synchronization;
import com.ibm.JikesRVM.VM_Uninterruptible;

public class Lock implements VM_Uninterruptible {

  private int lock;
  private static int lockFieldOffset = VM_Entrypoints.lockField.getOffset();
  private static int UNLOCKED = 0;
  private static int LOCKED = 1;

  public Lock() { 
    lock = UNLOCKED; 
  }

  // Will spin-wait
  //
  public void acquire() {
    while (!VM_Synchronization.tryCompareAndSwap(this, lockFieldOffset, UNLOCKED, LOCKED))
      ;
  }


  public void release() {
    if (VM.VerifyAssertions) VM._assert(lock == LOCKED);
    VM_Magic.sync();
    boolean success = VM_Synchronization.tryCompareAndSwap(this, lockFieldOffset, LOCKED, UNLOCKED); // guarantees flushing
    if (VM.VerifyAssertions) VM._assert(success);
  }

}
