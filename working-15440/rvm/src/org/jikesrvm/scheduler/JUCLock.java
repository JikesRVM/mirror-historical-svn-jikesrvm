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

import java.util.concurrent.locks.AbstractQueuedSynchronizer;

public class JUCLock extends SloppyDeflateLockBase {
  /** The lock is held, but the queue is empty. */
  protected static final int LOCKED    = 1;
  
  /**
   * This flag, OR'd into the state, indicates that the wait queue may not be
   * modified.
   */
  protected static final int QUEUEING   = 2;
  
  /** there is stuff on the waiting queue */
  protected static final int WAITING    = 4;
  
  /** the lock is destroyed */
  protected static final int DESTROYED  = 8;
  
  static class Sync extends AbstractQueuedSynchronizer {
    Sync() {}
    
    protected boolean isHeldExclusively() {
      return (getState()|LOCKED)!=0;
    }

    @Inline
    protected boolean tryAcquire(int acquires) {
      int oldState = getState();
      
      if ((oldState&DESTROYED)!=0) {
        if (false) VM.tsysWriteln("acquiring destroyed lock ",Magic.objectAsAddress(this));
        return true; /* simulate successful acquisition to get OUR acquisition
                        method to fail, instead of having the thread spin. */
      }
      
      if ((oldState&LOCKED)==0) {
        if (false) VM.tsysWriteln("locking with oldState = ",oldState);
        boolean result=compareAndSetState(oldState, oldState|LOCKED);
        if (false) VM.tsysWriteln("we now have state = ",getState());
        return result;
      }
      
      return false;
    }
    
    @Inline
    protected boolean tryRelease(int releases) {
      for (int cnt=0;;++cnt) {
        int oldState = getState();
        
        if ((oldState&DESTROYED)!=0) {
          if (false) VM.tsysWriteln("releasing destroyed lock ",Magic.objectAsAddress(this));
          // release 
          return true;
        }
        
        if (VM.VerifyAssertions) VM._assert((oldState&LOCKED)==LOCKED);
        
        if (false) VM.tsysWriteln("releasing with oldState = ",oldState);
        
        if (compareAndSetState(oldState, oldState&~LOCKED)) {
          if (false) VM.tsysWriteln("released with state = ",getState());
          return true;
        }
        
        Spinning.interruptibly(cnt,0);
      }
    }
    
    // expose some stuff
    
    @Inline
    boolean compareAndSetState_(int old, int changed) {
      return compareAndSetState(old, changed);
    }
    
    @Inline
    int getState_() {
      return getState();
    }
  }
  
  Sync s = new Sync();
  
  public JUCLock() {
    if (VM.VerifyAssertions) VM._assert(s!=null);
    if (false) VM.tsysWriteln("created lock ",Magic.objectAsAddress(s));
  }
  
  @NoNullCheck
  @Inline
  protected final boolean acquireImpl() {
    s.acquire(1);
    Magic.isync();
    return (s.getState_()&DESTROYED)==0;
  }
  
  @NoNullCheck
  @Inline
  protected final void releaseImpl() {
    Magic.sync();
    s.release(1);
  }
  
  private void lockStateImpl(boolean allowDestroyed) {
    for (int cnt=0;;++cnt) {
      int oldState=s.getState_();
      
      // FIXME: this isn't necessarily a smart assertion.  there are places where
      // this assertion would fail even though the code is not incorrect.
      // like where?  we know of removeFromWaitQueue.  others?
      if (VM.VerifyAssertions) VM._assert(allowDestroyed || (oldState&DESTROYED)==0);
      
      if ((oldState&QUEUEING)==0 &&
          s.compareAndSetState_(oldState, oldState|QUEUEING)) {
        break;
      }
      Spinning.interruptibly(cnt,0);
    }
    Magic.isync();
  }
  
  private void unlockStateImpl(boolean allowDestroyed) {
    Magic.sync();
    for (int cnt=0;;++cnt) {
      int oldState=s.getState_();
      if (VM.VerifyAssertions) VM._assert(allowDestroyed || (oldState&DESTROYED)==0); // ditto.
      if (VM.VerifyAssertions) VM._assert((oldState&QUEUEING)==QUEUEING);
      int newState=oldState;
      newState&=~QUEUEING;
      if (waitingIsEmpty()) {
        newState&=~WAITING;
      } else {
        newState|=WAITING;
      }
      if (s.compareAndSetState_(oldState, newState)) {
        break;
      }
      Spinning.interruptibly(cnt,0);
    }
  }
  
  protected final void lockState() { lockStateImpl(false); }
  protected final void unlockState() { unlockStateImpl(false); }
  protected final void lockStateInactive() { lockStateImpl(true); }
  protected final void unlockStateInactive() { unlockStateImpl(true); }
  
  protected final boolean stateIsLocked() {
    return (s.getState_()&QUEUEING)!=0;
  }
  
  protected final boolean canDeflate() {
    return s.getState_()==0 && !s.hasQueuedThreads();
  }
  
  protected final boolean assertDeflate() {
    if (!s.hasQueuedThreads() && s.compareAndSetState_(0, DESTROYED)) {
      
      if (false) VM.tsysWriteln("deflating lock ",Magic.objectAsAddress(s));

      // state is destroyed.  that means that Sync:
      //
      // - can enqueue new threads, but immediately after enqueueing them
      //   it'll return, believing that it succeeded in acquiring the lock.
      // - will not park new threads, because it will believe that the
      //   lock is not held.
      //
      // thus, we can safely assume that the queue of threads can only
      // shrink or stay constant.
      
      while (s.hasQueuedThreads()) {
        s.release(1);
      }
      
      return true;
    }
    
    return false;
  }
  
  @Uninterruptible
  protected final void dumpBlockedThreads() {
    VM.sysWriteln("I got nothin'");
  }
  
  protected int enqueueWaitingAndUnlockCompletely(RVMThread toWait) {
    if (VM.VerifyAssertions) VM._assert((s.getState_()&LOCKED)==LOCKED);
    if (VM.VerifyAssertions) VM._assert((s.getState_()&DESTROYED)==0);
    return super.enqueueWaitingAndUnlockCompletely(toWait);
  }
}


