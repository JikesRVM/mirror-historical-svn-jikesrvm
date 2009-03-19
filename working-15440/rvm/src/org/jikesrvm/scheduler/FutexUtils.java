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

import static org.jikesrvm.runtime.SysCall.sysCall;
import org.vmmagic.unboxed.Address;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.NoOptCompile;
import org.vmmagic.pragma.BaselineSaveLSRegisters;
import org.vmmagic.pragma.Unpreemptible;

public class FutexUtils {
  private FutexUtils() {}
  
  @NoInline
  @NoOptCompile
  @BaselineSaveLSRegisters
  @Unpreemptible
  public static int waitNicely(Address futexAddr, int expected) {
    RVMThread.saveThreadState();
    RVMThread.enterNative();
    int result=sysCall.sysFutexWait(futexAddr, expected);
    RVMThread.leaveNative();
    return result;
  }
  
  @Unpreemptible
  public static int wake(Address futexAddr, int num) {
    return sysCall.sysFutexWake(futexAddr, num);
  }
}

