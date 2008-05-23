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

package org.jikesrvm.tuningfork;

import org.jikesrvm.VM;
import org.jikesrvm.runtime.VM_Time;
import org.vmmagic.pragma.NoInline;

import com.ibm.tuningfork.tracegen.chunk.EventChunk;
import com.ibm.tuningfork.tracegen.types.EventType;

/**
 * <p>A Feedlet is a entity that is a unit of trace generation
 * for TuningFork.  In Jikes RVM, a VM_Feedlet is typically
 * associated with a VM_Thread.</p>
 *
 * <p>Note an important assumption that a VM_Feedlet will only
 * be used by a single thread at a time.  All operations are
 * unsynchronized.  This invariant is usually met because only
 * the VM_Thread to which it is attached is allowed to perform
 * addEvent operations on the VM_Feedlet.  If a VM_Feedlet is attached
 * to something other than a VM_Thread, then this invariant must be
 * established via external synchronization.</p>
 */
public class VM_Feedlet {
  private static final boolean CHECK_TYPES = VM.VerifyAssertions;

  private final VM_Engine engine;
  private final int feedletIndex;
  private int sequenceNumber;
  private EventChunk events;

  /**
   * Create a new VM_Feedlet.
   * This method is only meant to be called from VM_Engine.
   * @param eng the VM_Engine instance to which this feedlet is attached.
   * @param feedletIndex,
   */
  VM_Feedlet(VM_Engine engine, int feedletIndex) {
    this.engine = engine;
    this.feedletIndex = feedletIndex;
  }

  /**
   * @return the feedlet's index
   */
  final int getFeedletIndex() {
    return feedletIndex;
  }

  /**
   * @deprecated This is not a safe API
   * @return
   */
  final EventChunk stealEvents() {
    events.close();
      return events;
  }

  /**
   * Add an event to the feedlet's generated event stream
   * @param et The type of event to add
   */
  public void addEvent(EventType et) {
    if (CHECK_TYPES && !checkTypes(et, 0, 0, 0, 0)) return;

    long timeStamp = getTimeStamp();
    while (true) {
      if (events == null && !acquireEventChunk()) {
        return; /* failure */
      }
      if (events.addEvent(timeStamp, et)) {
        return; /* success */
      }
      flushEventChunk(); /* events is full or stale; flush and try again */
    }
  }

  /**
   * Add an event to the feedlet's generated event stream
   * @param et The type of event to add
   * @param ival1 The first int data value
   */
  public void addEvent(EventType et, int ival1) {
    if (CHECK_TYPES && !checkTypes(et, 1, 0, 0, 0)) return;

    long timeStamp = getTimeStamp();
    while (true) {
      if (events == null && !acquireEventChunk()) {
        return; /* failure */
      }
      if (events.addEvent(timeStamp, et, ival1)) {
        return; /* success */
      }
      flushEventChunk(); /* events is full or stale; flush and try again */
    }
  }


  @NoInline
  private boolean checkTypes(EventType et, int numInts, int numLongs, int numDoubles, int numStrings) {
    if (et.getNumberOfInts() != numInts ||
        et.getNumberOfLongs() != numLongs ||
        et.getNumberOfDoubles() != numDoubles ||
        et.getNumberOfStrings() != numStrings) {
      VM.sysWriteln("VM_Feedlet: Dropping addEvent of ill-typed event ", et.getName());
      return false;
    } else {
      return true;
    }
  }

  private long getTimeStamp() {
    return VM_Time.nanoTime();
  }

  @NoInline
  private boolean acquireEventChunk() {
    if (VM.VerifyAssertions) VM._assert(events == null);
    events = engine.getEventChunk();
    if (events == null) {
      // TODO: here is where we would need to record in the VM_Feedlet's
      //       state that we had to drop an event.  We would then later
      //       (when we got an event chunk back again) emit an event
      //       to indicate the number of events that were dropped due to
      //       an inability to obtain an event chunk.
      return false;
    }
    events.reset(feedletIndex, sequenceNumber++);
    return true;
  }

  @NoInline
  private void flushEventChunk() {
    events.close();
    engine.returnFullEventChunk(events);
    events = null;
  }

}
