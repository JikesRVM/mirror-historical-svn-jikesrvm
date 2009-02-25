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

public abstract class CommonThinLockPlan extends AbstractThinLockPlan {
  public static CommonThinLockPlan instance;
  
  public CommonThinLockPlan() {
    instance=this;
  }
  
  public void init() {
    if (false) {
      VM.sysWriteln("lock count mask: ",TL_LOCK_COUNT_MASK);
      VM.sysWriteln("thread id mask: ",TL_THREAD_ID_MASK);
      VM.sysWriteln("lock id mask: ",TL_LOCK_ID_MASK);
      VM.sysWriteln("fat lock mask: ",TL_FAT_LOCK_MASK);
      VM.sysWriteln("unlock mask: ",TL_UNLOCK_MASK);
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
}

