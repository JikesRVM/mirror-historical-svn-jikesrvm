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
package org.jikesrvm.debug;

import org.jikesrvm.Callbacks;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.Scheduler;

class EventNotifier implements Callbacks.StartupMonitor,
    Callbacks.ExitMonitor, Callbacks.ThreadStartMonitor,
    Callbacks.ThreadEndMonitor, Callbacks.ClassResolvedMonitor,
    Callbacks.ClassLoadedMonitor, Callbacks.ExceptionMonitor,
    Callbacks.ExceptionCatchMonitor {
  private final RVMDebug debug;
  
  /* Event notification. */
  private EventCallbacks callBacks;

  EventNotifier(RVMDebug debug) {
    this.debug = debug;
  }
  
  public synchronized void setEventCallbacks(EventCallbacks cb) {
    if (VM.VerifyAssertions) {
      VM._assert(this.callBacks == null);
    }
    this.callBacks = cb;
  }

  public void notifyStartup() {
    RVMThread t = Scheduler.getCurrentThread();
    if (debug.eventRequest.shouldNotifyEvent(RVMDebug.EventType.VM_INIT, t)) {
      callBacks.vmInit(t);
    }
  }

  public void notifyExit(int v) {
    RVMThread t = Scheduler.getCurrentThread();
    if (debug.eventRequest.shouldNotifyEvent(RVMDebug.EventType.VM_DEATH,t)) {
      callBacks.vmDeath();
    }
  }

  public void notifyClassLoaded(RVMClass klass) {
    RVMThread thread = Scheduler.getCurrentThread();
    if (debug.eventRequest.shouldNotifyEvent(RVMDebug.EventType.VM_CLASS_LOAD,thread)) {
      callBacks.classLoad(thread, klass);
    }
  }
  
  public void notifyClassResolved(RVMClass vmClass) {
    RVMThread t = Scheduler.getCurrentThread();
    if (debug.eventRequest.shouldNotifyEvent(RVMDebug.EventType.VM_CLASS_LOAD,t)) {
     callBacks.classPrepare(Scheduler.getCurrentThread(), vmClass);
    }
  }

  /** Notification from a new thread. */
  public void notifyThreadStart(RVMThread t) {
    if (Threads.isDebuggableThread(t)) {
      debug.eventRequest.add(t);
      if (debug.eventRequest.shouldNotifyEvent(RVMDebug.EventType.VM_THREAD_START,t)) {
        callBacks.threadStart(t);
      }
    }
  }

  /** Notification from the to-be-dead thread. */
  public void notifyThreadEnd(RVMThread t) {
    if (Threads.isDebuggableThread(t)) {
      if (debug.eventRequest.shouldNotifyEvent(RVMDebug.EventType.VM_THREAD_END,t)) {
        callBacks.threadEnd(t);
      }
      debug.eventRequest.remove(t);
    }
  }

  /**
   * Notification from a thread that this thread will catch catch an
   * exception.
   */
  public void notifyExceptionCatch(Throwable e, NormalMethod sourceMethod,
      int sourceByteCodeIndex) {
    RVMThread t = Scheduler.getCurrentThread();
    if (debug.eventRequest.shouldNotifyEvent(RVMDebug.EventType.VM_EXCEPTION_CATCH,t)) {
      callBacks.exceptionCatch(t, 
          sourceMethod, sourceByteCodeIndex, e);
    }
  }
  /**
   * Notification from a thread that this thread will catch catch an
   * exception.
   */
  public void notifyException(Throwable e, NormalMethod sourceMethod,
      int sourceByteCodeIndex, NormalMethod catchMethod,
      int catchByteCodeIndex) {
    RVMThread t = Scheduler.getCurrentThread();
    if (debug.eventRequest.shouldNotifyEvent(RVMDebug.EventType.VM_EXCEPTION,t)) {
      callBacks.exception(t, 
          sourceMethod, sourceByteCodeIndex, e, 
          catchMethod, catchByteCodeIndex);
    }
  }
  public void notifyBreakpoint(RVMThread t, NormalMethod method, int bcindex) {
    if (debug.eventRequest.shouldNotifyEvent(RVMDebug.EventType.VM_BREAKPOINT, t)) {
      callBacks.breakpoint(t, method, bcindex);
    }
  }
  public void notifySingleStep(RVMThread t, NormalMethod method, int bcindex) {
    if (debug.eventRequest.shouldNotifyEvent(RVMDebug.EventType.VM_SINGLE_STEP, t)) {
      callBacks.singleStep(t, method, bcindex);
    }
  }
}

