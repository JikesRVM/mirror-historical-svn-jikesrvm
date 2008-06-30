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
package org.jikesrvm.mm.mmtk;

import org.jikesrvm.VM;
import org.jikesrvm.Services;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

import static org.jikesrvm.runtime.SysCall.sysCall;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;

import org.mmtk.utility.Log;


/**
 * Really dump implementation of MMTk locks.
 */
@Uninterruptible public class Lock extends org.mmtk.vm.Lock {

  // Core Instance fields
  private String name;        // logical name of lock
  private final int id;       // lock id (based on a non-resetting counter)
  private static int lockCount;
  
  private Word lock;
  @Entrypoint
  private int lockInit; // 0 = not inited, 1 = inited, 2 = initializing

  // Diagnosis Instance fields
  private RVMThread thread;   // if locked, who locked it?
  private int where = -1;     // how far along has the lock owner progressed?

  public Lock(String name) {
    this();
    this.name = name;
  }

  public Lock() {
    id = lockCount++;
  }

  public void setName(String str) {
    name = str;
  }
  
  private void init() {
    if (VM.VerifyAssertions) VM._assert(VM.runningVM);
    Offset offset=Entrypoints.lockInitField.getOffset();
    for (;;) {
      int status=Magic.prepareInt(this,offset);
      if (status==1) {
	break;
      } else if (status==0 &&
		 Magic.attemptInt(this,offset,status,2)) {
	lock = sysCall.sysPthreadMutexCreate();
	Magic.sync();
	lockInit=1;
      }
    }
    Magic.sync();
  }

  public void acquire() {
    init();
    sysCall.sysPthreadMutexLock(lock);
    thread = RVMThread.getCurrentThread();
  }

  public void check(int w) {
    if (VM.VerifyAssertions) VM._assert(RVMThread.getCurrentThread() == thread);
    where = w;
  }

  public void release() {
    where=-1;
    thread=null;
    sysCall.sysPthreadMutexUnlock(lock);
  }
}
/*
Local Variables:
   c-basic-offset: 2
End:
*/
