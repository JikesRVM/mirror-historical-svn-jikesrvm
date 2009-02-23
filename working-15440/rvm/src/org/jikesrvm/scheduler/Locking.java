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
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.NoNullCheck;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/**
 * Public entrypoints for locking
 */
public final class Locking implements ThinLockConstants {
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
  @Entrypoint
  @NoNullCheck
  static void inlineLock(Object o, Offset lockOffset) {
    LockConfig.selectedPlan.inlineLock(o,lockOffset);
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
  @Entrypoint
  @NoNullCheck
  static void inlineUnlock(Object o, Offset lockOffset) {
    LockConfig.selectedPlan.inlineUnlock(o,lockOffset);
  }

  /**
   * Obtains a lock on the indicated object.  Light-weight locking
   * sequence for the prologue of synchronized methods and for the
   * <code>monitorenter</code> bytecode.
   *
   * @param o the object to be locked
   * @param lockOffset the offset of the thin lock word in the object.
   */
  @NoInline
  @NoNullCheck
  public static void lock(Object o, Offset lockOffset) {
    LockConfig.selectedPlan.lock(o,lockOffset);
  }

  /**
   * Releases the lock on the indicated object.   Light-weight unlocking
   * sequence for the epilogue of synchronized methods and for the
   * <code>monitorexit</code> bytecode.
   *
   * @param o the object to be locked
   * @param lockOffset the offset of the thin lock word in the object.
   */
  @NoInline
  @NoNullCheck
  public static void unlock(Object o, Offset lockOffset) {
    LockConfig.selectedPlan.unlock(o,lockOffset);
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
  public static boolean holdsLock(Object obj, Offset lockOffset, RVMThread thread) {
    return LockConfig.selectedPlan.holdsLock(obj,lockOffset,thread);
  }

  /**
   * Obtains the heavy-weight lock, if there is one, associated with the
   * indicated object.  Returns <code>null</code>, if there is no
   * heavy-weight lock associated with the object.
   *
   * @param o the object from which a lock is desired
   * @param lockOffset the offset of the thin lock word in the object.
   * @param create if true, create heavy lock if none found
   * @return the heavy-weight lock on the object (if any)
   */
  @NoNullCheck
  public static AbstractLock getHeavyLock(Object o, 
                                          Offset lockOffset, 
                                          boolean create) {
    return LockConfig.selectedPlan.getHeavyLock(o,lockOffset,create);
  }
}



