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

/**
 * Abstract baseclass for all locks.
 */
public abstract class AbstractLock implements Constants {
  
  public abstract boolean isActive();
  
  public abstract int getLockId();
  
  /**
   * Heavy lock acquisition.  Note that this may fail spuriously (so you should
   * spin) or, in particular, if the object for which you're trying to acquire
   * the lock is different from the object with which the lock is associated.
   * @param o The object for which you'd like to acquire this lock.  This lock
   *          is associated with some object - but the lock-to-object mapping
   *          may, in general, change asynchronously.  This parameter indicates
   *          for which object you'd like to acquire the lock, so that the
   *          implementation may back out if it realizes that it's associated
   *          with the wrong object.
   * @return true if you've successfully acquired the lock for the given object
   *         or false otherwise.  This method may spuriously (read: randomly,
   *         without cause or warning) return false if it chooses to.
   */
  public abstract boolean lockHeavy(Object o);
  
  public abstract void unlockHeavy();
  
  /** Set the thread that owns ("holds") the lock. */
  protected abstract void setOwnerId(int id);
  
  @Unpreemptible
  public abstract int getOwnerId();
  
  public abstract int getRecursionCount();
  
  @Unpreemptible
  public abstract boolean holdsLock(Object o, RVMThread thread);
  
  /**
   * Get the object currently associated with this lock.  This may change
   * asynchronously and without warning, so you should not rely on this method
   * except if you have implementation-specific knowledge, if you're just
   * using this method for debugging, or if this method's approximate result
   * is useful for your algorithm.  All that this method guarantees is that
   * the returned object was at some point in time associated with this lock.
   * One example use is to have the spin loop around lockHeavy() first check,
   * using this method, if the locked object is the one it was expecting.  If
   * not, it can just reload and try again; in some situations this may be
   * better than calling lockHeavy() directly, though the implementation gives
   * no guarantees in this regard (for example: lockHeavy() may already do this
   * fast check).
   */
  public abstract Object getLockedObject();
  
  protected abstract void dumpBlockedThreads();
  protected abstract void dumpWaitingThreads();
  
  protected void dumpImplementationSpecific() {}
  
  public void dump() {
    if (!isActive()) {
      return;
    }
    VM.sysWrite("Lock ");
    VM.sysWriteInt(getLockId());
    VM.sysWrite(":\n");
    VM.sysWrite(" lockedObject: ");
    VM.sysWriteHex(Magic.objectAsAddress(getLockedObject()));
    VM.sysWrite("   thin lock = ");
    VM.sysWriteHex(Magic.objectAsAddress(getLockedObject()).loadAddress(ObjectModel.defaultThinLockOffset()));
    VM.sysWrite(" object type = ");
    VM.sysWrite(Magic.getObjectType(getLockedObject()).getDescriptor());
    VM.sysWriteln();

    VM.sysWrite(" ownerId: ");
    VM.sysWriteInt(getOwnerId());
    VM.sysWrite(" recursionCount: ");
    VM.sysWriteInt(getRecursionCount());
    VM.sysWriteln();
    dumpBlockedThreads();
    dumpWaitingThreads();
    dumpImplementationSpecific();
  }
}

