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
import org.vmmagic.pragma.NonMoving;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/**
 * Extremely simple implementation of biased locking.
 * @author Filip
 */
public class ElementaryBiasedLockPlan extends CommonThinLockPlan {
  public static ElementaryBiasedLockPlan instance;
  
  static final boolean STATS=false;
  
  static int numHeaderLocks;
  static int numHeaderLocksSlow;
  static int numLocks;
  static int numLocksSlow;
  static int numRevocations;
  
  public ElementaryBiasedLockPlan() {
    instance=this;
  }
  
  public void lateBoot() {
    if (STATS) {
      StatsThread st=new StatsThread();
      st.makeDaemon(true);
      st.start();
    }
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
      return;
    }
    unlock(o, lockOffset);
  }
  
  @NoInline
  @NoNullCheck
  public void lock(Object o, Offset lockOffset) {
    if (STATS) numLocks++;
        
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());

    for (;;) {
      boolean attemptToInflate=false;
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
        } else {
          attemptToInflate=true;
        }
      } else if (!old.and(TL_FAT_LOCK_MASK).isZero()) {
        // lock is fat.  contend on it.
        LockConfig.Selected l=(LockConfig.Selected)
          LockConfig.selectedPlan.getLock(getLockIndex(old));
        if (l!=null && l.lockHeavy(o)) {
          return;
        }
      } else {
        attemptToInflate=true;
      }
      
      if (attemptToInflate) {
        if (STATS) numLocksSlow++;
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
    if (false) VM.sysWriteln("attemptToMarkInflated with oldLockWord = ",oldLockWord);
    // what this needs to do:
    // 1) if the lock is unbiased, CAS in the inflation
    // 2) if the lock is biased in our favor, store the lock without CAS
    // 3) if the lock is biased but to someone else, enter the pair handshake
    //    to unbias it and install the inflated lock
    RVMThread me=RVMThread.getCurrentThread();
    Word id=oldLockWord.and(TL_THREAD_ID_MASK);
    Word changed=
      TL_FAT_LOCK_MASK.or(Word.fromIntZeroExtend(lockId).lsh(TL_LOCK_ID_SHIFT))
      .or(oldLockWord.and(TL_UNLOCK_MASK));
    if (id.isZero()) {
      return Synchronization.tryCompareAndSwap(o, lockOffset, oldLockWord, changed);
    } else {
      if (false) VM.sysWriteln("id = ",id);
      int slot=id.toInt()>>TL_THREAD_ID_SHIFT;
      if (false) VM.sysWriteln("slot = ",slot);
      RVMThread owner=RVMThread.threadBySlot[slot];
      if (owner==me /* I own it, so I can unbias it trivially.  This occurs
                       when we are inflating due to, for example, wait() */ ||
          owner==null /* the thread that owned it is dead, so it's safe to
                         unbias. */) {
        // note that we use a CAS here, but it's only needed in the case
        // that owner==null, since in that case some other thread may also
        // be unbiasing.
        return Synchronization.tryCompareAndSwap(
          o, lockOffset, oldLockWord, changed);
      } else {
        if (STATS) numRevocations++;
        
        boolean result=false;
        
        // NB. this may stop a thread other than the one that had the bias,
        // if that thread died and some other thread took its slot.  that's
        // why we do a CAS below.  it's only needed if some other thread
        // had seen the owner be null (which may happen if we came here after
        // a new thread took the slot while someone else came here when the
        // slot was still null).  if it was the case that everyone else had
        // seen a non-null owner, then the pair handshake would serve as
        // sufficient synchronization (the id would identify the set of threads
        // that shared that id's communicationLock).  oddly, that means that
        // this whole thing could be "simplified" to acquire the
        // communicationLock even if the owner was null.  but that would be
        // goofy.
        owner.beginPairHandshake();
        
        Word newLockWord=Magic.getWordAtOffset(o, lockOffset);
        result=Synchronization.tryCompareAndSwap(
          o, lockOffset, oldLockWord, changed);
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
  
  @NoNullCheck
  public boolean lockHeader(Object o, Offset lockOffset) {
    if (STATS) numHeaderLocks++;
    // what do we do here?  if we have the bias, then it's easy.  but what
    // if we don't?  in that case we need to be ultra-careful.  what we can
    // do:
    // 1) if the lock is biased in our favor, then lock it
    // 2) if the lock is unbiased, then bias it in our favor an lock it
    // 3) if the lock is biased in someone else's favor, inflate it (so we can do (4))
    // 4) if the lock is fat, lock its state
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
    for (;;) {
      boolean attemptToInflate=false;
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
        } else {
          attemptToInflate=true;
        }
      } else if (!old.and(TL_FAT_LOCK_MASK).isZero()) {
        // lock is fat.  lock its state.
        LockConfig.Selected l=(LockConfig.Selected)
          LockConfig.selectedPlan.getLock(getLockIndex(old));
        if (l!=null) {
          if (l.getOwnerId()==RVMThread.getCurrentThread().getLockingId()) {
            // we own the lock so increment the rec count
            if (l.lockHeavy(o)) {
              return true;
            }
          } else {
            l.lockState();
            if (l.lockedObject==o && Magic.getWordAtOffset(o, lockOffset)==old) {
              return true; // cannot deflate the lock, so the header really is locked
            }
            l.unlockState();
          }
        }
      } else {
        attemptToInflate=true;
      }
      
      if (attemptToInflate) {
        if (STATS) numHeaderLocksSlow++;
        // lock is biased to someone else.  inflate it.
        LockConfig.selectedPlan.inflate(o,lockOffset);
      }
    }
  }
  
  @NoNullCheck
  public final void unlockHeader(Object o, Offset lockOffset,boolean lockHeaderResult) {
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
      // two possibilities:
      // 1) we had locked the lock's state, in which case we unlock the state
      // 2) we had incremented the lock's rec count, in which case we dec it
      // 3) we had incremented the bias rec count but the lock got inflated,
      //    so we unlock it
      // FIXME: if we had biased the lock in our favor but it got inflated,
      // then in here we should unlock the lock rather than unlocking the
      // state!!
      LockConfig.Selected l=(LockConfig.Selected)
        LockConfig.selectedPlan.getLock(getLockIndex(old));
      if (VM.VerifyAssertions) VM._assert(l!=null);
      if (VM.VerifyAssertions) VM._assert(l.getLockedObject()==o);
      if (l.getOwnerId()==RVMThread.getCurrentThread().getLockingId()) {
        // case (2) or (3)
        l.unlockHeavy();
      } else {
        // case (1)
        if (VM.VerifyAssertions) VM._assert(l.stateIsLocked());
        l.unlockState();
      }
    }
  }
  
  @Inline
  @Uninterruptible
  public final boolean allowHeaderCAS(Object o, Offset lockOffset) {
    return false;
  }
  
  @NonMoving
  static class StatsThread extends RVMThread {
    StatsThread() {
      super("StatsThread");
    }
    
    @Override
    public void run() {
      try {
        for (;;) {
          RVMThread.sleep(1000L*1000L*1000L);

          VM.sysWriteln("periodic biased locking stats report:");
          VM.sysWriteln("   numHeaderLocks = ",numHeaderLocks);
          VM.sysWriteln("   numHeaderLocksSlow = ",numHeaderLocksSlow);
          VM.sysWriteln("   numLocks = ",numLocks);
          VM.sysWriteln("   numLocksSlow = ",numLocksSlow);
          VM.sysWriteln("   numRevocations = ",numRevocations);
          
          numHeaderLocks=0;
          numHeaderLocksSlow=0;
          numLocks=0;
          numLocksSlow=0;
          numRevocations=0;
        }
      } catch (Throwable e) {
        VM.printExceptionAndDie("stats thread",e);
      }
      VM.sysFail("should never get here");
    }
  }
}


