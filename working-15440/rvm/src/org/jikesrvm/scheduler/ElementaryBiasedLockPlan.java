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

/**
 * Extremely simple implementation of biased locking.
 * @author Filip
 */
public class ElementaryBiasedLockPlan extends CommonThinLockPlan {
  public static ElementaryBiasedLockPlan instance;
  
  public ElementaryBiasedLockPlan() {
    instance=this;
  }
  
  @Inline
  @NoNullCheck
  public final void inlineLock(Object o, Offset lockOffset) {
    Word old = Magic.getWordAtOffset(o, lockOffset);
    if (old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK)).EQ(
          Word.fromIntSignExtend(RVMThread.getCurrentThread().getLockingId()))) {
      Word changed = old.toAddress().plus(TL_LOCK_COUNT_UNIT).toWord();
      if (!changed.and(TL_LOCK_COUNT_MASK).isZero()) {
        Magic.setWordAtOffset(o, lockOffset, changed);
        return;
      }
    }
    lock(o, lockOffset);
  }
  
  @Inline
  @NoNullCheck
  public final void inlineUnlock(Object o, Offset lockOffset) {
    Word old = Magic.getWordAtOffset(o, lockOffset);
    if (old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK)).EQ(
          Word.fromIntSignExtend(RVMThread.getCurrentThread().getLockingId())) &&
        !old.and(TL_LOCK_COUNT_MASK).isZero()) {
      Magic.setWordAtOffset(
        o, lockOffset,
        old.toAddress().minus(TL_LOCK_COUNT_UNIT).toWord());
    }
    unlock(o, lockOffset);
  }
  
  @NoInline
  @NoNullCheck
  public void lock(Object o, Offset lockOffset) {
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());

    for (;;) {
      Word old = Magic.getWordAtOffset(o, lockOffset);
      Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
      if (id.isZero()) {
        // lock is unbiased, bias it in our favor and grab it
        if (Synchronization.tryCompareAndSwap(
              o, lockOffset,
              old,
              old.or(threadId).toAddress().plus(TL_LOCK_COUNT_UNIT).toWord())) {
          Magic.isync();
          return;
        }
      } else if (id.EQ(threadId)) {
        // lock is biased in our favor
        Word changed = old.toAddress().plus(TL_LOCK_COUNT_UNIT).toWord();
        if (!changed.and(TL_LOCK_COUNT_MASK).isZero()) {
          Magic.setWordAtOffset(o, lockOffset, changed);
          return;
        }
      } else if (!old.and(TL_FAT_LOCK_MASK).isZero()) {
        // lock is fat.  contend on it.
        LockConfig.Selected l=(LockConfig.Selected)
          LockConfig.selectedPlan.getLock(getLockIndex(old));
        if (l!=null && l.lockHeavy(o)) {
          return;
        }
      } else {
        // the lock is not fat, is owned by someone else, or else the count wrapped.
        // attempt to inflate it (this may fail, in which case we'll just harmlessly
        // loop around) and lock it (may also fail, if we get the wrong lock).  if it
        // succeeds, we're done.
        // NB: this calls into our attemptToMarkInflated() method, which will do the
        // Right Thing if the lock is biased to someone else.
        if (LockConfig.selectedPlan.inflateAndLock(o, lockOffset)) {
          return;
        }
      }
    }
  }
  
  @NoInline
  @NoNullCheck
  public void unlock(Object o, Offset lockOffset) {
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
    Word old = Magic.getWordAtOffset(o, lockOffset);
    Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
    if (id.EQ(threadId)) {
      if (old.and(TL_LOCK_COUNT_MASK).isZero()) {
        RVMThread.raiseIllegalMonitorStateException("biased unlocking: we own this object but the count is already zero", o);
      }
      Magic.setWordAtOffset(o, lockOffset,
                            old.toAddress().minus(TL_LOCK_COUNT_UNIT).toWord());
    } else {
      if (old.and(TL_FAT_LOCK_MASK).isZero()) {
        RVMThread.raiseIllegalMonitorStateException("biased unlocking: we don't own this object", o);
      }
      // fat unlock
      LockConfig.Selected l=(LockConfig.Selected)
        LockConfig.selectedPlan.getLock(getLockIndex(old));
      l.unlockHeavy();
    }
  }
  
  @Unpreemptible
  @NoNullCheck
  public final boolean holdsLock(Object o, Offset lockOffset, RVMThread thread) {
    int tid = thread.getLockingId();
    Word bits = Magic.getWordAtOffset(o, lockOffset);
    if (bits.and(TL_FAT_LOCK_MASK).isZero()) {
      // if locked, then it is locked with a thin lock
      return
        bits.and(TL_THREAD_ID_MASK).toInt() == tid &&
        !bits.and(TL_LOCK_COUNT_MASK).isZero();
    } else {
      // if locked, then it is locked with a fat lock
      int index = getLockIndex(bits);
      LockConfig.Selected l = (LockConfig.Selected)
        Magic.eatCast(LockConfig.selectedPlan.getLock(index));
      return l != null && l.holdsLock(o, thread);
    }
  }

  @Inline
  @Unpreemptible
  public final int getLockOwner(Word lockWord) {
    if (lockWord.and(TL_LOCK_COUNT_MASK).isZero()) {
      return 0;
    } else {
      return lockWord.and(ThinLockConstants.TL_THREAD_ID_MASK).toInt();
    }
  }
  
  @Inline
  @Unpreemptible
  public final int getRecCount(Word lockWord) {
    return lockWord.and(TL_LOCK_COUNT_MASK).rshl(TL_LOCK_COUNT_SHIFT).toInt();
  }
  
  @Inline
  @Unpreemptible
  public final boolean attemptToMarkInflated(Object o, Offset lockOffset,
                                             Word oldLockWord,
                                             int lockId) {
    if (VM.VerifyAssertions) VM._assert(oldLockWord.and(TL_FAT_LOCK_MASK).isZero());
    // what this needs to do:
    // 1) if the lock is unbiased, CAS in the inflation
    // 2) if the lock is biased in our favor, store the lock without CAS
    // 2) if the lock is biased but to someone else, enter the pair handshake
    //    to unbias it and install the inflated lock
    RVMThread me=RVMThread.getCurrentThread();
    Word id=oldLockWord.and(TL_THREAD_ID_MASK);
    Word changed=
      TL_FAT_LOCK_MASK.or(Word.fromIntZeroExtend(lockId).lsh(TL_LOCK_ID_SHIFT))
      .or(oldLockWord.and(TL_UNLOCK_MASK));
    if (id.isZero()) {
      return Synchronization.tryCompareAndSwap(o, lockOffset,oldLockWord, changed);
    } else {
      RVMThread owner=RVMThread.threadBySlot[id.toInt()>>TL_THREAD_ID_SHIFT];
      if (owner==me) {
        Magic.setWordAtOffset(o, lockOffset, changed);
        return true;
      } else {
        boolean result=false;
        owner.beginPairHandshake();
        Word newLockWord=Magic.getWordAtOffset(o, lockOffset);
        if (newLockWord.EQ(oldLockWord)) {
          Magic.setWordAtOffset(o, lockOffset, changed);
          result=true;
        }
        owner.endPairHandshake();
        return result;
      }
    }
  }
  
  @Inline
  @Unpreemptible
  public final boolean attemptToMarkDeflated(Object o, Offset lockOffset,
                                             Word oldLockWord) {
    // NB: we disallow concurrent modifications of the lock word, so this
    // doesn't require a CAS.
    Magic.setWordAtOffset(o, lockOffset, oldLockWord.and(TL_UNLOCK_MASK));
    return true;
  }
  
  @NoInline
  @NoNullCheck
  public boolean lockHeader(Object o, Offset lockOffset) {
    // what do we do here?  if we have the bias, then it's easy.  but what
    // if we don't?  in that case we need to be ultra-careful.  what we can
    // do:
    // 1) if the lock is biased in our favor, then lock it
    // 2) if the lock is unbiased, then bias it in our favor an lock it
    // 3) if the lock is biased in someone else's favor, inflate it (so we can do (4))
    // 4) if the lock is fat, lock its state
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
    for (;;) {
      Word old = Magic.getWordAtOffset(o, lockOffset);
      Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
      if (id.isZero()) {
        // lock is unbiased, bias it in our favor and grab it
        if (Synchronization.tryCompareAndSwap(
              o, lockOffset,
              old,
              old.or(threadId).toAddress().plus(TL_LOCK_COUNT_UNIT).toWord())) {
          Magic.isync();
          return true;
        }
      } else if (id.EQ(threadId)) {
        // lock is biased in our favor, so grab it
        Word changed = old.toAddress().plus(TL_LOCK_COUNT_UNIT).toWord();
        if (!changed.and(TL_LOCK_COUNT_MASK).isZero()) {
          Magic.setWordAtOffset(o, lockOffset, changed);
          return true;
        }
      } else if (!old.and(TL_FAT_LOCK_MASK).isZero()) {
        // lock is fat.  lock its state.
        LockConfig.Selected l=(LockConfig.Selected)
          LockConfig.selectedPlan.getLock(getLockIndex(old));
        if (l!=null) {
          l.lockState();
          if (l.lockedObject==o && Magic.getWordAtOffset(o, lockOffset)==old) {
            return true; // cannot deflate the lock, so the header really is locked
          }
          l.unlockState();
        }
      } else {
        // lock is biased to someone else.  inflate it.
        LockConfig.selectedPlan.inflate(o,lockOffset);
      }
    }
  }
  
  @NoInline
  @NoNullCheck
  public final void unlockHeader(Object o, Offset lockOffset) {
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
    Word old = Magic.getWordAtOffset(o, lockOffset);
    Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
    if (id.EQ(threadId)) {
      if (VM.VerifyAssertions) VM._assert(!old.and(TL_LOCK_COUNT_MASK).isZero());
      Magic.setWordAtOffset(o, lockOffset,
                            old.toAddress().minus(TL_LOCK_COUNT_UNIT).toWord());
    } else {
      if (VM.VerifyAssertions) VM._assert(!old.and(TL_FAT_LOCK_MASK).isZero());
      // fat unlock
      LockConfig.Selected l=(LockConfig.Selected)
        LockConfig.selectedPlan.getLock(getLockIndex(old));
      if (VM.VerifyAssertions) VM._assert(l!=null);
      if (VM.VerifyAssertions) VM._assert(l.stateIsLocked());
      l.unlockState();
    }
  }
  
  @Inline
  @Uninterruptible
  public final boolean allowHeaderCAS(Object o, Offset lockOffset) {
    return false;
  }
}


