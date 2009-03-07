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

public class EagerDeflateLock extends CommonLock {
  @Entrypoint
  int mutex;

  private ThreadQueue entering;
  
  public EagerDeflateLock() {
  }
  
  protected final ThreadQueue entering() {
    if (entering==null) entering=new ThreadQueue();
    return entering;
  }
  
  protected final boolean enteringIsEmpty() {
    return entering==null || entering.isEmpty();
  }
  
  public final void activate() {}

  /**
   * Acquires this heavy-weight lock on the indicated object.
   *
   * @param o the object to be locked
   * @return true, if the lock succeeds; false, otherwise
   */
  @NoNullCheck
  public final boolean lockHeavy(Object o) {
    if (CommonLockPlan.PROFILE) RVMThread.enterLockingPath();
    if (EagerDeflateLockPlan.tentativeMicrolocking) {
      if (!Synchronization.tryAcquireLock(
            this,Entrypoints.eagerDeflateLockMutexField.getOffset())) {
        if (CommonLockPlan.PROFILE) RVMThread.leaveLockingPath();
        return false;
      }
    } else {
      lockState();  // Note: thread switching is not allowed while mutex is held.
    }
    boolean result=lockHeavyLocked(o);
    if (CommonLockPlan.PROFILE) RVMThread.leaveLockingPath();
    return result;
  }
  
  /** Complete the task of acquiring the heavy lock, assuming that the mutex
      is already acquired (locked). */
  @NoNullCheck
  @Inline
  protected final boolean lockHeavyLocked(Object o) {
    if (CommonLockPlan.PROFILE) RVMThread.enterLockingPath();
    if (lockedObject != o) { // lock disappeared before we got here
      unlockState();
      if (CommonLockPlan.PROFILE) RVMThread.leaveLockingPath();
      return false;
    }
    if (CommonLockPlan.HEAVY_STATS) CommonLockPlan.lockOperations++;
    RVMThread me = RVMThread.getCurrentThread();
    int threadId = me.getLockingId();
    if (ownerId == threadId) {
      recursionCount++;
    } else if (ownerId == 0) {
      ownerId = threadId;
      recursionCount = 1;
    } else {
      entering().enqueue(me);
      unlockState();
      me.monitor().lock();
      while (entering().isQueued(me)) {
        me.monitor().waitNicely(); // this may spuriously return
      }
      me.monitor().unlock();
      if (CommonLockPlan.PROFILE) RVMThread.leaveLockingPath();
      return false;
    }
    unlockState(); // thread-switching benign
    if (CommonLockPlan.PROFILE) RVMThread.leaveLockingPath();
    return true;
  }

  /**
   * Releases this heavy-weight lock on the indicated object.
   *
   * @param o the object to be unlocked
   */
  @NoNullCheck
  public final void unlockHeavy() {
    if (CommonLockPlan.PROFILE) RVMThread.enterLockingPath();
    boolean deflated = false;
    lockState(); // Note: thread switching is not allowed while mutex is held.
    RVMThread me = RVMThread.getCurrentThread();
    if (ownerId != me.getLockingId()) {
      unlockState(); // thread-switching benign
      RVMThread.raiseIllegalMonitorStateException("heavy unlocking", lockedObject);
    }
    recursionCount--;
    if (0 < recursionCount) {
      unlockState(); // thread-switching benign
      if (CommonLockPlan.PROFILE) RVMThread.leaveLockingPath();
      return;
    }
    if (CommonLockPlan.HEAVY_STATS) CommonLockPlan.unlockOperations++;
    ownerId = 0;
    RVMThread toAwaken = entering().dequeue();
    if (toAwaken == null && entering().isEmpty() && waitingIsEmpty()) { // heavy lock can be deflated
      // Possible project: decide on a heuristic to control when lock should be deflated
      Object o = lockedObject;
      Offset lockOffset = Magic.getObjectType(o).getThinLockOffset();
      if (!lockOffset.isMax()) { // deflate heavy lock
        deflate(o, lockOffset);
        deflated = true;
      }
    }
    unlockState(); // does a Magic.sync();  (thread-switching benign)
    if (toAwaken != null) {
      toAwaken.monitor().lockedBroadcast();
    }
    if (CommonLockPlan.PROFILE) RVMThread.leaveLockingPath();
  }

  /**
   * Disassociates this heavy-weight lock from the indicated object.
   * This lock is not held, nor are any threads on its queues.  Note:
   * the mutex for this lock is held when deflate is called.
   *
   * @param o the object from which this lock is to be disassociated
   */
  @NoNullCheck
  protected final void deflate(Object o, Offset lockOffset) {
    if (VM.VerifyAssertions) {
      VM._assert(lockedObject == o);
      VM._assert(recursionCount == 0);
      VM._assert(enteringIsEmpty());
      VM._assert(waitingIsEmpty());
    }
    if (CommonLockPlan.HEAVY_STATS) CommonLockPlan.deflations++;
    LockConfig.selectedThinPlan.markDeflated(o, lockOffset, id);
    lockedObject = null;
    EagerDeflateLockPlan.instance.free(this);
  }
  
  protected final void lockState() {
    Synchronization.acquireLock(this,Entrypoints.eagerDeflateLockMutexField.getOffset());
  }
  
  protected final void unlockState() {
    Synchronization.releaseLock(this,Entrypoints.eagerDeflateLockMutexField.getOffset());
  }
  
  protected final boolean stateIsLocked() {
    return mutex!=0;
  }
  
  @Uninterruptible
  protected final void dumpBlockedThreads() {
    VM.sysWrite(" entering: ");
    entering.dump();
  }
}


