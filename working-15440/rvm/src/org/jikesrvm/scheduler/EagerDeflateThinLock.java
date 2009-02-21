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
public class EagerDeflateThinLock extends CommonThinLock {
  final SpinLock mutex;
  final ThreadQueue entering;
  
  public EagerDeflateThinLock() {
    mutex=new SpinLock();
    entering=new ThreadQueue();
  }

  /**
   * Acquires this heavy-weight lock on the indicated object.
   *
   * @param o the object to be locked
   * @return true, if the lock succeeds; false, otherwise
   */
  @Unpreemptible
  public boolean lockHeavy(Object o) {
    RVMThread.enterLockingPath();
    if (EagerDeflateThinLockPlan.tentativeMicrolocking) {
      if (!mutex.tryLock()) {
        RVMThread.leaveLockingPath();
        return false;
      }
    } else {
      mutex.lock();  // Note: thread switching is not allowed while mutex is held.
    }
    boolean result=lockHeavyLocked(o);
    RVMThread.leaveLockingPath();
    return result;
  }
  
  /** Complete the task of acquiring the heavy lock, assuming that the mutex
      is already acquired (locked). */
  @Unpreemptible
  protected boolean lockHeavyLocked(Object o) {
    RVMThread.enterLockingPath();
    if (lockedObject != o) { // lock disappeared before we got here
      mutex.unlock(); // thread switching benign
      RVMThread.leaveLockingPath();
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
      entering.enqueue(me);
      mutex.unlock();
      me.monitor().lock();
      while (entering.isQueued(me)) {
        me.monitor().waitNicely(); // this may spuriously return
      }
      me.monitor().unlock();
      RVMThread.leaveLockingPath();
      return false;
    }
    mutex.unlock(); // thread-switching benign
    RVMThread.leaveLockingPath();
    return true;
  }

  /**
   * Releases this heavy-weight lock on the indicated object.
   *
   * @param o the object to be unlocked
   */
  public void unlockHeavy() {
    RVMThread.enterLockingPath();
    boolean deflated = false;
    mutex.lock(); // Note: thread switching is not allowed while mutex is held.
    RVMThread me = RVMThread.getCurrentThread();
    if (ownerId != me.getLockingId()) {
      mutex.unlock(); // thread-switching benign
      RVMThread.raiseIllegalMonitorStateException("heavy unlocking", lockedObject);
    }
    recursionCount--;
    if (0 < recursionCount) {
      mutex.unlock(); // thread-switching benign
      RVMThread.leaveLockingPath();
      return;
    }
    if (CommonLockPlan.HEAVY_STATS) CommonLockPlan.unlockOperations++;
    ownerId = 0;
    RVMThread toAwaken = entering.dequeue();
    if (toAwaken == null && entering.isEmpty() && waiting.isEmpty()) { // heavy lock can be deflated
      // Possible project: decide on a heuristic to control when lock should be deflated
      Object o = lockedObject;
      Offset lockOffset = Magic.getObjectType(o).getThinLockOffset();
      if (!lockOffset.isMax()) { // deflate heavy lock
        deflate(o, lockOffset);
        deflated = true;
      }
    }
    mutex.unlock(); // does a Magic.sync();  (thread-switching benign)
    if (toAwaken != null) {
      toAwaken.monitor().lockedBroadcast();
    }
    RVMThread.leaveLockingPath();
  }

  /**
   * Disassociates this heavy-weight lock from the indicated object.
   * This lock is not held, nor are any threads on its queues.  Note:
   * the mutex for this lock is held when deflate is called.
   *
   * @param o the object from which this lock is to be disassociated
   */
  protected void deflate(Object o, Offset lockOffset) {
    if (VM.VerifyAssertions) {
      VM._assert(lockedObject == o);
      VM._assert(recursionCount == 0);
      VM._assert(entering.isEmpty());
      VM._assert(waiting.isEmpty());
    }
    if (CommonLockPlan.HEAVY_STATS) CommonLockPlan.deflations++;
    EagerDeflateThinLockPlan.instance.deflate(o, lockOffset, this);
    lockedObject = null;
    EagerDeflateThinLockPlan.instance.free(this);
  }
  
  protected void lockWaiting() {
    mutex.lock();
  }
  
  protected void unlockWaiting() {
    mutex.unlock();
  }
  
  protected void dumpBlockedThreads() {
    VM.sysWrite(" entering: ");
    entering.dump();
  }
}


