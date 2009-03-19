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

public class SloppyDeflateLock extends SloppyDeflateLockBase {
  // WARNING: there is some code duplication with MMTk Lock
  
  /** The lock is held, but the queue is empty. */
  protected static final int LOCKED    = 1;
  
  /** The lock queue (our queue - not the waiting queue) has threads on it. */
  protected static final int QUEUED    = 2;
  
  /**
   * This flag, OR'd into the state, indicates that the lock's entire state is
   * blocked.  This prevents enqueueing or dequeueing on any of the queues, or
   * acquiring the lock; in other words, no state transitions may occur when
   * this bit is set.
   */
  protected static final int QUEUEING   = 4;
  
  /** there is stuff on the waiting queue */
  protected static final int WAITING    = 8;
  
  /** the lock is destroyed */
  protected static final int DESTROYED  = 16;
  
  protected ThreadQueue queue;

  protected int[] state=new int[1];
  
  public SloppyDeflateLock() {
    queue = new ThreadQueue();
  }
  
  /**
   * Implementation of lock acquisition.  Does not deal with locked objects or with
   * recursion.  Also does not do any Magic synchronization.
   */
  @NoNullCheck
  @Inline
  protected final boolean acquireImpl() {
    int n=spinLimit();
    for (int i=0;i<n;++i) {
      int oldState=Magic.prepareInt(state,Offset.zero());
      if ((oldState&(DESTROYED|LOCKED|QUEUEING))==0 &&
          Magic.attemptInt(state,Offset.zero(),oldState,oldState|LOCKED)) {
        return true; // acquired
      } else if ((oldState&DESTROYED)!=0) {
        return false;
      }
      Spinning.interruptibly(i,0);
    }
    for (int i=n;;i++) {
      int oldState=Magic.prepareInt(state,Offset.zero());
      if ((oldState&DESTROYED)!=0) {
        return false;
      } else if ((oldState&(LOCKED|QUEUEING))==0 &&
                 Magic.attemptInt(state,Offset.zero(),oldState,oldState|LOCKED)) {
        return true;
      } else if ((oldState&(LOCKED|QUEUEING))==LOCKED &&
                 Magic.attemptInt(state,Offset.zero(),oldState,oldState|QUEUEING)) {
        if (VM.VerifyAssertions) VM._assert((state[0]&QUEUEING)!=0);
        if (VM.VerifyAssertions) VM._assert((state[0]&LOCKED)!=0);
        if (VM.VerifyAssertions) VM._assert((state[0]&DESTROYED)==0);
        numHeavyUses++;
        RVMThread me=RVMThread.getCurrentThread();
        queue.enqueue(me);
        Magic.sync();
        state[0]=(oldState|QUEUED);
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
    for (int cnt=0;;++cnt) {
      int oldState=Magic.prepareInt(state,Offset.zero());
      if (VM.VerifyAssertions) {
        if ((oldState&(DESTROYED|LOCKED))!=LOCKED) {
          VM.sysWriteln("observed bad state in releaseImpl: ",oldState,"  for ",Magic.objectAsAddress(this));
          VM._assert(VM.NOT_REACHED);
        }
      }
      if ((oldState&(LOCKED|QUEUED|QUEUEING))==LOCKED &&
          Magic.attemptInt(state,Offset.zero(),oldState,oldState&~LOCKED)) {
        return;
      } else if ((oldState&(LOCKED|QUEUED|QUEUEING))==(LOCKED|QUEUED) &&
                 Magic.attemptInt(state,Offset.zero(),
                                  oldState,
                                  oldState|QUEUEING)) {
        RVMThread toAwaken=queue.dequeue();
        if (VM.VerifyAssertions) VM._assert(toAwaken!=null);
        boolean queueEmpty=queue.isEmpty();
        Magic.sync();
        if (queueEmpty) {
          state[0]=(oldState&~(QUEUED|LOCKED));
        } else {
          state[0]=(oldState&~LOCKED);
        }
        toAwaken.monitor().lockedBroadcast();
        return;
      }
      Spinning.interruptibly(cnt,0);
    }
  }
  
  protected final void lockState() {
    for (int cnt=0;;++cnt) {
      int oldState=Magic.prepareInt(state,Offset.zero());
      if ((oldState&QUEUEING)==0 &&
          Magic.attemptInt(state,Offset.zero(),
                           oldState,
                           oldState|QUEUEING)) {
        break;
      }
      Spinning.interruptibly(cnt,0);
    }
    Magic.isync();
  }
  
  protected final void unlockState() {
    Magic.sync();
    // don't need CAS since the state field cannot change while the QUEUEING_FLAG
    // is set.
    int oldState=state[0];
    int newState=oldState;
    if (VM.VerifyAssertions) VM._assert((newState&QUEUEING)!=0);
    newState&=~QUEUEING;
    if (waitingIsEmpty()) {
      newState&=~WAITING;
    } else {
      newState|=WAITING;
    }
    if (VM.VerifyAssertions) {
      if ((newState&~(QUEUEING|WAITING))!=(oldState&~(QUEUEING|WAITING))) {
        VM.sysWriteln("oldState = ",oldState,", newState = ",newState);
        VM._assert(VM.NOT_REACHED);
      }
      int newOldState=state[0];
      if ((newState&~(QUEUEING|WAITING))!=(newOldState&~(QUEUEING|WAITING))) {
        VM.sysWriteln("newOldState = ",newOldState,", newState = ",newState);
        VM._assert(VM.NOT_REACHED);
      }
    }
    state[0]=newState;
  }
  
  protected final boolean stateIsLocked() {
    return (state[0]&QUEUEING)!=0;
  }
  
  /**
   * Can this lock be deflated right now?  Only call this after calling lockWaiting(),
   * and if you do deflate it, make sure you do so prior to calling unlockWaiting().
   */
  protected final boolean canDeflate() {
    return state[0]==0;
  }
  
  protected final boolean assertDeflate() {
    return Synchronization.tryCompareAndSwap(
      state,Offset.zero(),0,DESTROYED);
  }
  
  @Uninterruptible
  protected final void dumpBlockedThreads() {
    VM.sysWriteln(" state: ",state[0]);
    VM.sysWrite(" entering: ");
    queue.dump();
  }
}


