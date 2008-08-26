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

import org.jikesrvm.VM;
import org.jikesrvm.ArchitectureSpecific.Registers;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.util.HashMapRVM;
import org.jikesrvm.util.LinkedListRVM;

class RVMDebugState {

  private static class EventStatus {
    private boolean enabled;
  }

  private HashMapRVM<RVMDebug.EventType, EventStatus> globalEventEnabled = 
    new HashMapRVM<RVMDebug.EventType, EventStatus>();

  private HashMapRVM<RVMDebug.EventType,LinkedListRVM<RVMThread>> threadEventEnabled = 
    new HashMapRVM<RVMDebug.EventType,LinkedListRVM<RVMThread>>();

  private LinkedListRVM<RVMThread> activeThreadList = 
    new LinkedListRVM<RVMThread>(); 
  
  private LinkedListRVM<RVMThread> agentThreads = 
    new LinkedListRVM<RVMThread>();

  RVMDebugState() {
    for(RVMDebug.EventType etype : RVMDebug.EventType.values() ) {
      globalEventEnabled.put(etype, new EventStatus());
      threadEventEnabled.put(etype, new LinkedListRVM<RVMThread>());
    }
  }
  synchronized void addAgentThreadImpl(RVMThread thread) {
    if (!agentThreads.contains(thread)) {
      agentThreads.add(thread);
    }
    if (VM.VerifyAssertions) {
      VM._assert(activeThreadList.contains(thread));
    }
  }

  synchronized boolean isAgentThreadImpl(RVMThread thread) {
    return agentThreads.contains(thread);
  }

  synchronized void add(RVMThread thread) {
    if (VM.VerifyAssertions) {
      VM._assert(!activeThreadList.contains(thread));
    }
    activeThreadList.add(thread);
  }

  synchronized void remove(RVMThread thread) {
    if (VM.VerifyAssertions) {
      VM._assert(activeThreadList.contains(thread));
    }
    for(RVMDebug.EventType type : RVMDebug.EventType.values()) {
      LinkedListRVM<RVMThread> l = threadEventEnabled.get(type);
      if (l != null) { l.remove(thread); }
    }
    activeThreadList.remove(thread);
    agentThreads.remove(thread);
  }

  /**
   * Set event notification mode. null thread value means all the threads.
   * 
   * @param RVMDebug.EventType The event type.
   * @param mode The event mode.
   * @param thread The thread.
   */
  public synchronized void setEventNotificationMode(RVMDebug.EventType eventType, boolean enabled,
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
  }

  /**
   * @param RVMDebug.EventType The event type.
   * @param t The thread.
   */
  synchronized boolean shouldNotifyEvent(RVMDebug.EventType eventType, RVMThread t) {
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

  public synchronized RVMThread[] getAllThreadsImpl() {
    int size = activeThreadList.size();
    RVMThread[] threads = new RVMThread[size];
    int i = 0;
    for(RVMThread t : activeThreadList) {
      threads[i++] = t;
    }
    return threads;
  }  
}
