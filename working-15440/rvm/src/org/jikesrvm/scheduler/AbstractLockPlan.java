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
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/**
 * Abstract base-class for the global state of the current lock implementation.
 */
public abstract class AbstractLockPlan implements Constants {
  public static AbstractLockPlan instance;
  
  public AbstractLockPlan() {
    instance=this;
  }
  
  public abstract void init();
  
  public abstract void boot();
  
  public abstract void lateBoot();
  
  public abstract AbstractLock inflate(Object o, Offset lockOffset);
  
  public AbstractLock getLock(Object o, Offset lockOffset) {
    Word bits=Magic.getWordAtOffset(o,lockOffset);
    if (LockConfig.selectedThinPlan.isFat(bits)) {
      int idx=LockConfig.selectedThinPlan.getLockIndex(bits);
      if (idx!=0) {
        return LockConfig.selectedPlan.getLock(idx);
      }
    }
    return null;
  }
  
  public boolean inflateAndLock(Object o, Offset lockOffset) {
    LockConfig.Selected l=(LockConfig.Selected)Magic.eatCast(inflate(o, lockOffset));
    if (l!=null) {
      return l.lockHeavy(o);
    } else {
      return false;
    }
  }
  
  public abstract void waitImpl(Object o, boolean hasTimeout, long whenWakeupNanos);

  public abstract void notify(Object o);
  public abstract void notifyAll(Object o);
  
  /** Upper bound on the number of locks; typically this is only used for
      assertions. */
  @Unpreemptible
  public abstract int numLocks();
  @Unpreemptible
  public abstract AbstractLock getLock(int id);
  
  public abstract int countLocksHeldByThread(int id);
  
  protected boolean tryToDeflateSomeLocks() {
    return false; /* by default implementations cannot spontaneously deflate
                     some locks. */
  }

  @Uninterruptible
  public void dumpLocks() {}
}


