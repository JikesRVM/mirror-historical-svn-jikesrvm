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
import org.jikesrvm.scheduler.RVMThread;

/**
 * A Debugger state keeper.  
 */
public class RVMDebug {

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
  private static RVMDebug debug;

  public static void boot() {
    debug = new RVMDebug();

    BreakpointsImpl.boot();
    JikesRVMJDWP.boot();
  }

  public static RVMDebug getRVMDebug() {
    if (VM.VerifyAssertions) {
      VM._assert(debug != null);
    }
    return debug;
  }

  final RVMDebugState eventRequest = new RVMDebugState();

  final BreakpointList breakPoints = new BreakpointList();

  final EventNotifier eventNotifier;

  /** Only one instance. */
  private RVMDebug() {
    eventNotifier = new EventNotifier(this);
    Callbacks.addStartupMonitor(eventNotifier);
    Callbacks.addExitMonitor(eventNotifier);
    Callbacks.addThreadStartedMonitor(eventNotifier);
    Callbacks.addThreadEndMonitor(eventNotifier);
    Callbacks.addClassResolvedMonitor(eventNotifier);
    Callbacks.addClassLoadedMonitor(eventNotifier);
    Callbacks.addExceptionCatchMonitor(eventNotifier);
  }

  /**
   * Set event receiver.
   * 
   * @param callBacks the callback.
   */
  public void setEventCallbacks(EventCallbacks callBacks) {
    eventNotifier.setEventCallbacks(callBacks);
  }

  public void setBreakPoint(NormalMethod method, int bcindex) {
    breakPoints.setBreakPoint(method, bcindex);
  }

  public void clearBreakPoint(NormalMethod method, int bcindex) {
    breakPoints.clearBreakPoint(method, bcindex);
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
    eventRequest.setEventNotificationMode(eventType, enabled, thread);
  }
}