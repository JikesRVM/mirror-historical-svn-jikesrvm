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
import org.jikesrvm.Callbacks;
import org.jikesrvm.Constants;
import org.jikesrvm.Services;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.objectmodel.ThinLockConstants;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UnpreemptibleNoWarn;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.Offset;

public abstract class CommonThinLockPlan extends CommonLockPlan {
  /**
   * Return the lock index for a given lock word.  Assert valid index
   * ranges, that the fat lock bit is set, and that the lock entry
   * exists.
   *
   * @param lockWord The lock word whose lock index is being established
   * @return the lock index corresponding to the lock workd.
   */
  @Inline
  protected int getLockIndex(Word lockWord) {
    int index = lockWord.and(TL_LOCK_ID_MASK).rshl(TL_LOCK_ID_SHIFT).toInt();
    if (VM.VerifyAssertions) {
      if (!(index > 0 && index < Lock.numLocks())) {
        VM.sysWrite("Lock index out of range! Word: "); VM.sysWrite(lockWord);
        VM.sysWrite(" index: "); VM.sysWrite(index);
        VM.sysWrite(" locks: "); VM.sysWrite(Lock.numLocks());
        VM.sysWriteln();
      }
      VM._assert(index > 0 && index < Lock.numLocks());  // index is in range
      VM._assert(!lockWord.and(TL_FAT_LOCK_MASK).isZero());        // fat lock bit is set
      VM._assert(Lock.getLock(index) != null);               // the lock is actually there
    }
    return index;
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
  @Unpreemptible("Become another thread when lock is contended, don't preempt in other cases")
  static void inlineLock(Object o, Offset lockOffset) {
    Word old = Magic.prepareWord(o, lockOffset);
    if (old.rshl(TL_THREAD_ID_SHIFT).isZero()) {
      // implies that fatbit == 0 & threadid == 0
      int threadId = RVMThread.getCurrentThread().getLockingId();
      if (Magic.attemptWord(o, lockOffset, old, old.or(Word.fromIntZeroExtend(threadId)))) {
        Magic.isync(); // don't use stale prefetched data in monitor
        if (STATS) fastLocks++;
        return;           // common case: o is now locked
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
  @Unpreemptible("No preemption normally, but may raise exceptions")
  static void inlineUnlock(Object o, Offset lockOffset) {
    Word old = Magic.prepareWord(o, lockOffset);
    Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
    if (old.xor(threadId).rshl(TL_LOCK_COUNT_SHIFT).isZero()) { // implies that fatbit == 0 && count == 0 && lockid == me
      Magic.sync(); // memory barrier: subsequent locker will see previous writes
      if (Magic.attemptWord(o, lockOffset, old, old.and(TL_UNLOCK_MASK))) {
        return; // common case: o is now unlocked
      }
    }
    unlock(o, lockOffset);  // uncommon case: default to non inlined unlock()
  }

  /**
   * @param obj an object
   * @param lockOffset the offset of the thin lock word in the object.
   * @param thread a thread
   * @return <code>true</code> if the lock on obj at offset lockOffset is currently owned
   *         by thread <code>false</code> if it is not.
   */
  public boolean holdsLock(Object obj, Offset lockOffset, RVMThread thread) {
    int tid = thread.getLockingId();
    Word bits = Magic.getWordAtOffset(obj, lockOffset);
    if (bits.and(TL_FAT_LOCK_MASK).isZero()) {
      // if locked, then it is locked with a thin lock
      return (bits.and(ThinLockConstants.TL_THREAD_ID_MASK).toInt() == tid);
    } else {
      // if locked, then it is locked with a fat lock
      int index = getLockIndex(bits);
      Lock l = Lock.getLock(index);
      return l != null && l.getOwnerId() == tid;
    }
  }
}


