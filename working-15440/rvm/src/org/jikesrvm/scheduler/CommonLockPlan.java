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
import org.jikesrvm.Callbacks;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.objectmodel.ThinLockConstants;
import org.jikesrvm.objectmodel.JavaHeader;
import org.jikesrvm.runtime.Magic;
import static org.jikesrvm.runtime.SysCall.sysCall;
import org.jikesrvm.runtime.RuntimeEntrypoints;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.UnpreemptibleNoWarn;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

@Uninterruptible
public abstract class CommonLockPlan extends AbstractLockPlan {
  public static CommonLockPlan instance;
  
  public CommonLockPlan() {
    instance=this;
  }
  
  /** do debug tracing? */
  protected static final boolean trace = false;
  
  protected static int INIT_LOCKS_LENGTH = 1;
  
  protected static int MAX_LOCKS = 262144; // any more doesn't make sense given the number of bits in the thin lock word

  protected static int LOCKS_LENGTH_MULT = 2;
  protected static int LOCKS_LENGTH_DIV = 1;
  
  protected static int nextLockSize(int cur) {
    int result=cur*LOCKS_LENGTH_MULT/LOCKS_LENGTH_DIV;
    if (VM.VerifyAssertions) VM._assert(result>cur);
    return result;
  }

  protected CommonLock[] locks;
  
  // ugh.  making this public so that Entrypoints can see it.
  public static FreeID {
    final int id;
    final FreeID next;
    
    FreeID(int id,
           FreeID next) {
      this.id=id;
      this.next=next;
    }
  }
  
  @Entrypoint
  protected FreeID freeHead;
  
  @Entrypoint
  protected int nextLockID;
  
  /** the total number of allocation operations. */
  private static int globalLocksAllocated;
  /** the total number of free operations. */
  private static int globalLocksFreed;

  public static final boolean STATS = false;

  // Statistics

  /** Number of lock operations */
  protected static int lockOperations;
  /** Number of unlock operations */
  protected static int unlockOperations;
  /** Number of inflations */
  protected static int inflations;
  /** Number of deflations */
  protected static int deflations;
  
  protected static int fastLocks;
  protected static int slowLocks;
  
  protected static int waitOperations;
  protected static int timedWaitOperations;
  protected static int notifyOperations;
  protected static int notifyAllOperations;

  @Interruptible
  public void init() {
    if (VM.VerifyAssertions) {
      // check that each potential lock is addressable
      VM._assert(((MAX_LOCKS - 1) <=
                  ThinLockConstants.TL_LOCK_ID_MASK.rshl(ThinLockConstants.TL_LOCK_ID_SHIFT).toInt()) ||
                  ThinLockConstants.TL_LOCK_ID_MASK.EQ(Word.fromIntSignExtend(-1)));
    }
  }
  
  @Interruptible
  public void boot() {
    locks = new CommonLock[INIT_LOCKS_LENGTH];
    if (STATS) {
      Callbacks.addExitMonitor(new CommonLockPlan.ExitMonitor());
      Callbacks.addAppRunStartMonitor(new CommonLockPlan.AppRunStartMonitor());
    }
  }
  
  @Interruptible
  public void lateBoot() {
    // nothing to do...
  }
  
  @UnpreemptibleNoWarn
  private void growLocksIfNeeded() {
    VM.sysWriteln("stopping all threads to grow locks");
    RVMThread.handshakeLock.lockNicely();
    try {
      if (freeHead==null && nextLockID==locks.length) {
        RVMThread.hardHandshakeSuspend(RVMThread.allButGC);
        VM.sysWriteln("all threads stopped");
        
        try {
          if (freeHead==null && nextLockID==locks.length) {
            CommonLock[] newLocks=new CommonLock[nextLockSize(locks.length)];
            System.arraycopy(locks,0,
                             newLocks,0,
                             locks.length);
            locks=newLocks;
          }
        } finally {
          VM.sysWriteln("resuming all threads");
          RVMThread.hardHandshakeResume(RVMThread.allButGC);
        }
      }
    } finally {
      RVMThread.handshakeLock.unlock();
      VM.sysWriteln("all threads resumed");
    }
  }

  protected CommonLock allocate() {
    CommonLock result=new LockConfig.Selected();
    
    RVMThread me=RVMThread.getCurrentThread();
    
    if (VM.VerifyAssertions) {
      // threads use cachedFreeLock==-2 to indicate that no new inflations should
      // be allowed.
      VM._assert(me.cachedFreeLock>=-1);
      
      // inflating locks from GC would be awkward
      VM._assert(!me.isGCThread());
    }
    
    if (me.cachedFreeLockID!=-1) {
      result.id=me.cachedFreeLockID;
      me.cachedFreeLockID=-1;
    } else {
      
      // this loop breaks only once it assigns an id to result.id
      for (;;) {
        FreeID fid=freeHead;
        if (fid!=null) {
          if (Synchronization.tryCompareAndSwap(
                this, Entrypoints.commonLockFreeHeadField.getOffset(),
                fid, fid.next)) {
            result.id = fid.id;
            break;
          }
        } else {
          int myID=nextLockID;
          if (myID>=MAX_LOCKS) {
            VM.sysWriteln("Too many fat locks");
            VM.sysFail("Exiting VM with fatal error");
          }
          if (myID<locks.length) {
            if (Synchronization.tryCompareAndSwap(
                  this, Entrypoints.commonLockNextLockIDField.getOffset(),
                  myID, myID+1)) {
              result.id = myID;
              break;
            }
          } else {
            growLocksIfNeeded();
          }
        }
        // we get here after growing locks, or more likely, if one of the CASes
        // failed.
        Magic.pause();
      }
    }
    
    return result;
  }
  
  @Unpreemptible
  protected void addLock(CommonLock l) {
    locks[l.id]=l;
  }
  
  @Unpreemptible
  protected CommonLock allocateActivateAndAdd() {
    CommonLock l=allocate();
    l.activate();
    addLock(l);
    return l;
  }
  
  @UnpreemptibleNoWarn
  protected void free(CommonLock l) {
    l.active=false;
    locks[l.id]=null;
    RVMThread me=RVMThread.getCurrentThread();
    if (me.cachedFreeLock==-1) {
      me.cachedFreeLock=l.id;
    } else {
      returnLockID(l.id);
    }
  }
  
  @UnpreemptibleNoWarn
  protected void returnLockID(int id) {
    for (;;) {
      FreeID fid=new FreeID(l.id,freeHead);
      if (Synchronization.tryCompareAndSwap(
            this, Entrypoints.commonLockFreeHeadField.getOffset(),
            fid.next, fid)) {
        break;
      }
    }
  }
  
  /**
   * Return the number of lock slots that have been allocated. This provides
   * the range of valid lock ids.
   */
  public int numLocks() {
    return nextLockID;
  }

  /**
   * Read a lock from the lock table by id.
   *
   * @param id The lock id
   * @return The lock object.
   */
  @Inline
  public CommonLock getLock(int id) {
    return locks[id];
  }

  /**
   * Dump the lock table.
   */
  public void dumpLocks() {
    for (int i = 0; i < numLocks(); i++) {
      CommonLock l = getLock(i);
      if (l != null) {
        l.dump();
      }
    }
    VM.sysWrite("\n");
    VM.sysWrite("lock availability stats: ");
    VM.sysWriteInt(globalLocksAllocated);
    VM.sysWrite(" locks allocated, ");
    VM.sysWriteInt(globalLocksFreed);
    VM.sysWrite(" locks freed, ");
  }

  /**
   * Count number of locks held by thread
   * @param id the thread locking ID we're counting for
   * @return number of locks held
   */
  public int countLocksHeldByThread(int id) {
    int count=0;
    for (int i = 0; i < numLocks(); i++) {
      CommonLock l = getLock(i);
      if (l != null && l.active && l.ownerId == id && l.recursionCount > 0) {
        count++;
      }
    }
    return count;
  }

  @Unpreemptible
  public abstract AbstractLock getHeavyLock(Object o, Offset lockOffset, boolean create);
  @Unpreemptible
  public AbstractLock getHeavyLock(Object o, boolean create) {
    return getHeavyLock(o, Magic.getObjectType(o).getThinLockOffset(), create);
  }
  
  @Unpreemptible
  protected void relock(Object o,int recCount) {
    lock(o);
    if (recCount!=1) {
      ((CommonLock)getHeavyLock(o,true)).setRecursionCount(recCount);
    }
  }
  
  @Interruptible
  public void waitImpl(Object o, boolean hasTimeout, long whenWakeupNanos) {
    if (STATS) {
      if (hasTimeout) {
        timedWaitOperations++;
      } else {
        waitOperations++;
      }
    }
    RVMThread t=RVMThread.getCurrentThread();
    boolean throwInterrupt = false;
    Throwable throwThis = null;
    if (t.asyncThrowable != null) {
      throwThis = t.asyncThrowable;
      t.asyncThrowable = null;
    } else if (!holdsLock(o, t)) {
      throw new IllegalMonitorStateException("waiting on " + o);
    } else if (t.hasInterrupt) {
      throwInterrupt = true;
      t.hasInterrupt = false;
    } else {
      t.waiting = hasTimeout ? RVMThread.Waiting.TIMED_WAITING : RVMThread.Waiting.WAITING;
      // get lock for object
      CommonLock l = (CommonLock)getHeavyLock(o, true);
      // this thread is supposed to own the lock on o
      if (VM.VerifyAssertions)
        VM._assert(l.getOwnerId() == t.getLockingId());

      // release the lock and enqueue waiting
      int waitCount=l.enqueueWaitingAndUnlockCompletely(t);

      // block
      t.monitor().lock();
      while (l.isWaiting(t) && !t.hasInterrupt && t.asyncThrowable == null &&
             (!hasTimeout || sysCall.sysNanoTime() < whenWakeupNanos)) {
        if (hasTimeout) {
          t.monitor().timedWaitAbsoluteNicely(whenWakeupNanos);
        } else {
          t.monitor().waitNicely();
        }
      }
      // figure out if anything special happened while we were blocked
      if (t.hasInterrupt) {
        throwInterrupt = true;
        t.hasInterrupt = false;
      }
      if (t.asyncThrowable != null) {
        throwThis = t.asyncThrowable;
        t.asyncThrowable = null;
      }
      t.monitor().unlock();
      l.removeFromWaitQueue(t);
      relock(o, waitCount);
      t.waiting = RVMThread.Waiting.RUNNABLE;
    }
    // check if we should exit in a special way
    if (throwThis != null) {
      RuntimeEntrypoints.athrow(throwThis);
    }
    if (throwInterrupt) {
      RuntimeEntrypoints.athrow(new InterruptedException("sleep interrupted"));
    }
  }
  
  /**
   * Support for Java {@link java.lang.Object#notify()} synchronization
   * primitive.
   *
   * @param o the object synchronized on
   */
  @UninterruptibleNoWarn("Never blocks except if there was an error")
  public void notify(Object o) {
    if (STATS)
      notifyOperations++;
    CommonLock l=(CommonLock)getHeavyLock(o, false);
    if (l == null)
      return;
    if (l.getOwnerId() != RVMThread.getCurrentThread().getLockingId()) {
      RVMThread.raiseIllegalMonitorStateException("notifying", o);
    }
    l.lockWaiting();
    RVMThread toAwaken = l.waiting.dequeue();
    l.unlockWaiting();
    if (toAwaken != null) {
      toAwaken.monitor().lockedBroadcast();
    }
  }

  /**
   * Support for Java synchronization primitive.
   *
   * @param o the object synchronized on
   * @see java.lang.Object#notifyAll
   */
  @UninterruptibleNoWarn("Never blocks except if there was an error")
  public void notifyAll(Object o) {
    if (STATS)
      notifyAllOperations++;
    CommonLock l = (CommonLock)getHeavyLock(o, false);
    if (l == null)
      return;
    if (l.getOwnerId() != RVMThread.getCurrentThread().getLockingId()) {
      RVMThread.raiseIllegalMonitorStateException("notifyAll", o);
    }
    for (;;) {
      l.lockWaiting();
      RVMThread toAwaken = l.waiting.dequeue();
      l.unlockWaiting();
      if (toAwaken == null)
        break;
      toAwaken.monitor().lockedBroadcast();
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
    int totalLocks = lockOperations + fastLocks + slowLocks;
    
    VM.sysWrite("FatLocks: ");
    VM.sysWrite(waitOperations);
    VM.sysWrite(" wait operations\n");
    VM.sysWrite("FatLocks: ");
    VM.sysWrite(timedWaitOperations);
    VM.sysWrite(" timed wait operations\n");
    VM.sysWrite("FatLocks: ");
    VM.sysWrite(notifyOperations);
    VM.sysWrite(" notify operations\n");
    VM.sysWrite("FatLocks: ");
    VM.sysWrite(notifyAllOperations);
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
    VM.sysWrite("FatLocks: ");
    VM.sysWrite(inflations);
    VM.sysWrite(" inflations\n");
    
    VM.sysWrite("ThinLocks: ");
    VM.sysWrite(fastLocks);
    VM.sysWrite(" fast locks");
    Services.percentage(fastLocks, totalLocks, "all lock operations");
    VM.sysWrite("ThinLocks: ");
    VM.sysWrite(slowLocks);
    VM.sysWrite(" slow locks");
    Services.percentage(slowLocks, totalLocks, "all lock operations");
    VM.sysWriteln();
    
    VM.sysWrite("lock availability stats: ");
    VM.sysWriteInt(globalLocksAllocated);
    VM.sysWrite(" locks allocated, ");
    VM.sysWriteInt(globalLocksFreed);
    VM.sysWrite(" locks freed, ");
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


