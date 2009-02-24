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
public class ElementaryBiasedLockPlan extends AbstractThinLockPlan {
  public static ElementaryBiasedLockPlan instance;
  
  public ElementaryBiasedLockPlan() {
    instance=this;
  }
  
  @Inline
  @NoNullcheck
  public final void inlineLock(Object o, Offset lockOffset) {
    Word old = Magic.getWordAtOffset(o, lockOffset);
    if (old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK)).EQ(
          Word.fromIntSignExtend(RVMThread.getCurrentThread().getLockingId()))) {
      Word changed = old.toAddress().plus(TL_LOCK_COUNT_UNIT).toWord();
      if (!changed.and(TL_LOCK_COUNT_MASK).isZero()) {
        Word.setWordAtOffset(o, lockOffset, changed);
        return;
      }
    }
    lock(o, lockOffset);
  }
  
  @Inline
  @NoNullcheck
  public final void inlineUnlock(Object o, Offset lockOffset) {
    Word old = Magic.getWordAtOffset(o, lockOffset);
    if (old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK)).EQ(
          Word.fromIntSignExtend(RVMThread.getCurrentThread().getLockingId())) &&
        !old.and(TL_LOCK_COUNT_MASK).isZero()) {
      Word.setWordAtOffset(
        o, lockOffset,
        old.toAddress().minus(TL_LOCK_COUNT_UNIT).toWord());
    }
    unlock(o, lockOffset);
  }
  
  @NoInline
  @NoNullcheck
  public void lock(Object o, Offset lockOffset) {
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());

    for (;;) {
      Word old = Magic.prepareWord(o, lockOffset);
      Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
      if (id.isZero()) {
        // lock is unbiased, bias it in our favor and grab it
        if (Magic.attemptWord(o, lockOffset, old, old.or(threadId).toAddress().plus(TL_LOCK_COUNT_UNIT).toWord())) {
          Magic.isync();
          return;
        }
      } else if (id.EQ(threadId)) {
        // lock is biased in our favor
        Word changed = old.toAddress().plus(TL_LOCK_COUNT_UNIT).toWord();
        if (changed.and(TL_LOCK_COUNT_MASK).isZero()) {
          // count overflowed.  inflate and lock.
          if (LockConfig.selectedPlan.inflateAndLock(o, lockOffset)) {
            return;
          }
        } else if (Magic.attemptWord(o, lockOffset, old, changed)) {
          Magic.isync();
          return;
        }
      } else if (old.and(TL_FAT_LOCK_MASK).isZero()) {
        // lock is biased but not in our favor.  this is the fun part.
        // what we do:
        // 1) figure out which thread owns it
        // 2) enter into a pair handshake with that thread
        // 3) inflate the lock
        // 4) end the pair handshake
        // 5) loop around.  three things may happen: i) the lock will still
        //    be held in fat mode (allowing us to contend on it), ii) the
        //    lock will no longer be held (releasing a fat lock returns the
        //    lock to an unbiased state, which allows us to bias it for
        //    ourselves), or iii) the lock will be rebiased to someone else,
        //    in which case we come back here (we hope that this happens
        //    rarely)
        RVMThread owner=RVMThread.threadBySlot[id.toInt()>>TL_THREAD_ID_SHIFT];
        if (owner!=null) {
          owner.beginPairHandshake();
          LockConfig.selectedPlan.inflate(o, lockOffset);
          owner.endPairHandshake();
        }
      } else {
        // lock is fat.  contend on it.
        LockConfig.Selected l=(LockConfig.Selected)
          LockConfig.selectedPlan.getLock(getLockIndex(old));
        if (l!=null && l.lockHeavy(o)) {
          return;
        }
      }
    }
  }
}


