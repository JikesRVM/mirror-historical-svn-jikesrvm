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
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/**
 * Abstract base-class for the global state of the current lock implementation.
 */
@Uninterruptible
public abstract class AbstractLockPlan implements Constants, ThinLockConstants {
  public static AbstractLockPlan instance;
  
  public AbstractLockPlan() {
    instance=this;
  }
  
  public abstract void init();
  
  public abstract void boot();
  
  public abstract void inlineLock(Object o,Offset lockOffset);
  public void inlineLock(Object o) {
    inlineLock(o, Magic.getObjectType(o).getThinLockOffset());
  }
  
  public abstract void inlineUnlock(Object o,Offset lockOffset);
  public void inlineUnlock(Object o) {
    inlineUnlock(o, Magic.getObjectType(o).getThinLockOffset());
  }
  
  public abstract void lock(Object o,Offset lockOffset);
  public void lock(Object o) {
    lock(o, Magic.getObjectType(o).getThinLockOffset());
  }

  public abstract void unlock(Object o,Offset lockOffset);
  public void unlock(Object o) {
    unlock(o, Magic.getObjectType(o).getThinLockOffset());
  }
  
  public abstract boolean holdsLock(Object o,Offset lockOffset,RVMThread thread);
  public boolean holdsLock(Object o, RVMThread thread) {
    return holdsLock(o, Magic.getObjectType(o).getThinLockOffset(), thread);
  }
  
  public abstract AbstractLock getHeavyLock(Object o,Offset lockOffset,boolean create);
  public AbstractLock getHeavyLock(Object o, boolean create) {
    return getHeavyLock(o, Magic.getObjectType(o).getThinLockOffset(), create);
  }
  
  public abstract void waitImpl(Object o, boolean hasTimeout, long whenWakeupNanos);
  public abstract void notify(Object o);
  public abstract void notifyAll(Object o);
  
  /** Upper bound on the number of locks; typically this is only used for
      assertions. */
  public abstract int numLocks();
  public abstract AbstractLock getLock(int id);
  
  public abstract int countLocksHeldByThread(int id);
  
  public void returnLock(AbstractLock l) {
    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }
}


