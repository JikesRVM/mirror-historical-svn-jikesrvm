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
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.NonMoving;

@NonMoving
public class Futex {
  private volatile int futex;
  
  public Futex(int initial) {
    futex=initial;
    if (VM.VerifyAssertions) VM._assert(Entrypoints.futexFutexField.getOffset()==Offset.fromIntSignExtend(-4));
  }
  
  @Inline
  @Uninterruptible
  public void store(int value) {
    futex=value;
  }
  
  @Inline
  @Uninterruptible
  public int load() {
    return futex;
  }
  
  @Inline
  @Uninterruptible
  public boolean cas(int expected, int changed) {
    return Synchronization.tryCompareAndSwap(
      this, Offset.fromIntSignExtend(-4), expected, changed);
  }
  
  @Inline
  @Uninterruptible
  public int casLoad(int expected, int changed) {
    for (;;) {
      int result=Magic.prepareInt(this, Offset.fromIntSignExtend(-4));
      if (result!=expected) {
        return result;
      }
      if (Magic.attemptInt(this, Offset.fromIntSignExtend(-4), expected, changed)) {
        return expected;
      }
    }
  }
  
  @Unpreemptible
  public int waitNicely(int expected) {
    return FutexUtils.waitNicely(Magic.objectAsAddress(this).minus(4), expected);
  }
  
  @Unpreemptible
  public int wake(int num) {
    return FutexUtils.wake(Magic.objectAsAddress(this).minus(4), num);
  }
}

