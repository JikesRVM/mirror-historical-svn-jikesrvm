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
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

public class SloppyDeflateLock extends CommonLock {
  // WARNING: there is some code duplication with MMTk Lock
  
  /** The lock is not held by anyone and the queue is empty. */
  protected static final int CLEAR           = 0;
  
  /** The lock is held, but the queue is empty. */
  protected static final int LOCKED          = 1;
  
  /** The lock is not held but the queue still has stuff in it. */
  protected static final int CLEAR_QUEUED    = 2;
  
  /** The lock is held and the queue has someone in it. */
  protected static final int LOCKED_QUEUED   = 3;
  
  /**
   * This flag, OR'd into the state, indicates that the lock's entire state is
   * blocked.  This prevents enqueueing or dequeueing on any of the queues, or
   * acquiring the lock; in other words, no state transitions may occur when
   * this bit is set.
   */
  protected static final int QUEUEING_FLAG   = 4;
  
  protected ThreadQueue queue;

  protected int[] state=new int[1];
  
  protected int numHeavyUses;
  
  public SloppyDeflateLock() {
    queue = new ThreadQueue();
  }
  
  protected final void init() {
    numHeavyUses=1;
    Magic.sync();
  }
  
  protected final void destroy() {
    if (VM.VerifyAssertions) {
      VM._assert(waitingIsEmpty());
      VM._assert(queue.isEmpty());
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
  protected final void acquireImpl() {
    int n=spinLimit();
    for (int i=0;i<n;++i) {
      int oldState=Magic.prepareInt(state,Offset.zero());
      if ((oldState==CLEAR &&
           Magic.attemptInt(state,Offset.zero(),CLEAR,LOCKED)) ||
          (oldState==CLEAR_QUEUED &&
           Magic.attemptInt(state,Offset.zero(),CLEAR_QUEUED,LOCKED_QUEUED))) {
        return; // acquired
      }
      Spinning.plan.interruptibleSpin(i,0);
    }
    for (int i=n;;i++) {
      int oldState=Magic.prepareInt(state,Offset.zero());
      if ((oldState==CLEAR &&
           Magic.attemptInt(state,Offset.zero(),CLEAR,LOCKED)) ||
          (oldState==CLEAR_QUEUED &&
           Magic.attemptInt(state,Offset.zero(),CLEAR_QUEUED,LOCKED_QUEUED))) {
        return;
      } else if ((oldState==LOCKED || oldState==LOCKED_QUEUED) &&
                 Magic.attemptInt(state,Offset.zero(),oldState,oldState|QUEUEING_FLAG)) {
        numHeavyUses++;
        RVMThread me=RVMThread.getCurrentThread();
        queue.enqueue(me);
        Magic.sync();
        state[0]=LOCKED_QUEUED;
        me.monitor().lock();
        while (queue.isQueued(me)) {
          me.monitor().waitNicely();
        }
        me.monitor().unlock();
      }
      Spinning.plan.interruptibleSpin(i,0);
    }
  }
  
  /**
   * Implementation of lock release.  Does not deal with locked objects or with
   * recursion.  Also does not do any Magic synchronization.
   */
  protected final void releaseImpl() {
    for (;;) {
      int oldState=Magic.prepareInt(state,Offset.zero());
      if (VM.VerifyAssertions) VM._assert((oldState&~QUEUEING_FLAG)==LOCKED ||
                                          (oldState&~QUEUEING_FLAG)==LOCKED_QUEUED);
      if (oldState==LOCKED &&
          Magic.attemptInt(state,Offset.zero(),LOCKED,CLEAR)) {
        return;
      } else if (oldState==LOCKED_QUEUED &&
                 Magic.attemptInt(state,Offset.zero(),
                                  LOCKED_QUEUED,
                                  LOCKED_QUEUED|QUEUEING_FLAG)) {
        RVMThread toAwaken=queue.dequeue();
        if (VM.VerifyAssertions) VM._assert(toAwaken!=null);
        boolean queueEmpty=queue.isEmpty();
        Magic.sync();
        if (queueEmpty) {
          state[0]=CLEAR;
        } else {
          state[0]=CLEAR_QUEUED;
        }
        toAwaken.monitor().lockedBroadcast();
        return;
      }
      Magic.pause();
    }
  }
  
  protected final void setUnlockedState() {
    if (false) VM.tsysWriteln("inflating unlocked: ",id);
    ownerId=0;
    recursionCount=0;
  }
  
  protected final void setLockedState(int ownerId,int recursionCount) {
    if (false) VM.tsysWriteln("inflating locked: ",id);
    acquireImpl();
    // at this point only the *owner* of the lock can mess with it.
    this.ownerId=ownerId;
    this.recursionCount=recursionCount;
    if (false) VM.tsysWriteln("done inflating locked: ",id);
  }
  
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
      acquireImpl();
      Magic.isync();
      if (lockedObject == o) {
        ownerId = myId;
        recursionCount=1;
        return true;
      } else {
        // oops!  acquired the lock of the wrong object!
        if (false) VM.tsysWriteln("acquired the lock of the wrong object!");
        Magic.sync();
        releaseImpl();
        return false;
      }
    }
  }
  
  public final void unlockHeavy() {
    if (recursionCount==0) {
      RVMThread.raiseIllegalMonitorStateException("unlocking unlocked lock",lockedObject);
    }
    if (--recursionCount==0) {
      if (false) VM.tsysWriteln("locking heavy completely: ",id);
      ownerId = 0;
      Magic.sync();
      releaseImpl();
    } else {
      if (false) VM.tsysWriteln("locking heavy recursively: ",id);
    }
  }
  
  @NoInline
  void rescueBadLock() {
    releaseImpl();
  }
  
  final boolean inlineLock(Object o, Word myId) {
    if (Synchronization.tryCompareAndSwap(
          state,Offset.zero(),CLEAR,LOCKED)) {
      Magic.isync();
      if (lockedObject==o) {
        ownerId = myId.toInt();
        recursionCount = 1;
        return true;
      } else {
        rescueBadLock();
        return false;
      }
    }
    return false;
  }
  
  @NoInline
  void rescueBadUnlock() {
    releaseImpl();
  }
  
  @Inline
  final boolean inlineUnlock() {
    if (recursionCount==1) {
      recursionCount = 0;
      ownerId = 0;
      Magic.sync();
      if (!Synchronization.tryCompareAndSwap(
            state, Offset.zero(), LOCKED, CLEAR)) {
        rescueBadUnlock();
      }
      return true;
    }
    return false;
  }
  
  protected final int enqueueWaitingAndUnlockCompletely(RVMThread toWait) {
    numHeavyUses++;
    return super.enqueueWaitingAndUnlockCompletely(toWait);
  }
  
  protected final void lockState() {
    for (;;) {
      int oldState=Magic.prepareInt(state,Offset.zero());
      if ((oldState&QUEUEING_FLAG)==0 &&
          Magic.attemptInt(state,Offset.zero(),
                           oldState,
                           oldState|QUEUEING_FLAG)) {
        break;
      }
      Magic.pause();
    }
    Magic.isync();
  }
  
  protected final void unlockState() {
    Magic.sync();
    // don't need CAS since the state field cannot change while the QUEUEING_FLAG
    // is set.
    state[0]=(state[0]&~QUEUEING_FLAG);
  }
  
  protected final boolean stateIsLocked() {
    return (state[0]&QUEUEING_FLAG)!=0;
  }
  
  /**
   * Can this lock be deflated right now?  Only call this after calling lockWaiting(),
   * and if you do deflate it, make sure you do so prior to calling unlockWaiting().
   */
  private final boolean canDeflate() {
    return (state[0]&~QUEUEING_FLAG) == CLEAR && waitingIsEmpty();
  }
  
  protected final boolean pollDeflate(int useThreshold) {
    boolean result=false;
    if (lockedObject!=null) {
      Offset lockOffset=Magic.getObjectType(lockedObject).getThinLockOffset();
      if (numHeavyUses<useThreshold || useThreshold<0) {
        lockState();
        if ((numHeavyUses<useThreshold || useThreshold<0) &&
            canDeflate()) {
          if (trace) VM.tsysWriteln("decided to deflate a lock.");
          LockConfig.selectedThinPlan.markDeflated(lockedObject, lockOffset, id);
          destroy();
          SloppyDeflateLockPlan.instance.free(this);
          result=true;
        } else {
          numHeavyUses=0;
        }
        unlockState();
      } else {
        numHeavyUses=0;
      }
    }
    return result;
  }
  
  @Uninterruptible
  protected final void dumpBlockedThreads() {
    VM.sysWrite(" entering: ");
    queue.dump();
  }
}


