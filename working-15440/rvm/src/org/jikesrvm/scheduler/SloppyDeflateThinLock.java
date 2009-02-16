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
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

@Uninterruptible
public class SloppyDeflateThinLock extends CommonThinLock {
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
  @Entrypoint
  protected int state;
  
  public SloppyDeflateThinLock() {
    queue = new ThreadQueue();
  }
  
  protected int spinLimit() {
    return 1000;
  }
  
  /**
   * Implementation of lock acquisition.  Does not deal with locked objects or with
   * recursion.  Also does not do any Magic synchronization.
   */
  @Unpreemptible
  protected void acquireImpl() {
    Offset offset=Entrypoints.sloppyDeflateThinLockStateField.getOffset();
    for (int n=spinLimit();i-->0;) {
      int oldState=Magic.prepareInt(this,offset);
      if ((oldState==CLEAR &&
           Magic.attemptInt(this,offset,CLEAR,LOCKED)) ||
          (oldState==CLEAR_QUEUED &&
           Magic.attemptInt(this,offset,CLEAR_QUEUED,LOCKED_QUEUED))) {
        return; // acquired
      }
    }
    for (;;) {
      int oldState=Magic.prepareInt(this,offset);
      if ((oldState==CLEAR &&
           Magic.attemptInt(this,offset,CLEAR,LOCKED)) ||
          (oldState==CLEAR_QUEUED &&
           Magic.attemptInt(this,offset,CLEAR_QUEUED,LOCKED_QUEUED))) {
        return;
      } else if ((oldState==LOCKED || oldState==LOCKED_QUEUED) &&
                 Magic.attemptInt(this,offset,oldState,oldState|QUEUEING_FLAG)) {
        RVMThread me=RVMThread.getCurrentThread();
        queue.enqueue(me);
        Magic.sync();
        state=LOCKED_QUEUED;
        me.monitor().lock();
        while (queue.isQueued(me)) {
          me.monitor().waitNicely();
        }
        me.monitor().unlock();
      }
    }
  }
  
  /**
   * Implementation of lock release.  Does not deal with locked objects or with
   * recursion.  Also does not do any Magic synchronization.
   */
  protected void releaseImpl() {
    Offset offset=Entrypoints.sloppyDeflateThinLockStateField.getOffset();
    for (;;) {
      int oldState=Magic.prepareInt(this,offset);
      if (VM.VerifyAssertions) VM._assert((oldState&~QUEUEING_FLAG)==LOCKED ||
                                          (oldState&~QUEUEING_FLAG)==LOCKED_QUEUED);
      if (oldState==LOCKED &&
          Magic.attemptInt(this,offset,LOCKED,CLEAR)) {
        return;
      } else if (oldState==LOCKED_QUEUED &&
                 Magic.attemptInt(this,offset,
                                  LOCKED_QUEUED,
                                  LOCKED_QUEUED|QUEUEING_FLAG)) {
        RVMThread toAwaken=queue.dequeue();
        if (VM.VerifyAssertions) VM._assert(toAwaken!=null);
        boolean queueEmpty=queue.isEmpty();
        Magic.sync();
        if (queueEmpty) {
          state=CLEAR;
        } else {
          state=CLEAR_QUEUED;
        }
        toAwaken.monitor().lockedBroadcast();
        return;
      }
    }
  }
  
  @Unpreemptible
  public boolean lockHeavy(Object o) {
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
      acquireImpl();
      Magic.isync();
      if (lockedObject == o) {
        ownerId = myId;
        recursionCount=1;
        return true;
      } else {
        // oops!  acquired the lock of the wrong object!
        Magic.sync();
        releaseImpl();
        return false;
      }
    }
  }
  
  public void unlockHeavy() {
    if (--recursionCount==0) {
      ownerId = 0;
      Magic.sync();
      releaseImpl();
    }
  }
  
  protected void lockWaiting() {
    Offset offset=Entrypoints.sloppyDeflateThinLockStateField.getOffset();
    for (;;) {
      int oldState=Magic.prepareInt(this,offset);
      if ((oldState&QUEUEING_FLAG)==0 &&
          Magic.attemptInt(this,offset,
                           oldState,
                           oldState|QUEUEING_FLAG)) {
        break;
      }
    }
    Magic.isync();
  }
  
  protected void unlockWaiting() {
    Magic.sync();
    // don't need CAS since the state field cannot change while the QUEUEING_FLAG
    // is set.
    state=(state&~QUEUEING_FLAG);
  }
  
  /**
   * Can this lock be deflated right now?  Only call this after calling lockWaiting(),
   * and if you do deflate it, make sure you do so prior to calling unlockWaiting().
   */
  protected boolean canDeflate() {
    return state == CLEAR && waiting.isEmpty();
  }
}


