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

  /** The (fixed) number of entries in the lock table spine */
  protected static int LOCK_SPINE_SIZE = 128;
  /** The log size of each chunk in the spine */
  protected static final int LOG_LOCK_CHUNK_SIZE = 11;
  /** The size of each chunk in the spine */
  protected static final int LOCK_CHUNK_SIZE = 1 << LOG_LOCK_CHUNK_SIZE;
  /** The mask used to get the chunk-level index */
  protected static final int LOCK_CHUNK_MASK = LOCK_CHUNK_SIZE - 1;
  /** The maximum possible number of locks */
  protected static final int MAX_LOCKS = LOCK_SPINE_SIZE * LOCK_CHUNK_SIZE;
  /** The number of chunks to allocate on startup */
  protected static final int INITIAL_CHUNKS = 1;

  // Heavy lock table.

  /** The table of locks. */
  private static CommonLock[][] locks;
  /** Used during allocation of locks within the table. */
  private static final SpinLock lockAllocationMutex = new SpinLock();
  /** The number of chunks in the spine that have been physically allocated */
  private static int chunksAllocated;
  /** The number of locks allocated (these may either be in use, on a global
   * freelist, or on a thread's freelist. */
  private static int nextLockIndex;

  // Global free list.

  /** A global lock free list head */
  private static CommonLock globalFreeLock;
  /** the number of locks held on the global free list. */
  private static int globalFreeLocks;
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
  /** Number of deflations */
  protected static int deflations;
  
  protected static int fastLocks;
  protected static int slowLocks;
  
  protected static int waitOperations;
  protected static int timedWaitOperations;
  protected static int notifyOperations;
  protected static int notifyAllOperations;
  
  public void init() {
    nextLockIndex = 1;
    locks = new CommonLock[LOCK_SPINE_SIZE][];
    for (int i=0; i < INITIAL_CHUNKS; i++) {
      chunksAllocated++;
      locks[i] = new CommonLock[LOCK_CHUNK_SIZE];
    }
    if (VM.VerifyAssertions) {
      // check that each potential lock is addressable
      VM._assert(((MAX_LOCKS - 1) <=
                  ThinLockConstants.TL_LOCK_ID_MASK.rshl(ThinLockConstants.TL_LOCK_ID_SHIFT).toInt()) ||
                  ThinLockConstants.TL_LOCK_ID_MASK.EQ(Word.fromIntSignExtend(-1)));
    }
  }
  
  /**
   * Delivers up an unassigned heavy-weight lock.  Locks are allocated
   * from processor specific regions or lists, so normally no synchronization
   * is required to obtain a lock.
   *
   * Collector threads cannot use heavy-weight locks.
   *
   * @return a free Lock; or <code>null</code>, if garbage collection is not enabled
   */
  @UnpreemptibleNoWarn("The caller is prepared to lose control when it allocates a lock")
  protected CommonLock allocate() {
    RVMThread me=RVMThread.getCurrentThread();
    if (me.cachedFreeLock != null) {
      CommonLock l = (CommonLock)me.cachedFreeLock;
      me.cachedFreeLock = null;
      if (trace) {
        VM.sysWriteln("Lock.allocate: returning ",Magic.objectAsAddress(l),
                      ", a cached free lock from Thread #",me.getThreadSlot());
      }
      return l;
    }

    CommonLock l = null;
    while (l == null) {
      if (globalFreeLock != null) {
        lockAllocationMutex.lock();
        l = globalFreeLock;
        if (l != null) {
          globalFreeLock = l.nextFreeLock;
          l.nextFreeLock = null;
          l.active = true;
          globalFreeLocks--;
        }
        lockAllocationMutex.unlock();
        if (trace && l!=null) {
          VM.sysWriteln("Lock.allocate: returning ",Magic.objectAsAddress(l),
                        " from the global freelist for Thread #",me.getThreadSlot());
        }
      } else {
        l = new LockConfig.Selected(); // may cause thread switch (and processor loss)
        lockAllocationMutex.lock();
        if (globalFreeLock == null) {
          // ok, it's still correct for us to be adding a new lock
          if (nextLockIndex >= MAX_LOCKS) {
            VM.sysWriteln("Too many fat locks"); // make MAX_LOCKS bigger? we can keep going??
            VM.sysFail("Exiting VM with fatal error");
          }
          l.id = nextLockIndex++;
          globalLocksAllocated++;
        } else {
          l = null; // someone added to the freelist, try again
        }
        lockAllocationMutex.unlock();
        if (l != null) {
          if (l.id >= numLocks()) {
            /* We need to grow the table */
            growLocks(l.id);
          }
          addLock(l);
          l.active = true;
          /* make sure other processors see lock initialization.
           * Note: Derek and I BELIEVE that an isync is not required in the other processor because the lock is newly allocated - Bowen */
          Magic.sync();
        }
        if (trace && l!=null) {
          VM.sysWriteln("Lock.allocate: returning ",Magic.objectAsAddress(l),
                        ", a freshly allocated lock for Thread #",
                        me.getThreadSlot());
        }
      }
    }
    return l;
  }

  /**
   * Recycles an unused heavy-weight lock.  Locks are deallocated
   * to processor specific lists, so normally no synchronization
   * is required to obtain or release a lock.
   */
  void free(CommonLock l) {
    l.active = false;
    RVMThread me = RVMThread.getCurrentThread();
    if (me.cachedFreeLock == null) {
      if (trace) {
        VM.sysWriteln("Lock.free: setting ",Magic.objectAsAddress(l),
                      " as the cached free lock for Thread #",
                      me.getThreadSlot());
      }
      me.cachedFreeLock = l;
    } else {
      if (trace) {
        VM.sysWriteln("Lock.free: returning ",Magic.objectAsAddress(l),
                      " to the global freelist for Thread #",
                      me.getThreadSlot());
      }
      returnLock(l);
    }
  }
  public void returnLock(AbstractLock l_) {
    if (trace) {
      VM.sysWriteln("Lock.returnLock: returning ",Magic.objectAsAddress(l_),
                    " to the global freelist for Thread #",
                    RVMThread.getCurrentThreadSlot());
    }
    CommonLock l=(CommonLock)l_;
    lockAllocationMutex.lock();
    l.nextFreeLock = globalFreeLock;
    globalFreeLock = l;
    globalFreeLocks++;
    globalLocksFreed++;
    lockAllocationMutex.unlock();
  }

  /**
   * Grow the locks table by allocating a new spine chunk.
   */
  @UnpreemptibleNoWarn("The caller is prepared to lose control when it allocates a lock")
  void growLocks(int id) {
    int spineId = id >> LOG_LOCK_CHUNK_SIZE;
    if (spineId >= LOCK_SPINE_SIZE) {
      VM.sysFail("Cannot grow lock array greater than maximum possible index");
    }
    for(int i=chunksAllocated; i <= spineId; i++) {
      if (locks[i] != null) {
        /* We were beaten to it */
        continue;
      }

      /* Allocate the chunk */
      CommonLock[] newChunk = new CommonLock[LOCK_CHUNK_SIZE];

      lockAllocationMutex.lock();
      if (locks[i] == null) {
        /* We got here first */
        locks[i] = newChunk;
        chunksAllocated++;
      }
      lockAllocationMutex.unlock();
    }
  }

  /**
   * Return the number of lock slots that have been allocated. This provides
   * the range of valid lock ids.
   */
  public int numLocks() {
    return chunksAllocated * LOCK_CHUNK_SIZE;
  }

  /**
   * Read a lock from the lock table by id.
   *
   * @param id The lock id
   * @return The lock object.
   */
  @Inline
  public CommonLock getLock(int id) {
    return locks[id >> LOG_LOCK_CHUNK_SIZE][id & LOCK_CHUNK_MASK];
  }

  /**
   * Add a lock to the lock table
   *
   * @param l The lock object
   */
  @Uninterruptible
  private void addLock(CommonLock l) {
    CommonLock[] chunk = locks[l.id >> LOG_LOCK_CHUNK_SIZE];
    int index = l.id & LOCK_CHUNK_MASK;
    Services.setArrayUninterruptible(chunk, index, l);
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
    VM.sysWriteInt(globalFreeLocks);
    VM.sysWrite(" free locks\n");
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

  public abstract CommonLock getHeavyLock(Object o, Offset lockOffset, boolean create);
  public CommonLock getHeavyLock(Object o, boolean create) {
    return getHeavyLock(o, Magic.getObjectType(o).getThinLockOffset(), create);
  }
  
  protected void relock(Object o,int recCount) {
    lock(o);
    if (recCount!=1) {
      getHeavyLock(o,true).setRecursionCount(recCount);
    }
  }
  
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
      CommonLock l = getHeavyLock(o, true);
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
  @Interruptible
  public void notify(Object o) {
    if (STATS)
      notifyOperations++;
    CommonLock l=getHeavyLock(o, false);
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
    CommonLock l = getHeavyLock(o, false);
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

  /****************************************************************************
   * Statistics
   */

  @Interruptible
  public void boot() {
    if (STATS) {
      Callbacks.addExitMonitor(new CommonLockPlan.ExitMonitor());
      Callbacks.addAppRunStartMonitor(new CommonLockPlan.AppRunStartMonitor());
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


