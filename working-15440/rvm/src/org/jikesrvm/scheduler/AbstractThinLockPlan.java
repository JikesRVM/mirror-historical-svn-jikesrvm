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

public abstract class AbstractThinLockPlan implements Constants, ThinLockConstants {
  public static AbstractThinLockPlan instance;
  
  public AbstractThinLockPlan() {
    instance=this;
  }
  
  public void init() {}
  
  public void boot() {}
  
  public void lateBoot() {}
  
  /**
   * Attempt to quickly acquire the lock, and if that fails, call
   * lock().
   */
  public abstract void inlineLock(Object o,Offset lockOffset);
  public void inlineLock(Object o) {
    inlineLock(o, Magic.getObjectType(o).getThinLockOffset());
  }
  
  /**
   * Attempt to quickly release the lock, and if that fails, call
   * unlock().
   */
  public abstract void inlineUnlock(Object o,Offset lockOffset);
  public void inlineUnlock(Object o) {
    inlineUnlock(o, Magic.getObjectType(o).getThinLockOffset());
  }

  // FIXME: just have APIs in AbstractLockPlan for inflating?  if we see that
  // we have a fat lock, just call AbstractLockPlan.instance.getLock().  if
  // we see that we should be inflating, just call
  // AbstractLockPlan.instance.inflate().

  public abstract void lock(Object o, Offset lockOffset);
  public void lock(Object o) {
    lock(o, Magic.getObjectType(o).getThinLockOffset());
  }

  public abstract void unlock(Object o, Offset lockOffset);
  public void unlock(Object o) {
    unlock(o, Magic.getObjectType(o).getThinLockOffset());
  }
  
  @Unpreemptible
  public abstract boolean holdsLock(Object o,Offset lockOffset,RVMThread thread);
  @Unpreemptible
  public boolean holdsLock(Object o, RVMThread thread) {
    return holdsLock(o, Magic.getObjectType(o).getThinLockOffset(), thread);
  }
  
  @Unpreemptible
  public abstract boolean isFat(Word lockWord);
  
  /**
   * Extract the current fat lock index.  This is only valid if the lock
   * is fat.
   */
  @Unpreemptible
  public abstract int getLockIndex(Word lockWord);
  
  /**
   * Extract the current lock owner.  This is only valid if the lock is not
   * fat.
   */
  @Unpreemptible
  public abstract int getLockOwner(Word lockWord);
  
  /**
   * Extract the current lock recursion count.  This is only valid if the
   * lock is held.
   */
  @Unpreemptible
  public abstract int getRecCount(Word lockWord);
  
  /**
   * Attempt to inflate the thin lock.
   */
  @Unpreemptible
  public abstract boolean attemptToMarkInflated(Object o, Offset lockOffset,
                                                Word oldLockWord, int lockId);
  
  /**
   * Attempt to deflate the thin lock.  This makes the thin lock unlocked
   * as well.
   */
  @Unpreemptible
  public abstract boolean attemptToMarkDeflated(Object o, Offset lockOffset,
                                                Word oldLockWord);
  
  public final void markDeflated(Object o, Offset lockOffset) {
    for (;;) {
      Word bits=Magic.getWordAtOffset(o, lockOffset);
      if (attemptToMarkDeflated(o, lockOffset, bits)) {
        return;
      }
    }
  }
  
  @Inline
  public boolean lockHeader(Object obj, Offset lockOffset) {
    return false;
  }
  
  @Inline
  public void unlockHeader(Object obj, Offset lockOffset) {
  }
  
  @Inline
  @Uninterruptible
  public boolean allowHeaderCAS(Object o, Offset lockOffset) {
    return true;
  }
}


