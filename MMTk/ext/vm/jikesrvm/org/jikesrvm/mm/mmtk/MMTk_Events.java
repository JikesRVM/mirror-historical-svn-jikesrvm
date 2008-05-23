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
import org.jikesrvm.tuningfork.VM_Engine;
import org.mmtk.policy.Space;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;

import com.ibm.tuningfork.tracegen.types.EventAttribute;
import com.ibm.tuningfork.tracegen.types.EventType;
import com.ibm.tuningfork.tracegen.types.ScalarType;

/**
 * Temporary interface to allow MMTk to generate TuningFork events.
 */
@Uninterruptible
public class MMTk_Events extends org.mmtk.vm.MMTk_Events {
  public static MMTk_Events events;

  public EventType gcStart;
  public EventType gcStop;
  public EventType pageAcquire;
  public EventType pageRelease;
  private boolean initialized;

  private VM_Engine engine;

  public MMTk_Events() {
    events = this;
    initialized = false;
  }

  @Interruptible
  public void initialize(VM_Engine engine) {
    this.engine = engine;

    /* Define events used by the MMTk subsystem */
    gcStart = engine.defineEvent("GC Start", "Start of a GC cycle",
                          new EventAttribute("Reason","Encoded reason for GC",ScalarType.INT));
    gcStop = engine.defineEvent("GC Stop", "End of a GC Cycle");
    pageAcquire = engine.defineEvent("Page Acquire", "A space has acquired one or more new pages",
                              new EventAttribute[] {
                                  new EventAttribute("Space", "Space ID", ScalarType.INT),
                                  new EventAttribute("Start Address", "Start address of range of released pages", ScalarType.INT),
                                  new EventAttribute("Num Pages", "Number of pages released", ScalarType.INT)});
    pageRelease = engine.defineEvent("Page Release", "A space has released one or more of its pages",
                              new EventAttribute[] {
                                  new EventAttribute("Space", "Space ID", ScalarType.INT),
                                  new EventAttribute("Start Address", "Start address of range of released pages", ScalarType.INT),
                                  new EventAttribute("Num Pages", "Number of pages released", ScalarType.INT)});

    initialized = true;
  }

  public void tracePageAcquired(Space space, Address startAddress, int numPages) {
    if (!initialized) {
      VM.sysWriteln("Ignoring page acquire event that occured before engine initialized");
      return;
    }
    engine.activeFeedlet.addEvent(pageAcquire, space.getIndex(), startAddress.toInt(), numPages);
  }

  public void tracePageReleased(Space space, Address startAddress, int numPages) {
    if (!initialized) {
      VM.sysWriteln("Ignoring page release event that occured before engine initialized");
      return;
    }
    engine.activeFeedlet.addEvent(pageRelease, space.getIndex(), startAddress.toInt(), numPages);
  }
}
