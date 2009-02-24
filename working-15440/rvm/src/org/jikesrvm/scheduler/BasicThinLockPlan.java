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
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.NoNullCheck;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

public class BasicThinLockPlan extends AbstractThinLockPlan {
  public static BasicThinLockPlan instance;

  public BasicThinLockPlan() {
    instance=this;
  }
  
  public void init() {}
  public void boot() {}
  public void lateBoot() {}
  
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
    if (!LockConfig.USE_REC_FASTPATH) {
      Word old = Magic.prepareWord(o, lockOffset);
      if (old.rshl(TL_THREAD_ID_SHIFT).isZero()) {
        // implies that fatbit == 0 & threadid == 0
        int threadId = RVMThread.getCurrentThread().getLockingId();
        if (Magic.attemptWord(o, lockOffset, old, old.or(Word.fromIntZeroExtend(threadId)))) {
          Magic.isync(); // don't use stale prefetched data in monitor
          if (CommonLockPlan.HEAVY_STATS) CommonLockPlan.fastLocks++;
          return;           // common case: o is now locked
        }
      }
    } else {
      Word old = Magic.prepareWord(o, lockOffset);
      Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
      if (id.isZero()) {
        // implies that fatbit == 0 & threadid == 0
        int threadId = RVMThread.getCurrentThread().getLockingId();
        if (Magic.attemptWord(o, lockOffset, old, old.or(Word.fromIntZeroExtend(threadId)))) {
          Magic.isync(); // don't use stale prefetched data in monitor
          if (CommonLockPlan.HEAVY_STATS) CommonLockPlan.fastLocks++;
          return;           // common case: o is now locked
        }
      } else {
        Word threadId = Word.fromIntSignExtend(RVMThread.getCurrentThread().getLockingId());
        if (id.EQ(threadId)) {
          Word changed=old.toAddress().plus(TL_LOCK_COUNT_UNIT).toWord();
          if (!changed.and(TL_LOCK_COUNT_MASK).isZero() &&
              Magic.attemptWord(o, lockOffset, old, changed)) {
            Magic.isync();
            if (CommonLockPlan.HEAVY_STATS) CommonLockPlan.fastLocks++;
            return;
          }
        }
      }
    }
    lock(o, lockOffset); // uncommon case: default to out-of-line lock()
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
    if (!LockConfig.USE_REC_FASTPATH) {
      Word old = Magic.prepareWord(o, lockOffset);
      Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
      if (old.xor(threadId).rshl(TL_LOCK_COUNT_SHIFT).isZero()) { // implies that fatbit == 0 && count == 0 && lockid == me
        Magic.sync(); // memory barrier: subsequent locker will see previous writes
        if (Magic.attemptWord(o, lockOffset, old, old.and(TL_UNLOCK_MASK))) {
          return; // common case: o is now unlocked
        }
      }
    } else {
      Word old = Magic.prepareWord(o, lockOffset);
      Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
      Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
      if (id.EQ(threadId)) {
        Magic.sync();
        if (old.and(TL_LOCK_COUNT_MASK).isZero()) {
          // release lock
          Word changed = old.and(TL_UNLOCK_MASK);
          if (Magic.attemptWord(o, lockOffset, old, changed)) {
            return;
          }
        } else {
          // decrement count
          Word changed = old.toAddress().minus(TL_LOCK_COUNT_UNIT).toWord();
          if (Magic.attemptWord(o, lockOffset, old, changed)) {
            return; // unlock succeeds
          }
        }
      }
    }
    unlock(o, lockOffset);  // uncommon case: default to non inlined unlock()
  }

  @NoInline
  public void lock(Object o, Offset lockOffset) {
    for (;;) {
      // the idea:
      // - if the lock is uninflated and unclaimed attempt to grab it the thin way
      // - if the lock is uninflated and claimed by me, attempt to increase rec count
      // - if the lock is uninflated and claimed by someone else, inflate it and
      //   do the slow path of acquisition
      // - if the lock is inflated, grab it.
      
      Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
      Word old = Magic.prepareWord(o, lockOffset);
      Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
      if (id.isZero()) {
        // lock not held, acquire quickly with rec count == 1
        if (Magic.attemptWord(o, lockOffset, old, old.or(threadId))) {
          Magic.isync();
          return;
        }
      } else if (id.EQ(threadId)) {
        // lock held, attempt to increment rec count
        Word changed = old.toAddress().plus(TL_LOCK_COUNT_UNIT).toWord();
        if (!changed.and(TL_LOCK_COUNT_MASK).isZero() &&
            Magic.attemptWord(o, lockOffset, old, changed)) {
          Magic.isync();
          return;
        }
      } else if (!old.and(TL_FAT_LOCK_MASK).isZero()) {
        // we have a heavy lock.
        LockConfig.Selected l=(LockConfig.Selected)
          LockConfig.selectedPlan.getLock(getLockIndex(old));
        if (l!=null && l.lockHeavy(o)) {
          return;
        } // else we grabbed someone else's lock
      } else {
        // the lock is not fat, is owned by someone else, or else the count wrapped.
        // attempt to inflate it (this may fail, in which case we'll just harmlessly
        // loop around) and lock it (may also fail, if we get the wrong lock).  if it
        // succeeds, we're done.
        if (LockConfig.selectedPlan.inflateAndLock(o, lockOffset)) {
          return;
        }
      }
    }
  }
  
  @NoInline
  public void unlock(Object o, Offset lockOffset) {
    Magic.sync();
    for (;;) {
      Word old = Magic.prepareWord(o, lockOffset);
      Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
      Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
      if (id.EQ(threadId)) {
        if (old.and(TL_LOCK_COUNT_MASK).isZero()) {
          // release lock
          Word changed = old.and(TL_UNLOCK_MASK);
          if (Magic.attemptWord(o, lockOffset, old, changed)) {
            return;
          }
        } else {
          // decrement count
          Word changed = old.toAddress().minus(TL_LOCK_COUNT_UNIT).toWord();
          if (Magic.attemptWord(o, lockOffset, old, changed)) {
            return; // unlock succeeds
          }
        }
      } else {
        if (old.and(TL_FAT_LOCK_MASK).isZero()) {
          // someone else holds the lock in thin mode and it's not us.  that indicates
          // bad use of monitorenter/monitorexit
          RVMThread.raiseIllegalMonitorStateException("thin unlocking", o);
        }
        // fat unlock
        LockConfig.Selected l=(LockConfig.Selected)
          LockConfig.selectedPlan.getLock(getLockIndex(old));
        l.unlockHeavy();
        return;
      }
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
    int tid = thread.getLockingId();
    Word bits = Magic.getWordAtOffset(obj, lockOffset);
    if (bits.and(TL_FAT_LOCK_MASK).isZero()) {
      // if locked, then it is locked with a thin lock
      return (bits.and(ThinLockConstants.TL_THREAD_ID_MASK).toInt() == tid);
    } else {
      // if locked, then it is locked with a fat lock
      int index = getLockIndex(bits);
      LockConfig.Selected l = (LockConfig.Selected)
        Magic.eatCast(LockConfig.selectedPlan.getLock(index));
      return l != null && l.holdsLock(obj, thread);
    }
  }
  
  @Inline
  @Unpreemptible
  public final boolean isFat(Word lockWord) {
    return !lockWord.and(TL_FAT_LOCK_MASK).isZero();
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
  @Unpreemptible
  public final int getLockIndex(Word lockWord) {
    int index = lockWord.and(TL_LOCK_ID_MASK).rshl(TL_LOCK_ID_SHIFT).toInt();
    if (VM.VerifyAssertions) {
      if (!(index > 0 && index < LockConfig.selectedPlan.numLocks())) {
        VM.sysWrite("Lock index out of range! Word: "); VM.sysWrite(lockWord);
        VM.sysWrite(" index: "); VM.sysWrite(index);
        VM.sysWrite(" locks: "); VM.sysWrite(LockConfig.selectedPlan.numLocks());
        VM.sysWriteln();
      }
      VM._assert(index > 0 && index < LockConfig.selectedPlan.numLocks());  // index is in range
      VM._assert(!lockWord.and(TL_FAT_LOCK_MASK).isZero());        // fat lock bit is set
    }
    return index;
  }
  
  @Inline
  @Unpreemptible
  public final int getLockOwner(Word lockWord) {
    return lockWord.and(ThinLockConstants.TL_THREAD_ID_MASK).toInt();
  }
  
  @Inline
  @Unpreemptible
  public final int getRecCount(Word lockWord) {
    return lockWord.and(TL_LOCK_COUNT_MASK).rshl(TL_LOCK_COUNT_SHIFT).toInt() + 1;
  }
  
  @Inline
  @Unpreemptible
  public final boolean attemptToMarkInflated(Object o, Offset lockOffset,
                                             Word oldLockWord,
                                             int lockId) {
    Word changed=
      TL_FAT_LOCK_MASK.or(Word.fromIntZeroExtend(lockId).lsh(TL_LOCK_ID_SHIFT))
      .or(oldLockWord.and(TL_UNLOCK_MASK));
    return Synchronization.tryCompareAndSwap(o, lockOffset, oldLockWord, changed);
  }
  
  @Inline
  @Unpreemptible
  public final boolean attemptToMarkDeflated(Object o, Offset lockOffset,
                                             Word oldLockWord) {
    return Synchronization.tryCompareAndSwap(
      o, lockOffset, oldLockWord, oldLockWord.and(TL_UNLOCK_MASK));
  }
}


