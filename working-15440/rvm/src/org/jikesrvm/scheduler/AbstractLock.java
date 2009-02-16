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
public abstract class AbstractLock implements Constants, ThinLockConstants {
  
  public abstract boolean isActive();
  
  public abstract int getLockId();
  
  public abstract boolean lockHeavy(Object o);
  
  public abstract void unlockHeavy();
  
  public abstract void setOwnerId(int id);
  
  public abstract int getOwnerId();
  
  public abstract void setRecursionCount(int c);
  
  public abstract int getRecursionCount();
  
  public abstract Object getLockedObject();
  
  public abstract void setLockedObject(Object o);
  
  public abstract void dumpBlockedThreads();
  public abstract void dumpWaitingThreads();
  
  public void dumpImplementationSpecific() {}
  
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
    VM.sysWrite(" (");
    VM.sysWriteInt(getOwnerId() >>> ThinLockConstants.TL_THREAD_ID_SHIFT);
    VM.sysWrite(") recursionCount: ");
    VM.sysWriteInt(getRecursionCount());
    VM.sysWriteln();
    dumpBlockedThreads();
    dumpWaitingThreads();
    dumpImplementationSpecific();
  }
}

