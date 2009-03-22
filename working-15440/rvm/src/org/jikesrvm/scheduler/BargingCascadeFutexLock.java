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
import org.jikesrvm.Services;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.objectmodel.ThinLockConstants;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.runtime.Entrypoints;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.NoNullCheck;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

public class BargingCascadeFutexLock extends SloppyDeflateLockBase {
  
  /** The lock is held, but the queue is empty. */
  protected static final int LOCKED    = 1;
  
  /** There are threads waiting on this lock. */
  protected static final int QUEUED    = 2;
  
  /**
   * This flag, OR'd into the state, indicates that the wait queue is locked.
   */
  protected static final int QUEUEING   = 4;
  
  /** there is stuff on the waiting queue */
  protected static final int WAITING    = 8;
  
  /** the lock is destroyed */
  protected static final int DESTROYED  = 16;
  
  Futex futex = new Futex(0);
  
  @NoNullCheck
  @Inline
  protected final boolean acquireImpl() {
    int lockedBits=LOCKED;
    for (int cnt=0;;++cnt) {
      int old = futex.load();
      if ((old&(DESTROYED|LOCKED))==0 &&
          futex.cas(old,old|lockedBits)) {
        return true;
      } else if ((old&DESTROYED)!=0) {
        return false;
      } else if ((old&DESTROYED|LOCKED)==LOCKED &&
                 cnt>VM.thinRetryLimit &&
                 ((old&QUEUED)!=0 ||
                  futex.cas(old, old|QUEUED))) {
        int res=futex.waitNicely(old|QUEUED);
        if (LockConfig.LATE_WAKE_FUTEX) {
          lockedBits|=QUEUED;
        } else {
          futex.wake(1);
        }
        if (res==0) continue;
      }
      Spinning.interruptibly(cnt,0);
    }
  }
  
  @NoNullCheck
  @Inline
  protected final void releaseImpl() {
    for (int cnt=0;;++cnt) {
      int old = futex.load();
      if (VM.VerifyAssertions) VM._assert((old&DESTROYED)==0);
      if (VM.VerifyAssertions) VM._assert((old&LOCKED)==LOCKED);
      if ((old&(LOCKED|QUEUED))==LOCKED &&
          futex.cas(old, old&~LOCKED)) {
        return;
      } else if ((old&(LOCKED|QUEUED))==(LOCKED|QUEUED) &&
                 futex.cas(old, old&~(LOCKED|QUEUED))) {
        futex.wake(1);
        return;
      }
      Spinning.interruptibly(cnt,0);
    }
  }
  
  protected final void lockState() {
    for (int cnt=0;;++cnt) {
      int old = futex.load();
      if ((old&QUEUEING)==0 &&
          futex.cas(old,old|QUEUEING)) {
        break;
      }
      Spinning.interruptibly(cnt, 0);
    }
    Magic.isync();
  }
  
  protected final void unlockState() {
    Magic.sync();
    for (int cnt=0;;++cnt) {
      int old = futex.load();
      if (VM.VerifyAssertions) VM._assert((old&QUEUEING)!=0);
      int changed=old;
      changed&=~QUEUEING;
      if (waitingIsEmpty()) {
        changed&=~WAITING;
      } else {
        changed|=WAITING;
      }
      if (futex.cas(old, changed)) {
        return;
      }
      Spinning.interruptibly(cnt, 0);
    }
  }
  
  protected final boolean stateIsLocked() {
    return (futex.load()&QUEUEING)!=0;
  }
  
  public final boolean canDeflate() {
    return futex.load()==0;
  }
  
  public final boolean assertDeflate() {
    return futex.cas(0, DESTROYED);
  }
  
  @Uninterruptible
  protected final void dumpBlockedThreads() {
    VM.sysWriteln("state = ",futex.load());
  }
}


