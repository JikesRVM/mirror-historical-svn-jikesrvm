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
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

@Uninterruptible
public class SloppyDeflateThinLockPlan extends CommonThinLockPlan {
  public static SloppyDeflateThinLockPlan instance;
  
  public SloppyDeflateThinLockPlan() {
    instance=this;
  }
  
  @Interruptible
  public void init() {
    super.init();
    // nothing to do...
  }
  
  @Interruptible
  public void boot() {
    super.boot();
    // we'll see if there's anything to do.
  }
  
  @NoInline
  @Unpreemptible
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
        if (Magic.attemptWord(o, lockOffset, old, old.or(threadId))) {
          Magic.isync();
          return;
        }
      } else if (id.EQ(threadId)) {
        Word changed = old.toAddress().plus(TL_LOCK_COUNT_UNIT).toWord();
        if (!changed.and(TL_LOCK_COUNT_MASK).isZero() &&
            Magic.attemptWord(o, lockOffset, old, changed)) {
          Magic.isync();
          return;
        }
      } else if (!old.and(TL_FAT_LOCK_MASK).isZero()) {
        // we have a heavy lock.
        if (getLock(getLockIndex(old)).lockHeavy(o)) {
          return;
        } // else we grabbed someone else's lock
      } else {
        // the lock is not fat, is owned by someone else, or else the count wrapped.
        // attempt to inflate it (this may fail, in which case we'll just harmlessly
        // loop around).  if it succeeds, we loop around anyway, so that we can
        // grab the lock the fat way.
        inflate(o, lockOffset);
      }
    }
  }
  
  @NoInline
  @Unpreemptible
  public void unlock(Object o, Offset lockOffset) {
    Magic.sync();
    for (;;) {
      Word old = Magic.prepareWord(o, lockOffset);
      Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
      Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
      if (old.EQ(threadId)) {
        if (old.and(TL_LOCK_COUNT_MASK).isZero()) {
          // release lock
          Word changed = old.and(TL_UNLOCK_MASL);
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
        getLock(getLockIndex(old)).unlockHeavy();
        return;
      }
    }
  }
  
  @Unpreemptible
  protected SloppyDeflateThinLock inflate(Object o, Offset lockOffset) {
    // the idea:
    // attempt to allocate fat lock, extract the
    // state of the thin lock and put it into the fat lock, mark the lock as active
    // (allowing it to be deflated) and attempt CAS to replace
    // the thin lock with a pointer to the fat lock.
    
    // nb:
    // what about when someone asks for the lock to be inflated, holds onto the fat
    // lock, and then does stuff to it?  won't the autodeflater deflate it at that
    // point?
    // no.  you're only allowed to ask for the fat lock when the object is locked.  in
    // that case, it cannot be deflated.
    
    for (;;) {
      Word old = o.plus(lockOffset).loadWord();
      Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
      
      if (!old.and(TL_FAT_LOCK_MASK).isZero()) {
        return (SloppyDeflateThinLock)getLock(getLockIndex(old));
      }
      
      SloppyDeflateThinLock l=(SloppyDeflateThinLock)allocate();
      if (l==null) {
        // allocation failed, give up
        return null;
      }
      
      l.setLockedObject(o);
      l.setOwnerId(old.and(TL_THREAD_ID_MASK).toInt());
      if (l.getOwnerId()!=0) {
        l.setRecursionCount(old.ant(TL_LOCK_COUNT_MASK).rshl(TL_LOCK_COUNT_SHIFT).toInt()+1);
      }
      
      Magic.sync(); // ensure the above writes happen.
      
      l.activate();
      
      // the lock is now "active" - so the deflation detector thingy will see it, but it
      // will also see that the lock is held.
      
      Word changed=
        TL_FAT_LOCK_MASK.or(Word.fromIntZeroExtend(l.id).lsh(TL_LOCK_ID_SHIFT))
        .or(old.and(TL_UNLOCK_MASK));
      
      if (Synchronization.tryCompareAndSwap(o, lockOffset, old, changed)) {
        return l;
      } else {
        free(l);
      }
    }
  }
  
  @Unpreemptible
  public AbstractLock getHeavyLock(Object o, Offset lockOffset, boolean create) {
    Word old = Magic.getWordAtOffset(o, lockOffset);
    if (!(old.and(TL_FAT_LOCK_MASK).isZero())) { // already a fat lock in place
      return getLock(getLockIndex(old));
    } else if (create) {
      AbstractLock result=inflate(o, lockOffset);
      if (VM.VerifyAssertions) VM._assert(result!=null);
      return result;
    } else {
      return null;
    }
  }
  
  protected void pollDeflate(Object o, Offset lockOffset) {
    // the idea:
    // - check if the object has a fat lock
    // - if it does, check its numUses counter:
    //   - if it's zero, lock the lock's state, see if numUses is still zero, and
    //     if the lock is deflatable, and if both are true, deflate the lock, unlock
    //     its state, and free it.
    //   - if it's non-zero, reset it to zero.
  }
}


