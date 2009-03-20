/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.scheduler;

import org.jikesrvm.VM;
import static org.jikesrvm.runtime.SysCall.sysCall;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.NoOptCompile;
import org.vmmagic.pragma.BaselineSaveLSRegisters;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.Uninterruptible;

public class FutexUtils {
  private FutexUtils() {}
  
  @NoInline
  @NoOptCompile
  @BaselineSaveLSRegisters
  @Unpreemptible
  public static int waitNicely(Address futexAddr, int expected) {
    RVMThread.saveThreadState();
    RVMThread.enterNative();
    RVMThread.getCurrentThread().waitingOnFutex=futexAddr;
    int result=sysCall.sysFutexWait(futexAddr, expected);
    RVMThread.getCurrentThread().waitingOnFutex=Address.zero();
    RVMThread.leaveNative();
    return result;
  }
  
  @Unpreemptible
  public static int waitNicely(Object o, Offset offset, int expected) {
    return waitNicely(Magic.objectAsAddress(o).plus(offset), expected);
  }
  
  @Unpreemptible
  public static int waitNicely(Object o, Offset offset, Word expected) {
    // FIXME: what to do for 64-bit?
    if (VM.VerifyAssertions) VM._assert(VM.BuildFor32Addr);
    return waitNicely(o, offset, expected.toInt());
  }
  
  @Uninterruptible
  public static int wake(Address futexAddr, int num) {
    return sysCall.sysFutexWake(futexAddr, num);
  }
  
  @Uninterruptible
  public static int wake(Object o, Offset offset, int num) {
    return wake(Magic.objectAsAddress(o).plus(offset), num);
  }
}

