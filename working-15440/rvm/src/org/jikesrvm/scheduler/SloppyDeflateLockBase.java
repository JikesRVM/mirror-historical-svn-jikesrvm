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
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.NoNullCheck;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

public abstract class SloppyDeflateLockBase extends CommonLock {
  // WARNING: there is some code duplication with MMTk Lock
  
  protected int numHeavyUses;
  
  public SloppyDeflateLockBase() {
  }
  
  protected final void init() {
    numHeavyUses=1;
    Magic.sync();
  }
  
  protected final void destroy() {
    if (VM.VerifyAssertions) {
      VM._assert(waitingIsEmpty());
      VM._assert(ownerId==0);
      VM._assert(recursionCount==0);
    }

    lockedObject=null;
  }
  
  protected int spinLimit() {
    return VM.sloppySpinLimit;
  }
  
  /**
   * Implementation of lock acquisition.  Does not deal with locked objects or with
   * recursion.  Also does not do any Magic synchronization.
   */
  @NoNullCheck
  @Inline
  protected abstract boolean acquireImpl();
  
  /**
   * Implementation of lock release.  Does not deal with locked objects or with
   * recursion.  Also does not do any Magic synchronization.
   */
  @NoNullCheck
  @Inline
  protected abstract void releaseImpl();
  
  protected final void setUnlockedState() {
    if (false) VM.tsysWriteln("inflating unlocked: ",id);
    ownerId=0;
    recursionCount=0;
  }
  
  protected final void setLockedState(int ownerId,int recursionCount) {
    if (false) VM.tsysWriteln("inflating locked: ",id);
    boolean result=acquireImpl();
    if (VM.VerifyAssertions) VM._assert(result==true);
    // at this point only the *owner* of the lock can mess with it.
    this.ownerId=ownerId;
    this.recursionCount=recursionCount;
    if (false) VM.tsysWriteln("done inflating locked: ",id);
  }
  
  @NoNullCheck
  public final boolean lockHeavy(Object o) {
    if (false) VM.tsysWriteln("locking heavy: ",id);
    int myId=RVMThread.getCurrentThread().getLockingId();
    if (myId == ownerId) {
      if (VM.VerifyAssertions) {
        Magic.isync();
        
        // If we own the lock, then there is no way that it could have been
        // deflated.
        VM._assert(lockedObject == o);
      }
      recursionCount++;
      return true;
    } else {
      // problem: what if we get here but the lock has since been deflated,
      // reinflated, and then we get here during reinflation?
      if (((LockConfig.Selected)Magic.eatCast(this)).acquireImpl()) {
        Magic.isync();
        if (lockedObject == o) {
          ownerId = myId;
          recursionCount=1;
          return true;
        } else {
          // oops!  acquired the lock of the wrong object!
          if (false) VM.tsysWriteln("acquired the lock of the wrong object!");
          Magic.sync();
          ((LockConfig.Selected)Magic.eatCast(this)).releaseImpl();
        }
      } else {
        if (false) VM.tsysWriteln("acquire failed.");
      }
    }
    return false;
  }
  
  @NoNullCheck
  public final void unlockHeavy() {
    if (recursionCount==0) {
      RVMThread.raiseIllegalMonitorStateException("unlocking unlocked lock",lockedObject);
    }
    if (--recursionCount==0) {
      if (false) VM.tsysWriteln("locking heavy completely: ",id);
      ownerId = 0;
      Magic.sync();
      ((LockConfig.Selected)Magic.eatCast(this)).releaseImpl();
      if (VM.sloppyEagerDeflate) deflateIfPossible();
    } else {
      if (false) VM.tsysWriteln("locking heavy recursively: ",id);
    }
  }
  
  protected int enqueueWaitingAndUnlockCompletely(RVMThread toWait) {
    numHeavyUses++;
    return super.enqueueWaitingAndUnlockCompletely(toWait);
  }
  
  protected abstract boolean canDeflate();
  
  protected abstract boolean assertDeflate();
  
  @Inline
  protected final boolean deflateIfPossible() {
    boolean result=false;
    if (((LockConfig.Selected)Magic.eatCast(this)).assertDeflate()) {
      // at this point the lock becomes unusable, anyone trying to use it will spin.
      if (trace) VM.tsysWriteln("decided to deflate a lock: ",Magic.objectAsAddress(this));
      LockConfig.selectedThinPlan.markDeflated(lockedObject, id);
      destroy();
      SloppyDeflateLockPlanBase.instance.free(this);
      if (VM.monitorSloppyInflations) SloppyDeflateLockPlanBase.deflations++;
      result=true;
    }
    return result;
  }
  
  @Inline
  protected final boolean pollDeflate(int useThreshold) {
    boolean result=false;
    if (lockedObject!=null) {
      if (useThreshold<0 || numHeavyUses<useThreshold) {
        result=deflateIfPossible();
      }
    }
    if (!result) {
      numHeavyUses=0;
    }
    return result;
  }
}


