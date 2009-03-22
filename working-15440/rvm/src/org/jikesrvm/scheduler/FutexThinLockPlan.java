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
import org.jikesrvm.Constants;
import org.jikesrvm.Services;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.objectmodel.ThinLockConstants;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.NoNullCheck;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/**
 * This is a "basic" implementation of thin locks that actually ends up
 * performing really well.  This was the production thin locking implementation
 * in RVM before the lock refactoring.
 */
public class FutexThinLockPlan extends AbstractThinLockPlan {
  public static FutexThinLockPlan instance;
  
  public FutexThinLockPlan() {
    instance=this;
  }
  
  public void boot() {
    if (false) {
      VM.sysWriteln("TLF_NUM_BITS_STAT    = ",TLF_NUM_BITS_STAT);
      VM.sysWriteln("TLF_NUM_BITS_TID     = ",TLF_NUM_BITS_TID);
      VM.sysWriteln("TLF_NUM_BITS_RC      = ",TLF_NUM_BITS_RC);
      VM.sysWriteln("TLF_LOCK_COUNT_SHIFT = ",TLF_LOCK_COUNT_SHIFT);
      VM.sysWriteln("TLF_THREAD_ID_SHIFT  = ",TLF_THREAD_ID_SHIFT);
      VM.sysWriteln("TLF_STAT_SHIFT       = ",TLF_STAT_SHIFT);
      VM.sysWriteln("TLF_LOCK_ID_SHIFT    = ",TLF_LOCK_ID_SHIFT);
      VM.sysWriteln("TLF_LOCK_COUNT_UNIT  = ",TLF_LOCK_COUNT_UNIT);
      VM.sysWriteln("TLF_LOCK_COUNT_MASK  = ",TLF_LOCK_COUNT_MASK);
      VM.sysWriteln("TLF_THREAD_ID_MASK   = ",TLF_THREAD_ID_MASK);
      VM.sysWriteln("TLF_LOCK_ID_MASK     = ",TLF_LOCK_ID_MASK);
      VM.sysWriteln("TLF_STAT_MASK        = ",TLF_STAT_MASK);
      VM.sysWriteln("TLF_UNLOCK_MASK      = ",TLF_UNLOCK_MASK);
      VM.sysWriteln("TLF_STAT_THIN        = ",TLF_STAT_THIN);
      VM.sysWriteln("TLF_STAT_THIN_WAIT   = ",TLF_STAT_THIN_WAIT);
      VM.sysWriteln("TLF_STAT_FAT         = ",TLF_STAT_FAT);
    }
  }
  
  /**
   * Obtains a lock on the indicated object.  Abbreviated light-weight
   * locking sequence inlined by the optimizing compiler for the
   * prologue of synchronized methods and for the
   * <code>monitorenter</code> bytecode.
   *
   * @param o the object to be locked
   * @param lockOffset the offset of the thin lock word in the object.
   * @see org.jikesrvm.compilers.opt.hir2lir.ExpandRuntimeServices
   */
  @Inline
  @NoNullCheck
  public final void inlineLock(Object o, Offset lockOffset) {
    if (false) VM.tsysWriteln("in inlineLock with o = ",Magic.objectAsAddress(o)," and offset = ",lockOffset);
    Word old = Magic.prepareWord(o, lockOffset);
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
    if (old.and(TLF_THREAD_ID_MASK.or(TLF_STAT_FAT)).isZero()) {
      // implies that fatbit == 0 & threadid == 0
      if (Magic.attemptWord(o, lockOffset, old, old.or(threadId))) {
        Magic.isync(); // don't use stale prefetched data in monitor
        if (CommonLockPlan.HEAVY_STATS) CommonLockPlan.fastLocks++;
        if (false) VM.tsysWriteln("Done with inlineLock (fast1).");
        return;           // common case: o is now locked
      }
    }
    if (false) VM.tsysWriteln("going into slow path...");
    lock(o, lockOffset); // uncommon case: default to out-of-line lock()
    if (false) VM.tsysWriteln("Done with inlineLock.");
  }

  /**
   * Releases the lock on the indicated object.  Abreviated
   * light-weight unlocking sequence inlined by the optimizing
   * compiler for the epilogue of synchronized methods and for the
   * <code>monitorexit</code> bytecode.
   *
   * @param o the object to be unlocked
   * @param lockOffset the offset of the thin lock word in the object.
   * @see org.jikesrvm.compilers.opt.hir2lir.ExpandRuntimeServices
   */
  @Inline
  @NoNullCheck
  public final void inlineUnlock(Object o, Offset lockOffset) {
    if (false) VM.tsysWriteln("in inlineUnlock with o = ",Magic.objectAsAddress(o)," and offset = ",lockOffset);
    Word old = Magic.prepareWord(o, lockOffset);
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
    if (old.xor(threadId).rshl(TLF_LOCK_COUNT_SHIFT).isZero()) { // implies that fatbit == 0 && waitbit == 0 && count == 0 && lockid == me
      Magic.sync(); // memory barrier: subsequent locker will see previous writes
      if (Magic.attemptWord(o, lockOffset, old, old.and(TLF_UNLOCK_MASK))) {
        return; // common case: o is now unlocked
      }
    }
    unlock(o, lockOffset);  // uncommon case: default to non inlined unlock()
  }

  @NoInline
  @NoNullCheck
  public void lock(Object o, Offset lockOffset) {
    if (false) VM.tsysWriteln("in lock with o = ",Magic.objectAsAddress(o)," and offset = ",lockOffset);

    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());

    Word thinLockFlags = Word.zero();
    
    for (int cnt=0;;cnt++) {
      // the idea:
      // - if the lock is uninflated and unclaimed attempt to grab it the thin way
      // - if the lock is uninflated and claimed by me, attempt to increase rec count
      // - if the lock is uninflated and claimed by someone else, inflate it and
      //   do the slow path of acquisition
      // - if the lock is inflated, grab it.
      
      boolean attemptToInflate=false;
      
      Word old = Magic.prepareWord(o, lockOffset);
      Word id = old.and(TLF_THREAD_ID_MASK.or(TLF_STAT_FAT));
      if (id.isZero()) {
        // lock not held, acquire quickly with rec count == 1
        if (Magic.attemptWord(o, lockOffset, old, old.or(threadId).or(thinLockFlags))) {
          Magic.isync();
          break;
        }
      } else if (id.EQ(threadId)) {
        // lock held, attempt to increment rec count
        Word changed = old.toAddress().plus(TLF_LOCK_COUNT_UNIT).toWord();
        if (changed.and(TLF_LOCK_COUNT_MASK).isZero()) {
          attemptToInflate=true;
        } else if (Magic.attemptWord(o, lockOffset, old, changed)) {
          Magic.isync();
          break;
        }
      } else if (!old.and(TLF_STAT_FAT).isZero()) {
        // we have a heavy lock.
        LockConfig.Selected l=(LockConfig.Selected)
          LockConfig.selectedPlan.getLock(getLockIndex(old));
        if (l!=null && l.lockHeavy(o)) {
          break;
        } // else we grabbed someone else's lock
      } else if (cnt>VM.thinRetryLimit) {
        // grab it using futex
        if (!old.and(TLF_STAT_THIN_WAIT).isZero() ||
            Magic.attemptWord(o, lockOffset, old, old.or(TLF_STAT_THIN_WAIT))) {
          if (false) VM.tsysWriteln("using futex");
          int res=FutexUtils.waitNicely(o, lockOffset, old.or(TLF_STAT_THIN_WAIT));
          if (LockConfig.LATE_WAKE_FUTEX) {
            thinLockFlags=TLF_STAT_THIN_WAIT;
          } else {
            // should this guy wake on the old address?
            FutexUtils.wake(o, lockOffset, 1);
          }
          if (res==0) continue; // don't spin if we were awakened.
        }
      }
      
      if (attemptToInflate) {
        // the lock is not fat, is owned by someone else, or else the count wrapped.
        // attempt to inflate it (this may fail, in which case we'll just harmlessly
        // loop around) and lock it (may also fail, if we get the wrong lock).  if it
        // succeeds, we're done.
        if (false) VM.tsysWriteln("doing inflation");
        if (LockConfig.selectedPlan.inflateAndLock(o, lockOffset)) {
          break;
        }
      } else {
        Spinning.interruptibly(cnt,old.and(TLF_THREAD_ID_MASK).toInt());
      }
    }
    
    if (false) VM.tsysWriteln("locked with bits = ",Magic.getWordAtOffset(o, lockOffset));
  }
  
  @NoInline
  @NoNullCheck
  public void unlock(Object o, Offset lockOffset) {
    if (false) VM.tsysWriteln("in unlock with o = ",Magic.objectAsAddress(o)," and offset = ",lockOffset);

    Magic.sync();
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
    for (int cnt=0;;cnt++) {
      Word old = Magic.prepareWord(o, lockOffset);
      Word id = old.and(TLF_THREAD_ID_MASK.or(TLF_STAT_FAT));
      if (id.EQ(threadId)) {
        if (old.and(TLF_LOCK_COUNT_MASK.or(TLF_STAT_THIN_WAIT)).isZero()) {
          // release lock
          Word changed = old.and(TLF_UNLOCK_MASK);
          if (Magic.attemptWord(o, lockOffset, old, changed)) {
            return;
          }
        } else if (old.and(TLF_LOCK_COUNT_MASK).isZero()) {
          // release the lock and wake somebody up
          Word changed = old.and(TLF_UNLOCK_MASK);
          if (Magic.attemptWord(o, lockOffset, old, changed)) {
            FutexUtils.wake(o, lockOffset, 1);
            return;
          }
        } else {
          // decrement count
          Word changed = old.toAddress().minus(TLF_LOCK_COUNT_UNIT).toWord();
          if (Magic.attemptWord(o, lockOffset, old, changed)) {
            return; // unlock succeeds
          }
        }
      } else {
        if (old.and(TLF_STAT_FAT).isZero()) {
          // someone else holds the lock in thin mode and it's not us.  that indicates
          // bad use of monitorenter/monitorexit
          VM.tsysWriteln("illegal monitor state: ",old);
          VM.sysWriteln("for object ",Magic.objectAsAddress(o));
          RVMThread.raiseIllegalMonitorStateException("thin unlocking", o);
        }
        // fat unlock
        LockConfig.Selected l=(LockConfig.Selected)
          LockConfig.selectedPlan.getLock(getLockIndex(old));
        if (l!=null) {
          l.unlockHeavy();
          return;
        } // else the lock was inflated but hasn't been added yet.  this is ultra-rare.
      }
      if (LockConfig.SPIN_UNLOCK) Spinning.interruptibly(cnt,0);
    }
  }
  
  /**
   * @param obj an object
   * @param lockOffset the offset of the thin lock word in the object.
   * @param thread a thread
   * @return <code>true</code> if the lock on obj at offset lockOffset is currently owned
   *         by thread <code>false</code> if it is not.
   */
  @Unpreemptible
  @NoNullCheck
  public final boolean holdsLock(Object obj, Offset lockOffset, RVMThread thread) {
    for (int cnt=0;;++cnt) {
      int tid = thread.getLockingId();
      Word bits = Magic.getWordAtOffset(obj, lockOffset);
      if (bits.and(TLF_STAT_FAT).isZero()) {
        // if locked, then it is locked with a thin lock
        return (bits.and(ThinLockConstants.TLF_THREAD_ID_MASK).toInt() == tid);
      } else {
        // if locked, then it is locked with a fat lock
        int index = getLockIndex(bits);
        LockConfig.Selected l = (LockConfig.Selected)
          Magic.eatCast(LockConfig.selectedPlan.getLock(index));
        if (l!=null) {
          return l.holdsLock(obj, thread);
        }
      }
      Spinning.uninterruptibly(cnt, 0);
    }
  }
  
  @Inline
  @Unpreemptible
  public final boolean isFat(Word lockWord) {
    return !lockWord.and(TLF_STAT_FAT).isZero();
  }
  
  /**
   * Return the lock index for a given lock word.  Assert valid index
   * ranges, that the fat lock bit is set, and that the lock entry
   * exists.
   *
   * @param lockWord The lock word whose lock index is being established
   * @return the lock index corresponding to the lock workd.
   */
  @Inline
  @Uninterruptible
  public final int getLockIndex(Word lockWord) {
    int index = lockWord.and(TLF_LOCK_ID_MASK).rshl(TLF_LOCK_ID_SHIFT).toInt();
    if (VM.VerifyAssertions) {
      if (!(index > 0 && index < LockConfig.selectedPlan.numLocks())) {
        VM.sysWrite("Lock index out of range! Word: "); VM.sysWrite(lockWord);
        VM.sysWrite(" index: "); VM.sysWrite(index);
        VM.sysWrite(" locks: "); VM.sysWrite(LockConfig.selectedPlan.numLocks());
        VM.sysWriteln();
      }
      VM._assert(index > 0 && index < LockConfig.selectedPlan.numLocks());  // index is in range
      VM._assert(!lockWord.and(TLF_STAT_FAT).isZero());        // fat lock bit is set
    }
    return index;
  }

  @Inline
  @Unpreemptible
  public final int getLockOwner(Word lockWord) {
    return lockWord.and(ThinLockConstants.TLF_THREAD_ID_MASK).toInt();
  }
  
  @Inline
  @Unpreemptible
  public final int getRecCount(Word lockWord) {
    return lockWord.and(TLF_LOCK_COUNT_MASK).rshl(TLF_LOCK_COUNT_SHIFT).toInt() + 1;
  }
  
  @Inline
  @Unpreemptible
  public final boolean attemptToMarkInflated(Object o, Offset lockOffset,
                                             Word oldLockWord,
                                             int lockId,
                                             int cnt) {
    Word changed=
      TLF_STAT_FAT.or(Word.fromIntZeroExtend(lockId).lsh(TLF_LOCK_ID_SHIFT))
      .or(oldLockWord.and(TLF_UNLOCK_MASK));
    if (Synchronization.tryCompareAndSwap(o, lockOffset, oldLockWord, changed)) {
      if (!oldLockWord.and(TLF_STAT_THIN_WAIT).isZero()) {
        FutexUtils.wake(o, lockOffset, RVMThread.numThreads); // this wakes everybody up.  nasty!
      }
      return true;
    } else {
      return false;
    }
  }
  
  @Inline
  @Unpreemptible
  public final boolean attemptToMarkDeflated(Object o, Offset lockOffset,
                                             Word oldLockWord) {
    // NB: we only need a CAS here because the lock word may be concurrently
    // modified by GC or hashing.
    return Synchronization.tryCompareAndSwap(
      o, lockOffset, oldLockWord, oldLockWord.and(TLF_UNLOCK_MASK));
  }
  
  public void dumpStats() {
  }
}


