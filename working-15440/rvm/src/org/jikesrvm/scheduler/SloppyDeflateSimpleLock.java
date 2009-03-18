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

public class SloppyDeflateSimpleLock extends CommonLock {
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
  
  public SloppyDeflateSimpleLock() {
    queue = new ThreadQueue();
  }
  
  /**
   * Implementation of lock acquisition.  Does not deal with locked objects or with
   * recursion.  Also does not do any Magic synchronization.
   */
  @NoNullCheck
  @Inline
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
      Spinning.interruptibly(i,0);
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
      Spinning.interruptibly(i,0);
    }
  }
  
  /**
   * Implementation of lock release.  Does not deal with locked objects or with
   * recursion.  Also does not do any Magic synchronization.
   */
  @NoNullCheck
  @Inline
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
  
  @Uninterruptible
  protected final void dumpBlockedThreads() {
    VM.sysWrite(" entering: ");
    queue.dump();
  }
}


