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
import org.jikesrvm.runtime.Entrypoints;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.UnpreemptibleNoWarn;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.NoNullCheck;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

public abstract class CommonLockPlan extends AbstractLockPlan {
  public static CommonLockPlan instance;
  
  public CommonLockPlan() {
    instance=this;
  }
  
  /** do debug tracing? */
  protected static final boolean trace = false;
  
  protected static int INIT_LOCKS_LENGTH = 2048;
  
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
  public static class FreeID {
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
  protected int nextLockID=1;

  @Entrypoint
  private int locksAllocated;
  /** the total number of allocation operations. */
  @Entrypoint
  private int globalLocksAllocated;
  /** the total number of free operations. */
  @Entrypoint
  private int globalLocksFreed;

  public static final boolean HEAVY_STATS = false;
  public static final boolean STATS = HEAVY_STATS || false;
  
  public static final boolean PROFILE = false;

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

  public void init() {
    if (VM.VerifyAssertions) {
      // check that each potential lock is addressable
      VM._assert(((MAX_LOCKS - 1) <=
                  ThinLockConstants.TL_LOCK_ID_MASK.rshl(ThinLockConstants.TL_LOCK_ID_SHIFT).toInt()) ||
                  ThinLockConstants.TL_LOCK_ID_MASK.EQ(Word.fromIntSignExtend(-1)));
    }
  }
  
  public void boot() {
    locks = new CommonLock[INIT_LOCKS_LENGTH];
    if (STATS) {
      Callbacks.addExitMonitor(new CommonLockPlan.ExitMonitor());
      Callbacks.addAppRunStartMonitor(new CommonLockPlan.AppRunStartMonitor());
    }
  }
  
  public void lateBoot() {
    // nothing to do...
  }
  
  private void growLocksIfNeeded() {
    if (true || trace) VM.sysWriteln("stopping all threads to grow locks");
    int oldLocksLength=locks.length;
    if (freeHead==null && nextLockID==oldLocksLength) {
      CommonLock[] newLocks=new CommonLock[nextLockSize(oldLocksLength)];
      RVMThread.hardHandshakeSuspend(RVMThread.handshakeBlockAdapter,RVMThread.allButGC);
      if (trace) VM.sysWriteln("all threads stopped");
      
      try {
        if (freeHead==null && newLocks.length>locks.length) {
          System.arraycopy(locks,0,
                           newLocks,0,
                           locks.length);
          locks=newLocks; // what if there is a barrier here?  shouldn't be a problem...
        }
      } finally {
        if (trace) VM.sysWriteln("resuming all threads");
        RVMThread.hardHandshakeResume(RVMThread.handshakeBlockAdapter,RVMThread.allButGC);
      }
    }
  }
  
  @Inline
  @NoNullCheck
  protected final CommonLock allocate() {
    if (PROFILE) RVMThread.enterLockingPath();
    
    RVMThread me=RVMThread.getCurrentThread();
    
    if (VM.VerifyAssertions) {
      VM._assert(!me.noMoreLocking);
      
      // inflating locks from GC would be awkward
      VM._assert(!me.isGCThread());
    }
    
    CommonLock result;
    
    if (STATS) Synchronization.fetchAndAdd(
      this,Entrypoints.commonLockLocksAllocatedField.getOffset(),1);

    if (LockConfig.CACHE_LOCKS && me.cachedFreeLock!=null) {
      result=me.cachedFreeLock;
      me.cachedFreeLock=null;
    } else {
      result=new LockConfig.Selected();

      if (!LockConfig.CACHE_LOCKS && me.cachedFreeLockID!=-1) {
        result.id=me.cachedFreeLockID;
        me.cachedFreeLockID=-1;
      } else {
        allocateSlow(result);
      }
    }

    if (VM.VerifyAssertions) VM._assert(result.id>0);
    if (PROFILE) RVMThread.leaveLockingPath();
    
    return result;
  }

  @NoInline
  private final CommonLock allocateSlow(CommonLock result) {
    RVMThread me=RVMThread.getCurrentThread();
    
    if (STATS) Synchronization.fetchAndAdd(
      this,Entrypoints.commonLockGlobalLocksAllocatedField.getOffset(),1);
    
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
    
    return result;
  }
  
  @Unpreemptible
  @NoNullCheck
  protected final void addLock(CommonLock l) {
    locks[l.id]=l;
  }
  
  protected final CommonLock allocateActivateAndAdd() {
    LockConfig.Selected l=(LockConfig.Selected)Magic.eatCast(allocate());
    l.init();
    addLock(l);
    return l;
  }
  
  @Inline
  protected final void free(CommonLock l) {
    if (PROFILE) RVMThread.enterLockingPath();
    RVMThread me=RVMThread.getCurrentThread();
    l.lockedObject=null;
    locks[l.id]=null;
    if (LockConfig.CACHE_LOCKS && me.cachedFreeLock==null) {
      me.cachedFreeLock=l;
    } else if (!LockConfig.CACHE_LOCKS && me.cachedFreeLockID==-1) {
      me.cachedFreeLockID=l.id;
    } else {
      returnLockID(l.id);
    }
    if (PROFILE) RVMThread.leaveLockingPath();
  }
  
  protected final void returnLock(CommonLock l) {
    returnLockID(l.id);
  }
  
  @NoInline
  protected final void returnLockID(int id) {
    if (STATS) Synchronization.fetchAndAdd(
      this,Entrypoints.commonLockGlobalLocksFreedField.getOffset(),1);
    for (;;) {
      FreeID fid=new FreeID(id,freeHead);
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
  @Uninterruptible
  public final int numLocks() {
    return nextLockID;
  }

  /**
   * Read a lock from the lock table by id.
   *
   * @param id The lock id
   * @return The lock object.
   */
  @Inline
  @Uninterruptible
  @NoNullCheck
  public final AbstractLock getLock(int id) {
    return locks[id];
  }
  
  @Uninterruptible
  public final void dumpLockStats() {
    VM.sysWrite("lock availability stats: ");
    VM.sysWriteInt(locksAllocated);
    VM.sysWrite(" locks allocated, ");
    VM.sysWriteInt(globalLocksAllocated);
    VM.sysWrite(" global locks allocated, ");
    VM.sysWriteInt(globalLocksFreed);
    VM.sysWrite(" global locks freed\n");
  }

  /**
   * Dump the lock table.
   */
  @Uninterruptible
  public final void dumpLocks() {
    for (int i = 0; i < numLocks(); i++) {
      CommonLock l = (CommonLock)Magic.eatCast(getLock(i));
      if (l != null) {
        l.dump();
      }
    }
    VM.sysWriteln();
    dumpLockStats();
  }

  /**
   * Count number of locks held by thread
   * @param id the thread locking ID we're counting for
   * @return number of locks held
   */
  public final int countLocksHeldByThread(int id) {
    int count=0;
    for (int i = 0; i < numLocks(); i++) {
      CommonLock l = (CommonLock)Magic.eatCast(getLock(i));
      if (l != null && l.lockedObject!=null && l.ownerId == id && l.recursionCount > 0) {
        count++;
      }
    }
    return count;
  }

  protected final void relock(Object o,int recCount) {
    LockConfig.selectedThinPlan.lock(o);
    if (recCount!=1) {
      ((CommonLock)Magic.eatCast(inflate(o))).setRecursionCount(recCount);
    }
  }
  
  public final void waitImpl(Object o, boolean hasTimeout, long whenWakeupNanos) {
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
    } else if (!t.holdsLock(o)) {
      throw new IllegalMonitorStateException("waiting on " + o);
    } else if (t.hasInterrupt) {
      throwInterrupt = true;
      t.hasInterrupt = false;
    } else {
      t.waiting = hasTimeout ? RVMThread.Waiting.TIMED_WAITING : RVMThread.Waiting.WAITING;
      // get lock for object
      CommonLock l = (CommonLock)Magic.eatCast(inflate(o));
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
  public final void notify(Object o) {
    if (STATS)
      notifyOperations++;
    if (!RVMThread.getCurrentThread().holdsLock(o)) {
      RVMThread.raiseIllegalMonitorStateException("notifying", o);
    }
    CommonLock l=(CommonLock)Magic.eatCast(getLock(o));
    if (l == null)
      return;
    if (VM.VerifyAssertions) {
      if (l.getOwnerId() != RVMThread.getCurrentThread().getLockingId()) {
        VM.sysWriteln("fat lock owner doesn't match current thread ID");
        VM.sysWriteln("lock word = ",Magic.getWordAtOffset(o, Magic.getObjectType(o).getThinLockOffset()));
        VM.sysWriteln("lock id = ",l.id);
        VM.sysWriteln("cur thread id = ",RVMThread.getCurrentThreadSlot());
        VM.sysWriteln("lock owner = ",l.getOwnerId());
        VM.sysWriteln("lock rec cont = ",l.getRecursionCount());
        VM._assert(false);
      }
    }
    l.lockState();
    RVMThread toAwaken = l.waitingDequeue();
    l.unlockState();
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
  public final void notifyAll(Object o) {
    if (STATS)
      notifyAllOperations++;
    if (!RVMThread.getCurrentThread().holdsLock(o)) {
      RVMThread.raiseIllegalMonitorStateException("notifying", o);
    }
    CommonLock l = (CommonLock)Magic.eatCast(getLock(o));
    if (l == null)
      return;
    if (VM.VerifyAssertions)
      VM._assert(l.getOwnerId() == RVMThread.getCurrentThread().getLockingId());
    for (;;) {
      l.lockState();
      RVMThread toAwaken = l.waitingDequeue();
      l.unlockState();
      if (toAwaken == null)
        break;
      toAwaken.monitor().lockedBroadcast();
    }
  }

  protected final void initStats() {
    lockOperations = 0;
    unlockOperations = 0;
    deflations = 0;
    fastLocks = 0;
    slowLocks = 0;
  }
  
  protected final void reportStats() {
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

    dumpLockStats();
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


