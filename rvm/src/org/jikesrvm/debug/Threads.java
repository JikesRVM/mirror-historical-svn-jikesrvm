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

import java.util.List;

import org.jikesrvm.VM;
import org.jikesrvm.debug.RVMDebug.EventType;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.Scheduler;

/**
 * JikesRVM's thread information.
 */
public final class Threads {

  /**
   * Run agent thread.
   * 
   * @param thread The agent thread.
   */
  public static void setAgentThread(RVMThread thread) {
    RVMDebug.getRVMDebug().eventRequest.addAgentThreadImpl(thread);
  }

  /**
   * Check wheather or not an agent thread.
   * 
   * @param thread The thread.
   * @return true if the agent thread, and false otherwise.
   */
  public static boolean isAgentThread(RVMThread thread) {
    return RVMDebug.getRVMDebug().eventRequest.isAgentThreadImpl(thread);
  }
  
  public static RVMThread[] getAllThreads() {
    return RVMDebug.getRVMDebug().eventRequest.getAllThreadsImpl();
  }

  static boolean isDebuggableThread(RVMThread thread) {
    final boolean rValue = !thread.isBootThread() && !thread.isDebuggerThread()
        && !thread.isGCThread() && !thread.isSystemThread()
        && !thread.isIdleThread();
    return rValue;
  }

  public static void suspendThread(RVMThread thread) {
    Thread jthread = thread.getJavaLangThread();
    if (VM.VerifyAssertions) {
      VM._assert(jthread != null);
    }
    synchronized (jthread) { 
      thread.suspend(); 
    }
  }
  
  public static void suspendThreadList(List<RVMThread> list) {
    boolean suspendCurrentThread = false;
    for(RVMThread t : list) {
      if (t == Scheduler.getCurrentThread() ) {
        suspendCurrentThread = true;
      } else {
        suspendThread(t);
      }
    }
    if (suspendCurrentThread) {
      RVMThread myThread = Scheduler.getCurrentThread();
      suspendThread(myThread);
    }
  }
  
  public static void resumeThread(RVMThread t) {
    if (VM.VerifyAssertions) {
      VM._assert(t != Scheduler.getCurrentThread());
    }
    Thread jthread = t.getJavaLangThread();
    synchronized (jthread) {
      t.resume();
    }
  }
  
  public static void resumeThreadList(List<RVMThread> list) {
     for(RVMThread t : list) {
       resumeThread(t);
     }
  }
}
