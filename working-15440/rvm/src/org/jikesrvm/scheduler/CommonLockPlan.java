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
public abstract class CommonLockPlan extends AbstractLockPlan {
  public static final boolean STATS = false;

  // Statistics

  /** Number of lock operations */
  protected static int lockOperations;
  /** Number of unlock operations */
  protected static int unlockOperations;
  /** Number of deflations */
  protected static int deflations;
  
  protected static int fastLocks;
  protected static int slowLocks;
  
  public void init() {
    // do nothing ... real action happens in subclasses
  }
  
  protected void relock(Object o,int recCount) {
    ObjectModel.genericLock(o);
    if (recCount!=1) {
      ObjectModel.getHeavyLock(o,true).setRecursionCount(recCount);
    }
  }
  
  // FIXME: why the fuck are we passing lockOffsets around?
  public abstract CommonLock getHeavyLock(Object o, Offset lockOffset, boolean create);
  
  public void waitImpl(Object o, boolean hasTimeout, long whenWakeupNanos) {
    RVMThread t=RVMThread.getCurrentThread();
    boolean throwInterrupt = false;
    Throwable throwThis = null;
    if (t.asyncThrowable != null) {
      throwThis = t.asyncThrowable;
      t.asyncThrowable = null;
    } else if (!holdsLock(o, this)) {
      throw new IllegalMonitorStateException("waiting on " + o);
    } else if (t.hasInterrupt) {
      throwInterrupt = true;
      t.hasInterrupt = false;
    } else {
      waiting = hasTimeout ? Waiting.TIMED_WAITING : Waiting.WAITING;
      // get lock for object
      Lock l = (CommonLock)getHeavyLock(o, true);
      // this thread is supposed to own the lock on o
      if (VM.VerifyAssertions)
        VM._assert(l.getOwnerId() == getLockingId());

      // release the lock and enqueue waiting
      int waitCount=l.enqueueWaitingAndUnlockCompletely(this);

      // block
      monitor().lock();
      while (l.isWaiting(this) && !hasInterrupt && asyncThrowable == null &&
             (!hasTimeout || sysCall.sysNanoTime() < whenWakeupNanos)) {
        if (hasTimeout) {
          monitor().timedWaitAbsoluteNicely(whenWakeupNanos);
        } else {
          monitor().waitNicely();
        }
      }
      // figure out if anything special happened while we were blocked
      if (hasInterrupt) {
        throwInterrupt = true;
        hasInterrupt = false;
      }
      if (asyncThrowable != null) {
        throwThis = asyncThrowable;
        asyncThrowable = null;
      }
      monitor().unlock();
      l.removeFromWaitQueue(this);
      // reacquire the lock, restoring the recursion count.  note that the
      // lock associated with the object may have changed (been deflated and
      // reinflated) so we must start anew
      ObjectModel.genericLock(o);
      waitObject = null;
      if (waitCount != 1) { // reset recursion count
        Lock l2 = ObjectModel.getHeavyLock(o, true);
        l2.setRecursionCount(waitCount);
      }
      waiting = Waiting.RUNNABLE;
    }
    // check if we should exit in a special way
    if (throwThis != null) {
      RuntimeEntrypoints.athrow(throwThis);
    }
    if (throwInterrupt) {
      RuntimeEntrypoints.athrow(new InterruptedException("sleep interrupted"));
    }
  }
  
  /****************************************************************************
   * Statistics
   */

  @Interruptible
  public void boot() {
    if (STATS) {
      Callbacks.addExitMonitor(new Lock.ExitMonitor());
      Callbacks.addAppRunStartMonitor(new Lock.AppRunStartMonitor());
    }
  }
  
  protected void initStats() {
    lockOperations = 0;
    unlockOperations = 0;
    deflations = 0;
    fastLocks = 0;
    slowLocks = 0;
  }
  
  protected void reportStats() {
    int totalLocks = lockOperations + ThinLock.fastLocks + ThinLock.slowLocks;
    
    RVMThread.dumpStats();
    VM.sysWrite(" notifyAll operations\n");
    VM.sysWrite("FatLocks: ");
    VM.sysWrite(lockOperations);
    VM.sysWrite(" locks");
    Services.percentage(lockOperations, totalLocks, "all lock operations");
    VM.sysWrite("FatLocks: ");
    VM.sysWrite(unlockOperations);
    VM.sysWrite(" unlock operations\n");
    VM.sysWrite("FatLocks: ");
    VM.sysWrite(deflations);
    VM.sysWrite(" deflations\n");
    
    VM.sysWrite("ThinLocks: ");
    VM.sysWrite(fastLocks);
    VM.sysWrite(" fast locks");
    Services.percentage(fastLocks, value, "all lock operations");
    VM.sysWrite("ThinLocks: ");
    VM.sysWrite(slowLocks);
    VM.sysWrite(" slow locks");
    Services.percentage(slowLocks, value, "all lock operations");
    VM.sysWriteln();
    
    // FIXME: move to subclass
    VM.sysWrite("lock availability stats: ");
    VM.sysWriteInt(globalLocksAllocated);
    VM.sysWrite(" locks allocated, ");
    VM.sysWriteInt(globalLocksFreed);
    VM.sysWrite(" locks freed, ");
    VM.sysWriteInt(globalFreeLocks);
    VM.sysWrite(" free locks\n");
  }

  /**
   * Initialize counts in preparation for gathering statistics
   */
  private static final class AppRunStartMonitor implements Callbacks.AppRunStartMonitor {
    public void notifyAppRunStart(String app, int value) {
      ((CommonLockPlan)LockConfig.selectedPlan).initStats();
    }
  }

  /**
   * Report statistics at the end of execution.
   */
  private static final class ExitMonitor implements Callbacks.ExitMonitor {
    public void notifyExit(int value) {
      ((CommonLockPlan)LockConfig.selectedPlan).reportStats();
    }
  }
}


