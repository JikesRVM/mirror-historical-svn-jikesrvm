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
import org.vmmagic.pragma.NoNullCheck;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

public class EagerDeflateLockPlan extends CommonLockPlan {
  public static EagerDeflateLockPlan instance;
  
  public EagerDeflateLockPlan() {
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
  @NoNullCheck
  public final EagerDeflateLock inflate(Object o, Offset lockOffset) {
    if (PROFILE) RVMThread.enterLockingPath();
    if (VM.VerifyAssertions) {
      VM._assert(LockConfig.selectedThinPlan.holdsLock(
                   o, lockOffset, RVMThread.getCurrentThread()));
    }
    EagerDeflateLock l = (EagerDeflateLock)Magic.eatCast(allocateActivateAndAdd());
    if (VM.VerifyAssertions) {
      VM._assert(l != null); // inflate called by wait (or notify) which shouldn't be called during GC
    }
    EagerDeflateLock rtn = attemptToInflate(o, lockOffset, l);
    if (rtn == l)
      l.unlockState();
    if (PROFILE) RVMThread.leaveLockingPath();
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
  @NoNullCheck
  public final boolean inflateAndLock(Object o, Offset lockOffset) {
    EagerDeflateLock l = (EagerDeflateLock)Magic.eatCast(allocateActivateAndAdd());
    if (l == null) return false; // can't allocate locks during GC
    EagerDeflateLock rtn = attemptToInflate(o, lockOffset, l);
    if (l != rtn) {
      l = rtn;
      l.lockState();
    }
    return l.lockHeavyLocked(o);
  }

  /**
   * Promotes a light-weight lock to a heavy-weight lock.  If this returns the lock
   * that you gave it, its mutex will be locked; otherwise, its mutex will be unlocked.
   * Hence, calls to this method should always be followed by a condition lock() or
   * unlock() call.
   *
   * @param o the object to get a heavy-weight lock
   * @param lockOffset the offset of the thin lock word in the object.
   * @return the inflated lock; either the one you gave, or another one, if the lock
   *         was inflated by some other thread.
   */
  @NoNullCheck
  protected final EagerDeflateLock attemptToInflate(Object o,
                                                    Offset lockOffset,
                                                    EagerDeflateLock l) {
    if (PROFILE) RVMThread.enterLockingPath();
    Word old;
    if (false) VM.sysWriteln("l = ",Magic.objectAsAddress(l));
    l.lockState();
    do {
      Word bits = Magic.getWordAtOffset(o, lockOffset);
      // check to see if another thread has already created a fat lock
      if (LockConfig.selectedThinPlan.isFat(bits)) {
        if (trace) {
          VM.sysWriteln("Thread #",RVMThread.getCurrentThreadSlot(),
                        ": freeing lock ",Magic.objectAsAddress(l),
                        " because we had a double-inflate");
        }
        EagerDeflateLock result = (EagerDeflateLock)
          Magic.eatCast(getLock(LockConfig.selectedThinPlan.getLockIndex(bits)));
        if (result==null ||
            result.lockedObject!=o) {
          continue; /* this is nasty.  this will happen when a lock
                       is deflated. */
        }
        free(l);
        l.unlockState();
        if (PROFILE) RVMThread.leaveLockingPath();
        return result;
      }
      if (VM.VerifyAssertions) VM._assert(l!=null);
      if (LockConfig.selectedThinPlan.attemptToMarkInflated(
            o, lockOffset, bits, l.id)) {
        l.setLockedObject(o);
        l.setOwnerId(LockConfig.selectedThinPlan.getLockOwner(bits));
        if (l.getOwnerId() != 0) {
          l.setRecursionCount(LockConfig.selectedThinPlan.getRecCount(bits));
        }
        if (PROFILE) RVMThread.leaveLockingPath();
        return l;
      }
      // contention detected, try again
    } while (true);
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


