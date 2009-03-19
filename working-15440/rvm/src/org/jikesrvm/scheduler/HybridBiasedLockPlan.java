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
 * Biased locking implementation that also uses thin locking as a fall-back.
 * @author Filip
 */
public class HybridBiasedLockPlan extends AbstractThinLockPlan {
  public static HybridBiasedLockPlan instance;
  
  static final boolean HEAVY_STATS=false;
  
  static int numHeaderLocks;
  static int numHeaderLocksSlow;
  static int numLocks;
  static int numLocksSlow;
  static int numThinInflates;
  static int numSpinRevocations;
  static int numSlowRevocations;
  
  public HybridBiasedLockPlan() {
    instance=this;
  }
  
  public void boot() {
    if (false) {
      VM.sysWriteln("BL_NUM_BITS_STAT    = ",BL_NUM_BITS_STAT);
      VM.sysWriteln("BL_NUM_BITS_TID     = ",BL_NUM_BITS_TID);
      VM.sysWriteln("BL_NUM_BITS_RC      = ",BL_NUM_BITS_RC);
      VM.sysWriteln("BL_LOCK_COUNT_SHIFT = ",BL_LOCK_COUNT_SHIFT);
      VM.sysWriteln("BL_THREAD_ID_SHIFT  = ",BL_THREAD_ID_SHIFT);
      VM.sysWriteln("BL_STAT_SHIFT       = ",BL_STAT_SHIFT);
      VM.sysWriteln("BL_LOCK_ID_SHIFT    = ",BL_LOCK_ID_SHIFT);
      VM.sysWriteln("BL_LOCK_COUNT_UNIT  = ",BL_LOCK_COUNT_UNIT);
      VM.sysWriteln("BL_LOCK_COUNT_MASK  = ",BL_LOCK_COUNT_MASK);
      VM.sysWriteln("BL_THREAD_ID_MASK   = ",BL_THREAD_ID_MASK);
      VM.sysWriteln("BL_LOCK_ID_MASK     = ",BL_LOCK_ID_MASK);
      VM.sysWriteln("BL_STAT_MASK        = ",BL_STAT_MASK);
      VM.sysWriteln("BL_UNLOCK_MASK      = ",BL_UNLOCK_MASK);
      VM.sysWriteln("BL_STAT_BIASABLE    = ",BL_STAT_BIASABLE);
      VM.sysWriteln("BL_STAT_THIN        = ",BL_STAT_THIN);
      VM.sysWriteln("BL_STAT_FAT         = ",BL_STAT_FAT);
    }
  }
  
  public void lateBoot() {
    if (HEAVY_STATS || VM.monitorBiasing) {
      StatsThread st=new StatsThread();
      st.makeDaemon(true);
      st.start();
    }
  }
  
  @Inline
  @NoNullCheck
  public final void inlineLock(Object o, Offset lockOffset) {
    Word old = Magic.prepareWord(o, lockOffset); // FIXME: bad for PPC?
    Word id = old.and(BL_THREAD_ID_MASK.or(BL_STAT_MASK));
    Word tid = Word.fromIntSignExtend(RVMThread.getCurrentThread().getLockingId());
    if (id.EQ(tid)) {
      Word changed = old.toAddress().plus(BL_LOCK_COUNT_UNIT).toWord();
      if (!changed.and(BL_LOCK_COUNT_MASK).isZero()) {
        Magic.setWordAtOffset(o, lockOffset, changed);
        return;
      }
    } else if (id.EQ(BL_STAT_THIN)) {
      // lock is thin and not held by anyone
      if (Magic.attemptWord(o, lockOffset, old, old.or(tid))) {
        Magic.isync();
        return;
      }
    } else if (LockConfig.INLINE_INFLATED && !old.and(BL_STAT_FAT).isZero()) {
      if (LockConfig.selectedPlan.inlineLockInflated(old, o, tid)) {
        return;
      }
    }
    lock(o, lockOffset);
  }
  
  @Inline
  @NoNullCheck
  public final void inlineUnlock(Object o, Offset lockOffset) {
    Word old = Magic.prepareWord(o, lockOffset); // FIXME: bad for PPC?
    Word id = old.and(BL_THREAD_ID_MASK.or(BL_STAT_MASK));
    Word tid = Word.fromIntSignExtend(RVMThread.getCurrentThread().getLockingId());
    if (id.EQ(tid)) {
      if (!old.and(BL_LOCK_COUNT_MASK).isZero()) {
        Magic.setWordAtOffset(
          o, lockOffset,
          old.toAddress().minus(BL_LOCK_COUNT_UNIT).toWord());
        return;
      }
    } else if (old.xor(tid).rshl(BL_LOCK_COUNT_SHIFT).EQ(BL_STAT_THIN.rshl(BL_LOCK_COUNT_SHIFT))) {
      Magic.sync();
      if (Magic.attemptWord(o, lockOffset, old, old.and(BL_UNLOCK_MASK).or(BL_STAT_THIN))) {
        return;
      }
    } else if (LockConfig.INLINE_INFLATED && !old.and(BL_STAT_FAT).isZero()) {
      if (LockConfig.selectedPlan.inlineUnlockInflated(old)) {
        return;
      }
    }
    unlock(o, lockOffset);
  }
  
  @NoInline
  @NoNullCheck
  public void lock(Object o, Offset lockOffset) {
    if (HEAVY_STATS) numLocks++;
        
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());

    for (int cnt=0;;cnt++) {
      Word old = Magic.getWordAtOffset(o, lockOffset);
      Word stat = old.and(BL_STAT_MASK);
      boolean tryToInflate=false;
      if (stat.EQ(BL_STAT_BIASABLE)) {
        Word id = old.and(BL_THREAD_ID_MASK);
        if (id.isZero()) {
          // lock is unbiased, bias it in our favor and grab it
          if (Synchronization.tryCompareAndSwap(
                o, lockOffset,
                old,
                old.or(threadId).toAddress().plus(BL_LOCK_COUNT_UNIT).toWord())) {
            Magic.isync();
            return;
          }
        } else if (id.EQ(threadId)) {
          // lock is biased in our favor
          Word changed = old.toAddress().plus(BL_LOCK_COUNT_UNIT).toWord();
          if (!changed.and(BL_LOCK_COUNT_MASK).isZero()) {
            Magic.setWordAtOffset(o, lockOffset, changed);
            return;
          } else {
            tryToInflate=true;
          }
        } else {
          if (casFromBiased(o, lockOffset, old, biasBitsToThinBits(old), cnt)) {
            continue; // don't spin, since it's thin now
          }
        }
      } else if (stat.EQ(BL_STAT_THIN)) {
        Word id = old.and(BL_THREAD_ID_MASK);
        if (id.isZero()) {
          if (Synchronization.tryCompareAndSwap(
                o, lockOffset, old, old.or(threadId))) {
            Magic.isync();
            return;
          }
        } else if (id.EQ(threadId)) {
          Word changed = old.toAddress().plus(BL_LOCK_COUNT_UNIT).toWord();
          if (changed.and(BL_LOCK_COUNT_MASK).isZero()) {
            tryToInflate=true;
          } else if (Synchronization.tryCompareAndSwap(
                       o, lockOffset, old, changed)) {
            Magic.isync();
            return;
          }
        } else if (cnt>VM.thinRetryLimit) {
          tryToInflate=true;
        }
      } else {
        if (VM.VerifyAssertions) VM._assert(stat.EQ(BL_STAT_FAT));
        // lock is fat.  contend on it.
        LockConfig.Selected l=(LockConfig.Selected)
          LockConfig.selectedPlan.getLock(getLockIndex(old));
        if (l!=null && l.lockHeavy(o)) {
          return;
        }
      }
      
      if (tryToInflate) {
        if (HEAVY_STATS) numLocksSlow++;
        // the lock is not fat, is owned by someone else, or else the count wrapped.
        // attempt to inflate it (this may fail, in which case we'll just harmlessly
        // loop around) and lock it (may also fail, if we get the wrong lock).  if it
        // succeeds, we're done.
        // NB: this calls into our attemptToMarkInflated() method, which will do the
        // Right Thing if the lock is biased to someone else.
        if (LockConfig.selectedPlan.inflateAndLock(o, lockOffset)) {
          return;
        }
      } else {
        Spinning.interruptibly(cnt,old.and(BL_THREAD_ID_MASK).toInt());
      }
    }
  }
  
  @NoInline
  @NoNullCheck
  public void unlock(Object o, Offset lockOffset) {
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
    for (int cnt=0;;cnt++) {
      Word old = Magic.getWordAtOffset(o, lockOffset);
      Word stat = old.and(BL_STAT_MASK);
      if (stat.EQ(BL_STAT_BIASABLE)) {
        Word id = old.and(BL_THREAD_ID_MASK);
        if (id.EQ(threadId)) {
          if (old.and(BL_LOCK_COUNT_MASK).isZero()) {
            RVMThread.raiseIllegalMonitorStateException("biased unlocking: we own this object but the count is already zero", o);
          }
          Magic.setWordAtOffset(o, lockOffset,
                                old.toAddress().minus(BL_LOCK_COUNT_UNIT).toWord());
          return;
        } else {
          RVMThread.raiseIllegalMonitorStateException("biased unlocking: we don't own this object", o);
        }
      } else if (stat.EQ(BL_STAT_THIN)) {
        Magic.sync();
        Word id = old.and(BL_THREAD_ID_MASK);
        if (id.EQ(threadId)) {
          Word changed;
          if (old.and(BL_LOCK_COUNT_MASK).isZero()) {
            changed=old.and(BL_UNLOCK_MASK).or(BL_STAT_THIN);
          } else {
            changed=old.toAddress().minus(BL_LOCK_COUNT_UNIT).toWord();
          }
          if (Synchronization.tryCompareAndSwap(
                o, lockOffset, old, changed)) {
            return;
          }
        } else {
          if (false) {
            VM.sysWriteln("threadId = ",threadId);
            VM.sysWriteln("id = ",id);
          }
          RVMThread.raiseIllegalMonitorStateException("thin unlocking: we don't own this object", o);
        }
      } else {
        if (VM.VerifyAssertions) VM._assert(stat.EQ(BL_STAT_FAT));
        // fat unlock
        LockConfig.Selected l=(LockConfig.Selected)
          LockConfig.selectedPlan.getLock(getLockIndex(old));
        if (l!=null) {
          l.unlockHeavy();
          return;
        }
      }
      Spinning.interruptibly(cnt,0);
    }
  }
  
  @Unpreemptible
  @NoNullCheck
  public final boolean holdsLock(Object o, Offset lockOffset, RVMThread thread) {
    int tid = thread.getLockingId();
    Word bits = Magic.getWordAtOffset(o, lockOffset);
    if (bits.and(BL_STAT_MASK).EQ(BL_STAT_BIASABLE)) {
      // if locked, then it is locked with a thin lock
      return
        bits.and(BL_THREAD_ID_MASK).toInt() == tid &&
        !bits.and(BL_LOCK_COUNT_MASK).isZero();
    } else if (bits.and(BL_STAT_MASK).EQ(BL_STAT_THIN)) {
      return bits.and(BL_THREAD_ID_MASK).toInt()==tid;
    } else {
      if (VM.VerifyAssertions) VM._assert(bits.and(BL_STAT_MASK).EQ(BL_STAT_FAT));
      // if locked, then it is locked with a fat lock
      int index = getLockIndex(bits);
      LockConfig.Selected l = (LockConfig.Selected)
        Magic.eatCast(LockConfig.selectedPlan.getLock(index));
      return l != null && l.holdsLock(o, thread);
    }
  }

  @Inline
  @Unpreemptible
  public final boolean isFat(Word lockWord) {
    return lockWord.and(BL_STAT_MASK).EQ(BL_STAT_FAT);
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
    int index = lockWord.and(BL_LOCK_ID_MASK).rshl(BL_LOCK_ID_SHIFT).toInt();
    if (VM.VerifyAssertions) {
      if (!(index > 0 && index < LockConfig.selectedPlan.numLocks())) {
        VM.sysWrite("Lock index out of range! Word: "); VM.sysWrite(lockWord);
        VM.sysWrite(" index: "); VM.sysWrite(index);
        VM.sysWrite(" locks: "); VM.sysWrite(LockConfig.selectedPlan.numLocks());
        VM.sysWriteln();
      }
      VM._assert(index > 0 && index < LockConfig.selectedPlan.numLocks());  // index is in range
      VM._assert(lockWord.and(BL_STAT_MASK).EQ(BL_STAT_FAT));        // fat lock bit is set
    }
    return index;
  }

  @Inline
  @Unpreemptible
  public final int getLockOwner(Word lockWord) {
    if (VM.VerifyAssertions) VM._assert(!isFat(lockWord));
    if (lockWord.and(BL_STAT_MASK).EQ(BL_STAT_BIASABLE)) {
      if (lockWord.and(BL_LOCK_COUNT_MASK).isZero()) {
        return 0;
      } else {
        return lockWord.and(BL_THREAD_ID_MASK).toInt();
      }
    } else {
      return lockWord.and(BL_THREAD_ID_MASK).toInt();
    }
  }
  
  @Inline
  @Unpreemptible
  public final int getRecCount(Word lockWord) {
    if (VM.VerifyAssertions) VM._assert(getLockOwner(lockWord)!=0);
    if (lockWord.and(BL_STAT_MASK).EQ(BL_STAT_BIASABLE)) {
      return lockWord.and(BL_LOCK_COUNT_MASK).rshl(BL_LOCK_COUNT_SHIFT).toInt();
    } else {
      return lockWord.and(BL_LOCK_COUNT_MASK).rshl(BL_LOCK_COUNT_SHIFT).toInt()+1;
    }
  }
  
  @NoInline
  @Unpreemptible
  public final boolean casFromBiased(Object o, Offset lockOffset,
                                     Word oldLockWord, Word changed,
                                     int cnt) {
    RVMThread me=RVMThread.getCurrentThread();
    Word id=oldLockWord.and(BL_THREAD_ID_MASK);
    if (id.isZero()) {
      if (false) VM.sysWriteln("id is zero - easy case.");
      return Synchronization.tryCompareAndSwap(o, lockOffset, oldLockWord, changed);
    } else {
      if (false) VM.sysWriteln("id = ",id);
      int slot=id.toInt()>>BL_THREAD_ID_SHIFT;
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
        if (VM.biasSpinToRevoke && cnt==0) {
          owner.objectToUnbias=o;
          owner.takeYieldpoint=1;
          
          int limit=VM.biasRevokeRetryLimit;
          for (int cnt2=0;cnt2<limit;++cnt2) {
            if (Magic.getWordAtOffset(o,lockOffset)!=oldLockWord) {
              break;
            }
            Spinning.uninterruptibly(cnt2,0);
          }
          
          owner.objectToUnbias=null;
          return false;
        } else {
          numSlowRevocations++;
          
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
          if (false) VM.sysWriteln("entering pair handshake");
          owner.beginPairHandshake();
          if (false) VM.sysWriteln("done with that");
          
          Word newLockWord=Magic.getWordAtOffset(o, lockOffset);
          result=Synchronization.tryCompareAndSwap(
            o, lockOffset, oldLockWord, changed);
          owner.endPairHandshake();
          if (false) VM.sysWriteln("that worked.");
          
          return result;
        }
      }
    }
  }
  
  @Inline
  @Unpreemptible
  public final boolean attemptToMarkInflated(Object o, Offset lockOffset,
                                             Word oldLockWord,
                                             int lockId,
                                             int cnt) {
    if (VM.VerifyAssertions) VM._assert(oldLockWord.and(BL_STAT_MASK).NE(BL_STAT_FAT));
    if (false) VM.sysWriteln("attemptToMarkInflated with oldLockWord = ",oldLockWord);
    // what this needs to do:
    // 1) if the lock is thin, it's just a CAS
    // 2) if the lock is unbiased, CAS in the inflation
    // 3) if the lock is biased in our favor, store the lock without CAS
    // 4) if the lock is biased but to someone else, enter the pair handshake
    //    to unbias it and install the inflated lock
    Word changed=
      BL_STAT_FAT.or(Word.fromIntZeroExtend(lockId).lsh(BL_LOCK_ID_SHIFT))
      .or(oldLockWord.and(BL_UNLOCK_MASK));
    if (false && oldLockWord.and(BL_STAT_MASK).EQ(BL_STAT_THIN))
      VM.sysWriteln("obj = ",Magic.objectAsAddress(o),
                    ", old = ",oldLockWord,
                    ", owner = ",getLockOwner(oldLockWord),
                    ", rec = ",getLockOwner(oldLockWord)==0?0:getRecCount(oldLockWord),
                    ", changed = ",changed,
                    ", lockId = ",lockId);
    if (false) VM.sysWriteln("changed = ",changed);
    if (oldLockWord.and(BL_STAT_MASK).EQ(BL_STAT_THIN)) {
      if (false) VM.sysWriteln("it's thin, inflating the easy way.");
      if (HEAVY_STATS) numThinInflates++;
      return Synchronization.tryCompareAndSwap(
        o, lockOffset, oldLockWord, changed);
    } else {
      return casFromBiased(o, lockOffset, oldLockWord, changed, cnt);
    }
  }
  
  @Inline
  @Unpreemptible
  private Word biasBitsToThinBits(Word bits) {
    int lockOwner=getLockOwner(bits);
    
    Word changed=bits.and(BL_UNLOCK_MASK).or(BL_STAT_THIN);
    
    if (lockOwner!=0) {
      int recCount=getRecCount(bits);
      changed=changed
        .or(Word.fromIntZeroExtend(lockOwner))
        .or(Word.fromIntZeroExtend(recCount-1).lsh(BL_LOCK_COUNT_SHIFT));
    }
    
    return changed;
  }
  
  @Unpreemptible
  public final void poll(RVMThread t) {
    Object o=t.objectToUnbias;
    t.objectToUnbias=null;
    
    if (o!=null) {
      Offset lockOffset=Magic.getObjectType(o).getThinLockOffset();
      
      Word bits=Magic.getWordAtOffset(o, lockOffset);
      
      if (bits.and(BL_STAT_MASK).EQ(BL_STAT_BIASABLE)) {
        Word changed=biasBitsToThinBits(bits);
        
        if (false) VM.sysWriteln("unbiasing in poll: ",bits," -> ",changed);
        
        if (VM.VerifyAssertions) VM._assert(Magic.getWordAtOffset(o, lockOffset)==bits);
        
        Magic.setWordAtOffset(o,lockOffset,changed);
        
        numSpinRevocations++;
      }
    }
  }
  
  @Inline
  @Unpreemptible
  public final boolean attemptToMarkDeflated(Object o, Offset lockOffset,
                                             Word oldLockWord) {
    // we allow concurrent modification of the lock word when it's thin or fat.
    Word changed=oldLockWord.and(BL_UNLOCK_MASK).or(BL_STAT_THIN);
    if (VM.VerifyAssertions) VM._assert(getLockOwner(changed)==0);
    return Synchronization.tryCompareAndSwap(
      o, lockOffset, oldLockWord, changed);
  }
  
  @NoNullCheck
  public boolean lockHeader(Object o, Offset lockOffset) {
    if (HEAVY_STATS) numHeaderLocks++;
    // what this should do:
    // 1) take advantage of the fact that if a lock is fat it can only go back to
    //    being thin, so concurrent modification of the lock word is allowed.
    // 2) if it's biased, we own it anyway so we can "lock" it by incrementing the
    //    count.
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
    for (;;) {
      boolean attemptToInflate=false;
      Word old=Magic.getWordAtOffset(o,lockOffset);
      if (old.and(BL_STAT_MASK).NE(BL_STAT_BIASABLE)) {
        if (VM.VerifyAssertions) VM._assert(old.and(BL_STAT_MASK).EQ(BL_STAT_THIN) ||
                                            old.and(BL_STAT_MASK).EQ(BL_STAT_FAT));
        return false;
      } else {
        Word id = old.and(BL_THREAD_ID_MASK);
        // what do we do here?  if we have the bias, then it's easy.  but what
        // if we don't?  in that case we need to be ultra-careful.  what we can
        // do:
        // 1) if the lock is biased in our favor, then lock it
        // 2) if the lock is unbiased, then bias it in our favor an lock it
        // 3) if the lock is biased in someone else's favor, inflate it (so we can go above)
        if (id.isZero()) {
          // lock is unbiased, bias it in our favor and grab it
          if (Synchronization.tryCompareAndSwap(
                o, lockOffset,
                old,
                old.or(threadId).toAddress().plus(BL_LOCK_COUNT_UNIT).toWord())) {
            Magic.isync();
            return true;
          }
        } else if (id.EQ(threadId)) {
          // lock is biased in our favor, so grab it
          Word changed = old.toAddress().plus(BL_LOCK_COUNT_UNIT).toWord();
          if (!changed.and(BL_LOCK_COUNT_MASK).isZero()) {
            Magic.setWordAtOffset(o, lockOffset, changed);
            return true;
          } else {
            attemptToInflate=true;
          }
        } else {
          attemptToInflate=true;
        }
        
        if (attemptToInflate) {
          numHeaderLocksSlow++;
          // lock is biased to someone else.  inflate it.
          LockConfig.selectedPlan.inflate(o,lockOffset);
        }
      }
    }
  }
  
  @NoNullCheck
  public final void unlockHeader(Object o, Offset lockOffset,boolean lockHeaderResult) {
    // what to do here?
    // 1) if lockHeaderResult is false, we're done
    // 2) if lockHeaderResult is true, release the lock.
    if (lockHeaderResult) {
      unlock(o, lockOffset);
    }
  }
  
  @Inline
  @Uninterruptible
  public final boolean allowHeaderCAS(Object o, Offset lockOffset) {
    return Magic.getWordAtOffset(o,lockOffset).and(BL_STAT_MASK).NE(BL_STAT_BIASABLE);
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
          if (HEAVY_STATS) {
            VM.sysWriteln("   numHeaderLocks     = ",numHeaderLocks);
          }
          VM.sysWriteln("   numHeaderLocksSlow = ",numHeaderLocksSlow);
          if (HEAVY_STATS) {
            VM.sysWriteln("   numLocks           = ",numLocks);
            VM.sysWriteln("   numLocksSlow       = ",numLocksSlow);
            VM.sysWriteln("   numThinInflates    = ",numThinInflates);
          }
          VM.sysWriteln("   numSpinRevocations = ",numSpinRevocations);
          VM.sysWriteln("   numSlowRevocations = ",numSlowRevocations);
          
          numHeaderLocks=0;
          numHeaderLocksSlow=0;
          numLocks=0;
          numLocksSlow=0;
          numThinInflates=0;
          numSpinRevocations=0;
          numSlowRevocations=0;
        }
      } catch (Throwable e) {
        VM.printExceptionAndDie("stats thread",e);
      }
      VM.sysFail("should never get here");
    }
  }
}


