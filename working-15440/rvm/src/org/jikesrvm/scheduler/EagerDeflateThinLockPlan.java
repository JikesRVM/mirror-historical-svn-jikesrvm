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
public class EagerDeflateThinLockPlan extends CommonThinLockPlan {
  public static EagerDeflateThinLockPlan instance;
  
  public EagerDeflateThinLockPlan() {
    instance=this;
  }
  
  /**
   * Should we give up or persist in the attempt to get a heavy-weight lock,
   * if its <code>mutex</code> microlock is held by another procesor.
   */
  public static final boolean tentativeMicrolocking = false;

  public void init() {
    super.init();
    // nothing to do...
  }
  
  /**
   * Obtains a lock on the indicated object.  Light-weight locking
   * sequence for the prologue of synchronized methods and for the
   * <code>monitorenter</code> bytecode.
   *
   * @param o the object to be locked
   * @param lockOffset the offset of the thin lock word in the object.
   */
  @NoInline
  @Unpreemptible("Become another thread when lock is contended, don't preempt in other cases")
  public void lock(Object o, Offset lockOffset) {
    major:
    while (true) { // repeat only if attempt to lock a promoted lock fails
      int retries = retryLimit;
      Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
      while (0 != retries--) { // repeat if there is contention for thin lock
        Word old = Magic.prepareWord(o, lockOffset);
        Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
        if (id.isZero()) { // o isn't locked
          if (Magic.attemptWord(o, lockOffset, old, old.or(threadId))) {
            Magic.isync(); // don't use stale prefetched data in monitor
            if (STATS) slowLocks++;
            break major;  // lock succeeds
          }
          continue; // contention, possibly spurious, try again
        }
        if (id.EQ(threadId)) { // this thread has o locked already
          Word changed = old.toAddress().plus(TL_LOCK_COUNT_UNIT).toWord(); // update count
          if (changed.and(TL_LOCK_COUNT_MASK).isZero()) { // count wrapped around (most unlikely), make heavy lock
            while (!inflateAndLock(o, lockOffset)) { // wait for a lock to become available
              RVMThread.yield();
            }
            break major;  // lock succeeds (note that lockHeavy has issued an isync)
          }
          if (Magic.attemptWord(o, lockOffset, old, changed)) {
            Magic.isync(); // don't use stale prefetched data in monitor !!TODO: is this isync required?
            if (STATS) slowLocks++;
            break major;  // lock succeeds
          }
          continue; // contention, probably spurious, try again (TODO!! worry about this)
        }

        if (!(old.and(TL_FAT_LOCK_MASK).isZero())) { // o has a heavy lock
          int index = getLockIndex(old);
          if (getLock(index).lockHeavy(o)) {
            break major; // lock succeeds (note that lockHeavy has issued an isync)
          }
          // heavy lock failed (deflated or contention for system lock)
          RVMThread.yield();
          continue major;    // try again
        }
        // real contention: wait (hope other thread unlocks o), try again
        if (traceContention) { // for performance tuning only (see section 5)
          Address fp = Magic.getFramePointer();
          fp = Magic.getCallerFramePointer(fp);
          int mid = Magic.getCompiledMethodID(fp);
          RVMMethod m1 = CompiledMethods.getCompiledMethod(mid).getMethod();
          fp = Magic.getCallerFramePointer(fp);
          mid = Magic.getCompiledMethodID(fp);
          RVMMethod m2 = CompiledMethods.getCompiledMethod(mid).getMethod();
          String s = m1.getDeclaringClass() + "." + m1.getName() + " " + m2.getDeclaringClass() + "." + m2.getName();
          RVMThread.trace(Magic.getObjectType(o).toString(), s, -2 - retries);
        }
        if (0 != retries) {
          RVMThread.yield(); // wait, hope o gets unlocked
        }
      }
      // create a heavy lock for o and lock it
      if (inflateAndLock(o, lockOffset)) break;
    }
    // o has been locked, must return before an exception can be thrown
  }

  /**
   * Releases the lock on the indicated object.   Light-weight unlocking
   * sequence for the epilogue of synchronized methods and for the
   * <code>monitorexit</code> bytecode.
   *
   * @param o the object to be locked
   * @param lockOffset the offset of the thin lock word in the object.
   */
  @NoInline
  @Unpreemptible("No preemption normally, but may raise exceptions")
  static void unlock(Object o, Offset lockOffset) {
    Magic.sync(); // prevents stale data from being seen by next owner of the lock
    while (true) { // spurious contention detected
      Word old = Magic.prepareWord(o, lockOffset);
      Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
      Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
      if (id.NE(threadId)) { // not normal case
        if (!(old.and(TL_FAT_LOCK_MASK).isZero())) { // o has a heavy lock
          getLock(getLockIndex(old)).unlockHeavy(o);
          // note that unlockHeavy has issued a sync
          return;
        }
        RVMThread.trace("Lock", "unlock error: thin lock word = ", old.toAddress());
        RVMThread.trace("Lock", "unlock error: thin lock word = ", Magic.objectAsAddress(o));
        // RVMThread.trace("Lock", RVMThread.getCurrentThread().toString(), 0);
        RVMThread.raiseIllegalMonitorStateException("thin unlocking", o);
      }
      if (old.and(TL_LOCK_COUNT_MASK).isZero()) { // get count, 0 is the last lock
        Word changed = old.and(TL_UNLOCK_MASK);
        if (Magic.attemptWord(o, lockOffset, old, changed)) {
          return; // unlock succeeds
        }
        continue;
      }
      // more than one lock
      // decrement recursion count
      Word changed = old.toAddress().minus(TL_LOCK_COUNT_UNIT).toWord();
      if (Magic.attemptWord(o, lockOffset, old, changed)) {
        return; // unlock succeeds
      }
    }
  }

  ////////////////////////////////////////////////////////////////
  /// Support for inflating (and deflating) heavy-weight locks ///
  ////////////////////////////////////////////////////////////////

  /**
   * Promotes a light-weight lock to a heavy-weight lock.  Note: the
   * object is question will normally be locked by another thread,
   * or it may be unlocked.  If there is already a heavy-weight lock
   * on this object, that lock is returned.
   *
   * @param o the object to get a heavy-weight lock
   * @param lockOffset the offset of the thin lock word in the object.
   * @return the heavy-weight lock on this object
   */
  @Unpreemptible
  private static EagerDeflateThinLock inflate(Object o, Offset lockOffset) {
    RVMThread.enterLockingPath();
    if (VM.VerifyAssertions) {
      VM._assert(holdsLock(o, lockOffset, RVMThread.getCurrentThread()));
      // this assertions is just plain wrong.
      //VM._assert((Magic.getWordAtOffset(o, lockOffset).and(TL_FAT_LOCK_MASK).isZero()));
    }
    EagerDeflateThinLock l = (EagerDeflateThinLock)allocate();
    if (VM.VerifyAssertions) {
      VM._assert(l != null); // inflate called by wait (or notify) which shouldn't be called during GC
    }
    EagerDeflateThinLock rtn = attemptToInflate(o, lockOffset, l);
    if (rtn == l)
      l.mutex.unlock();
    RVMThread.leaveLockingPath();
    return rtn;
  }

  /**
   * Promotes a light-weight lock to a heavy-weight lock and locks it.
   * Note: the object in question will normally be locked by another
   * thread, or it may be unlocked.  If there is already a
   * heavy-weight lock on this object, that lock is returned.
   *
   * @param o the object to get a heavy-weight lock
   * @param lockOffset the offset of the thin lock word in the object.
   * @return whether the object was successfully locked
   */
  @Unpreemptible
  private static boolean inflateAndLock(Object o, Offset lockOffset) {
    EagerDeflateThinLock l = (EagerDeflateThinLock)allocate();
    if (l == null) return false; // can't allocate locks during GC
    EagerDeflateThinLock rtn = attemptToInflate(o, lockOffset, l);
    if (l != rtn) {
      l = rtn;
      l.mutex.lock();
    }
    return l.lockHeavyLocked(o);
  }

  /**
   * Promotes a light-weight lock to a heavy-weight lock.
   *
   * @param o the object to get a heavy-weight lock
   * @param lockOffset the offset of the thin lock word in the object.
   * @return whether the object was successfully locked
   */
  private static EagerDeflateThinLock attemptToInflate(Object o,
                                                       Offset lockOffset,
                                                       EagerDeflateThinLock l) {
    RVMThread.enterLockingPath();
    Word old;
    l.mutex.lock();
    do {
      old = Magic.prepareWord(o, lockOffset);
      // check to see if another thread has already created a fat lock
      if (!(old.and(TL_FAT_LOCK_MASK).isZero())) { // already a fat lock in place
        if (trace) {
          VM.sysWriteln("Thread #",RVMThread.getCurrentThreadSlot(),
                        ": freeing lock ",Magic.objectAsAddress(l),
                        " because we had a double-inflate");
        }
        free(l);
        l.mutex.unlock();
        l = getLock(getLockIndex(old));
        RVMThread.leaveLockingPath();
        return l;
      }
      Word locked = TL_FAT_LOCK_MASK.or(Word.fromIntZeroExtend(l.index).lsh(TL_LOCK_ID_SHIFT));
      Word changed = locked.or(old.and(TL_UNLOCK_MASK));
      if (VM.VerifyAssertions) VM._assert(getLockIndex(changed) == l.index);
      if (Magic.attemptWord(o, lockOffset, old, changed)) {
        l.setLockedObject(o);
        l.setOwnerId(old.and(TL_THREAD_ID_MASK).toInt());
        if (l.getOwnerId() != 0) {
          l.setRecursionCount(old.and(TL_LOCK_COUNT_MASK).rshl(TL_LOCK_COUNT_SHIFT).toInt() + 1);
        }
        RVMThread.leaveLockingPath();
        return l;
      }
      // contention detected, try again
    } while (true);
  }

  static void deflate(Object o, Offset lockOffset, EagerDeflateThinLock l) {
    if (VM.VerifyAssertions) {
      Word old = Magic.getWordAtOffset(o, lockOffset);
      VM._assert(!(old.and(TL_FAT_LOCK_MASK).isZero()));
      VM._assert(l == getLock(getLockIndex(old)));
    }
    Word old;
    do {
      old = Magic.prepareWord(o, lockOffset);
    } while (!Magic.attemptWord(o, lockOffset, old, old.and(TL_UNLOCK_MASK)));
  }

  /**
   * Obtains the heavy-weight lock, if there is one, associated with the
   * indicated object.  Returns <code>null</code>, if there is no
   * heavy-weight lock associated with the object.
   *
   * @param o the object from which a lock is desired
   * @param lockOffset the offset of the thin lock word in the object.
   * @param create if true, create heavy lock if none found
   * @return the heavy-weight lock on the object (if any)
   */
  @Unpreemptible
  static EagerDeflateThinLock getHeavyLock(Object o, Offset lockOffset, boolean create) {
    Word old = Magic.getWordAtOffset(o, lockOffset);
    if (!(old.and(TL_FAT_LOCK_MASK).isZero())) { // already a fat lock in place
      return getLock(getLockIndex(old));
    } else if (create) {
      return inflate(o, lockOffset);
    } else {
      return null;
    }
  }

  /**
   * Number of times a thread yields before inflating the lock on a
   * object to a heavy-weight lock.  The current value was for the
   * portBOB benchmark on a 12-way SMP (AIX) in the Fall of '99.  This
   * is almost certainly not the optimal value.
   */
  private static final int retryLimit = 40; // (-1 is effectively infinity)

  /**
   * Should we trace lockContention to enable debugging?
   */
  private static final boolean traceContention = false;

}


