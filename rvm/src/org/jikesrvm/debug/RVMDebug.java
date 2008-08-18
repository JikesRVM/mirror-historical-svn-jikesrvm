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
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.Scheduler;
import org.jikesrvm.util.HashMapRVM;
import org.jikesrvm.util.LinkedListRVM;

/**
 * JikesRVM's debugging interface.
 */
public class RVMDebug implements
  Callbacks.StartupMonitor, Callbacks.ExitMonitor,
  Callbacks.ThreadStartMonitor, Callbacks.ThreadEndMonitor,
  Callbacks.ClassResolvedMonitor, Callbacks.ClassLoadedMonitor, 
  Callbacks.ExceptionMonitor,Callbacks.ExceptionCatchMonitor,
  Callbacks.MethodCompileCompleteMonitor {

  public enum EventType {
    VM_INIT,
    VM_START,
    VM_DEATH,
    VM_THREAD_START,
    VM_THREAD_END,
    VM_CLASS_LOAD,
    VM_CLASS_PREPARE,
    VM_BREAKPOINT,
    VM_SINGLE_STEP,
    VM_EXCEPTION,
    VM_EXCEPTION_CATCH,
  }

  private class EventStatus {
    private boolean enabled;
  }

  private static RVMDebug debug;

  public static void boot() {
    debug = new RVMDebug();
  }

  public static RVMDebug getRVMDebug() {
    if (VM.VerifyAssertions) {
      VM._assert(debug != null);
    }
    return debug;
  }
  
  /* Event notification. */
  private EventCallbacks callBacks;
  private HashMapRVM<EventType,EventStatus> globalEventEnabled 
    = new HashMapRVM<EventType,EventStatus>();
  private HashMapRVM<EventType,LinkedListRVM<RVMThread>> threadEventEnabled
    = new HashMapRVM<EventType,LinkedListRVM<RVMThread>>();
  
  Threads threads = new Threads();

  /** Only one instance. */
  private RVMDebug() {
    for(EventType etype : EventType.values() ) {
      globalEventEnabled.put(etype, new EventStatus());
      threadEventEnabled.put(etype, new LinkedListRVM<RVMThread>());
    }
    Callbacks.addStartupMonitor(this);
    Callbacks.addExitMonitor(this);
    Callbacks.addThreadStartedMonitor(this);
    Callbacks.addThreadEndMonitor(this);
    Callbacks.addClassResolvedMonitor(this);
    Callbacks.addClassLoadedMonitor(this);
    Callbacks.addExceptionCatchMonitor(this);
    Callbacks.addMethodCompileCompleteMonitor(this);
  }



  /**
   * Set event notification mode. null thread value means all the threads.
   * 
   * @param eventType The event type.
   * @param mode The event mode.
   * @param thread The thread.
   */
  public void setEventNotificationMode(EventType eventType, boolean enabled,
      RVMThread thread) {
    if (thread == null) {
      globalEventEnabled.get(eventType).enabled = enabled;
    } else {
      LinkedListRVM<RVMThread> l =threadEventEnabled.get(eventType);
      boolean oldEnabled = l.contains(thread);
      if (oldEnabled && !enabled) {
        l.remove(thread);
      } else if (!oldEnabled && enabled) {
        l.add(thread); 
      }
    }

    switch(eventType) {
    case VM_SINGLE_STEP:
      if (VM.VerifyAssertions) {
        VM._assert(thread != null, "global single step is not supported.");
      }
      if (enabled) {
        SingleStep.setStepBreakpoint(thread);
      } else {
        SingleStep.clearStepping(thread);
      }
      break;
    default:
      break;
    }
  }

  /**
   * Set event receiver.
   * 
   * @param callBacks the callback.
   */
  public void setEventCallbacks(EventCallbacks callBacks) {
    if (VM.VerifyAssertions) {
      VM._assert(this.callBacks == null);
    }
    this.callBacks = callBacks;
  }


  /**
   * @param eventType The event type.
   * @param t The thread.
   */
  private boolean shouldNotifyEvent(EventType eventType, RVMThread t) {
    if (VM.VerifyAssertions) {
      VM._assert(t != null);
    }
    if (Threads.isAgentThread(t)) {return false;} // never report agent thread event.
    
    boolean enabled;
    switch(eventType) {
    // The thread filter can not be applied to these events.
    case VM_INIT:
    case VM_DEATH:
    case VM_THREAD_START:
      enabled = globalEventEnabled.get(eventType).enabled;
      break;
    default:
      enabled = globalEventEnabled.get(eventType).enabled
      || (t != null && threadEventEnabled.get(eventType).contains(t));
      break;
    }
    return enabled;
  }

  public void notifyStartup() {
    RVMThread t = Scheduler.getCurrentThread();
    if (shouldNotifyEvent(EventType.VM_INIT, t)) {
      callBacks.vmInit(t);
    }
  }

  public void notifyExit(int v) {
    RVMThread t = Scheduler.getCurrentThread();
    if (shouldNotifyEvent(EventType.VM_DEATH,t)) {
      callBacks.vmDeath();
    }
  }

  public void notifyClassLoaded(RVMClass klass) {
    RVMThread thread = Scheduler.getCurrentThread();
    if (shouldNotifyEvent(EventType.VM_CLASS_LOAD,thread)) {
      callBacks.classLoad(thread, klass);
    }
  }
  
  public void notifyClassResolved(RVMClass vmClass) {
    RVMThread t = Scheduler.getCurrentThread();
    if (shouldNotifyEvent(EventType.VM_CLASS_LOAD,t)) {
     callBacks.classPrepare(Scheduler.getCurrentThread(), vmClass);
    }
  }

  /** Notification from a new thread. */
  public void notifyThreadStart(RVMThread t) {
    if (Threads.isDebuggableThread(t)) {
      threads.add(t);
      if (shouldNotifyEvent(EventType.VM_THREAD_START,t)) {
        callBacks.threadStart(t);
      }
    }
  }

  /** Notification from the to-be-dead thread. */
  public void notifyThreadEnd(RVMThread t) {
    if (Threads.isDebuggableThread(t)) {
      threads.remove(t);
      if (shouldNotifyEvent(EventType.VM_THREAD_END,t)) {
        callBacks.threadEnd(t);
      }
    }
  }

  /**
   * Notification from a thread that this thread will catch catch an
   * exception.
   */
  public void notifyExceptionCatch(Throwable e, NormalMethod sourceMethod,
      int sourceByteCodeIndex) {
    RVMThread t = Scheduler.getCurrentThread();
    if (shouldNotifyEvent(EventType.VM_EXCEPTION_CATCH,t)) {
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
    if (shouldNotifyEvent(EventType.VM_EXCEPTION,t)) {
      callBacks.exception(t, 
          sourceMethod, sourceByteCodeIndex, e, 
          catchMethod, catchByteCodeIndex);
    }
  }
  public void notifyBreakpoint(RVMThread t, NormalMethod method, int bcindex) {
    callBacks.breakpoint(t, method, bcindex);
  }
  public void notifyMethodCompileComplete(CompiledMethod cm) {
    BreakpointManager.checkBreakpoint(cm);
  }
}
