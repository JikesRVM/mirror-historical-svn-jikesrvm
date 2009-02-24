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
import org.jikesrvm.Callbacks;
import org.jikesrvm.Constants;
import org.jikesrvm.Services;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.objectmodel.ThinLockConstants;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UnpreemptibleNoWarn;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.NoNullCheck;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.Offset;

/**
 * The typically not implementation-specific common to all locks.  Most
 * locks inheric from this.
 */
public abstract class CommonLock extends AbstractLock {
  protected static final boolean trace = CommonLockPlan.trace;
  
  protected Object lockedObject;
  protected int ownerId;
  protected int recursionCount;
  protected int id;
  private ThreadQueue waiting;
  
  protected CommonLock() {
  }
  
  protected final ThreadQueue waiting() {
    if (waiting==null) waiting=new ThreadQueue();
    return waiting;
  }
  
  protected final boolean waitingIsEmpty() {
    return waiting==null || waiting.isEmpty();
  }
  
  protected final RVMThread waitingDequeue() {
    if (waiting==null) {
      return null;
    }
    return waiting.dequeue();
  }
  
  /**
   * Lock the lock's waiting state.  Used by all subclasses of CommonLock prior
   * to changing the waiting queue.  May be overloaded by some subclasses to mean,
   * more strongly, that the lock's entire state is locked.  This is a
   * non-recursive lock, and is typically implemented using spinning.
   */
  protected abstract void lockState();
  
  /**
   * Unlock the lock's waiting state.  Used by all subclasses of CommonLock
   * after changing the waiting queue.
   */
  protected abstract void unlockState();
  
  protected int enqueueWaitingAndUnlockCompletely(RVMThread toWait) {
    lockState();
    waiting().enqueue(toWait);
    unlockState();
    return unlockHeavyCompletely();
  }
  
  protected final boolean isWaiting(RVMThread t) {
    ThreadQueue w=this.waiting;
    return w!=null && w.isQueued(t);
  }
  
  protected final void removeFromWaitQueue(RVMThread wasWaiting) {
    if (isWaiting(wasWaiting)) {
      lockState();
      waiting().remove(wasWaiting);
      unlockState();
    }
  }
  
  protected final int unlockHeavyCompletely() {
    int result=getRecursionCount();
    setRecursionCount(1);
    unlockHeavy();
    return result;
  }
  
  public final void setOwnerId(int id) {
    ownerId=id;
  }
  
  @Unpreemptible
  public final int getOwnerId() {
    return ownerId;
  }
  
  public final void setRecursionCount(int c) {
    recursionCount=c;
  }
  
  public final int getRecursionCount() {
    return recursionCount;
  }
  
  public final void setLockedObject(Object o) {
    lockedObject=o;
  }
  
  public final Object getLockedObject() {
    return lockedObject;
  }
  
  @UnpreemptibleNoWarn
  public final boolean holdsLock(Object o, RVMThread thread) {
    lockState();
    boolean result = (lockedObject == o && thread.getLockingId()==getOwnerId());
    unlockState();
    return result;
  }
  
  public final int getLockId() {
    return id;
  }
  
  public final boolean isActive() {
    return lockedObject!=null;
  }
  
  protected void activate() {
  }

  protected final void dumpWaitingThreads() {
    VM.sysWrite(" waiting: ");
    waiting().dump();
  }
}

