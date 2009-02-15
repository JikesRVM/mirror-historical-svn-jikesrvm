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
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.Offset;

/**
 * The typically not implementation-specific common to all locks.  Most
 * locks inheric from this.
 */
public abstract class CommonLock extends AbstractLock {
  protected Object lockedObject;
  protected int ownerId;
  protected int recursionCount;
  protected boolean active;
  protected int id;
  protected ThreadQueue waiting;
  protected CommonLock nextFreeLock;
  
  protected CommonLock() {
    waiting=new ThreadQueue();
  }
  
  protected abstract void lockWaiting();
  protected abstract void unlockWaiting();
  
  protected abstract boolean isWaiting(RVMThread t);
  protected abstract void removeFromWaitQueue(RVMThread wasWaiting);
  
  protected int enqueueWaitingAndUnlockCompletely(RVMThread toWait) {
    lockWaiting();
    waiting.enqueue(toWait);
    unlockWaiting();
    return unlockHeavyCompletely();
  }
  
  protected boolean isWaiting(RVMThread t) {
    return waiting.isQueued(t);
  }
  
  protected void removeFromWaitQueue(RVMThread wasWaiting) {
    if (isWaiting(wasWaiting)) {
      lockWaiting();
      waiting.remove(wasWaiting);
      unlockWaiting();
    }
  }
  
  protected int unlockHeavyCompletely() {
    int result=getRecursionCount();
    setRecursionCount(1);
    unlockHeavy();
    return result;
  }
  
  public void setOwnerId(int id) {
    ownerId=id;
  }
  
  public int getOwnerId() {
    return ownerId;
  }
  
  public void setRecursionCount(int c) {
    recursionCount=c;
  }
  
  public int getRecursionCount() {
    return recursionCount;
  }
  
  public void setLockedObject(Object o) {
    lockedObject=o;
  }
  
  public Object getLockedObject() {
    return lockedObject;
  }
  
  protected void dumpWaitingThreads() {
    VM.sysWrite(" waiting: ");
    waiting.dump();
  }
}

