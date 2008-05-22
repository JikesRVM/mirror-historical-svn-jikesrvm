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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.jikesrvm.VM;
import org.jikesrvm.VM_Callbacks;
import org.jikesrvm.VM_Configuration;
import org.jikesrvm.VM_Callbacks.ExitMonitor;
import org.jikesrvm.runtime.VM_Time;

import com.ibm.tuningfork.tracegen.chunk.EventChunk;
import com.ibm.tuningfork.tracegen.chunk.EventTypeChunk;
import com.ibm.tuningfork.tracegen.chunk.EventTypeSpaceChunk;
import com.ibm.tuningfork.tracegen.chunk.FeedHeaderChunk;
import com.ibm.tuningfork.tracegen.chunk.FeedletChunk;
import com.ibm.tuningfork.tracegen.chunk.PropertyTableChunk;
import com.ibm.tuningfork.tracegen.chunk.RawChunk;
import com.ibm.tuningfork.tracegen.types.EventAttribute;
import com.ibm.tuningfork.tracegen.types.EventType;
import com.ibm.tuningfork.tracegen.types.EventTypeSpaceVersion;
import com.ibm.tuningfork.tracegen.types.ScalarType;

/**
 * TuningFork Trace Engine (roughly functionally equivalent to the
 * Logger classes in the TuningFork JavaTraceGenerationLibrary).
 *
 */
public class VM_Engine {

  public enum State { STARTING_UP, RUNNING_FILE, SHUTTING_DOWN, SHUT_DOWN };

  public static final VM_Engine engine = new VM_Engine();
  private static final int IO_INTERVAL_MS = 100;

  private VM_ChunkQueue unwrittenMetaChunks = new VM_ChunkQueue();
  private VM_ChunkQueue unwrittenEventChunks = new VM_ChunkQueue();
  private VM_ChunkQueue availableEventChunks = new VM_ChunkQueue();

  private FeedletChunk activeFeedletChunk = new FeedletChunk();
  private EventTypeChunk activeEventTypeChunk = new EventTypeChunk();
  private PropertyTableChunk activePropertyTableChunk = new PropertyTableChunk();

  private OutputStream outputStream;
  private State state;

  private VM_Engine() {
    for (int i=0; i<32; i++) {
      availableEventChunks.enqueue(new EventChunk(false));
    }
  }


  public void earlyStageBooting() {
    // TODO: make all of this conditional on command line argument.

    unwrittenMetaChunks.enqueue(new FeedHeaderChunk());
    unwrittenMetaChunks.enqueue(new EventTypeSpaceChunk(new EventTypeSpaceVersion("org.jikesrvm", 1)));
    unwrittenMetaChunks.enqueue(new VM_SpaceDescriptorChunk());

    state = State.STARTING_UP;
  }

  public void fullyBootedVM() {
    File f = new File("rvm.trace");
    try {
      outputStream = new FileOutputStream(f);
    } catch (FileNotFoundException e) {
      VM.sysWriteln("Unable to open trace file "+f.getAbsolutePath());
      VM.sysWriteln("continuing, but TuningFork trace generation is disabled.");
      state = State.SHUT_DOWN;
      return;
    }

    createDaemonThreads();
    writeInitialProperites();
   }


  /**
   * Put some basic properties about this vm build & current execution into the feed.
   */
  private void writeInitialProperites() {
    addProperty("rvm version", VM_Configuration.RVM_VERSION_STRING);
    addProperty("rvm config", VM_Configuration.RVM_CONFIGURATION);
    addProperty("Tick Frequency", "1000000000"); /* ticks ia 1 nanosecond */
  }


  /*
   * Support for defining EventTypes
   */

  /**
   * Define an EventType
   * @param name The name to give the event
   * @param description A human readable description of the event for display in the TuningFork UI.
   */
  public EventType defineEvent(String name, String description) {
    EventType result = new EventType(name, description);
    internalDefineEvent(result);
    return result;
  }

  /**
   * Define an EventType
   * @param name The name to give the event
   * @param description A human readable description of the event for display in the TuningFork UI.
   * @param attribute Description of the event's single data value
   */
  public EventType defineEvent(String name, String description, EventAttribute attribute) {
    EventType result = new EventType(name, description, attribute);
    internalDefineEvent(result);
    return result;
  }

  /**
   * Define an EventType
   * @param name The name to give the event
   * @param description A human readable description of the event for display in the TuningFork UI.
   * @param attributes Descriptions of the event's data values
   */
  public EventType defineEvent(String name, String description, EventAttribute[] attributes) {
    EventType result = new EventType(name, description, attributes);
    internalDefineEvent(result);
    return result;
  }

  private synchronized void internalDefineEvent(EventType et) {
    if (activeEventTypeChunk == null) {
      activeEventTypeChunk = new EventTypeChunk();
    }
    if (!activeEventTypeChunk.add(et)) {
      activeEventTypeChunk.close();
      unwrittenMetaChunks.enqueue(activeEventTypeChunk);
      activeEventTypeChunk = new EventTypeChunk();
      if (!activeEventTypeChunk.add(et)) {
        if (VM.VerifyAssertions) {
          VM.sysFail("EventTypeChunk is too small to to add event type "+et);
        }
      }
    }
  }

  /**
   * Add a Property (key, value) pair to the Feed.
   * @param key the key for the property
   * @param value the value for the property
   */
  public synchronized void addProperty(String key, String value) {
    if (activePropertyTableChunk == null) {
      activePropertyTableChunk = new PropertyTableChunk();
    }
    if (!activePropertyTableChunk.add(key, value)) {
      activePropertyTableChunk.close();
      unwrittenMetaChunks.enqueue(activePropertyTableChunk);
      activePropertyTableChunk = new PropertyTableChunk();
      if (!activePropertyTableChunk.add(key, value)) {
        if (VM.VerifyAssertions) {
          VM.sysFail("PropertyTableChunk is too small to to add "+key+" = " +value);
        }
      }
    }
  }



  /*
   * Daemon Threads & I/O
   */

  private void createDaemonThreads() {
    /* Create primary I/O thread */
    Thread ioThread = new Thread(new Runnable() {
      public void run() {
        ioThreadMainLoop();
      }}, "TuningFork Primary I/O thread");
    ioThread.setDaemon(true);
    ioThread.start();

    /* Install shutdown hook that will delay VM exit until I/O completes. */
    VM_Callbacks.addExitMonitor(new ExitMonitor(){
      public void notifyExit(int value) {
        state = State.SHUTTING_DOWN;
        while (state == State.SHUTTING_DOWN) {
          try {
            Thread.sleep(1);
          } catch (InterruptedException e) {
          }
        }
      }});
  }

  private void ioThreadMainLoop() {
    state = State.RUNNING_FILE;
    while (true) {
      try {
        Thread.sleep(IO_INTERVAL_MS);
      } catch (InterruptedException e) {
        // Do nothing.
      }
      boolean shouldShutDown = state == State.SHUTTING_DOWN;
      writeMetaChunks();
      writeEventChunks(shouldShutDown);
      if (shouldShutDown) {
        state = State.SHUT_DOWN;
        return;
      }
    }
  }

  private synchronized void writeMetaChunks() {
    try {
      while (!unwrittenMetaChunks.isEmpty()) {
        RawChunk c = unwrittenMetaChunks.dequeue();
        c.write(outputStream);
      }
      if (activeEventTypeChunk != null && activeEventTypeChunk.hasData()) {
        activeEventTypeChunk.close();
        activeEventTypeChunk.write(outputStream);
        activeEventTypeChunk.reset();
      }
      if (activeFeedletChunk != null && activeFeedletChunk.hasData()) {
        activeFeedletChunk.close();
        activeFeedletChunk.write(outputStream);
        activeFeedletChunk.reset();
      }
      if (activePropertyTableChunk != null && activePropertyTableChunk.hasData()) {
        activePropertyTableChunk.close();
        activePropertyTableChunk.write(outputStream);
        activePropertyTableChunk.reset();
      }
    } catch (IOException e) {
      VM.sysWriteln("Exception while outputing trace TuningFork trace file");
      e.printStackTrace();
    }
  }

  private synchronized void writeEventChunks(boolean shouldShutDown) {
    // TODO: if shouldShutDown, we need to forcibly flush all of the EventChunks
    //       that are currently attached to feedlets as well.
    while (!unwrittenEventChunks.isEmpty()) {
      RawChunk c = unwrittenEventChunks.dequeue();
      try {
        c.write(outputStream);
      } catch (IOException e) {
        VM.sysWriteln("Exception while outputing trace TuningFork trace file");
        e.printStackTrace();
      }
      availableEventChunks.enqueue(c); /* reduce; reuse; recycle...*/
    }
    if (shouldShutDown) {
      activeEventChunk.close();
      try {
        activeEventChunk.write(outputStream);
      } catch (IOException e) {
        VM.sysWriteln("Exception while outputing trace TuningFork trace file");
        e.printStackTrace();
      }
    }

  }


  /*
   * Temporary hack to get a few events generated;
   * once we have feedlets implemented, these static entrypoints will go away.
   */
  EventType gcStart;
  EventType gcStop;
  EventChunk activeEventChunk;
  int sequenceNumber = 0;
  public void gcStart(int why) {
    if (gcStart == null) {
      gcStart = defineEvent("GC Start", "Start of a GC cycle",
                            new EventAttribute("Reason","Encoded reason for GC",ScalarType.INT));
      gcStop = defineEvent("GC Stop", "End of a GC Cycle");
      activeFeedletChunk.add(1, "VM Engine", "Tracing Engine");
    }

    if (activeEventChunk == null) {
      activeEventChunk = (EventChunk)availableEventChunks.dequeue();
      activeEventChunk.reset(1, sequenceNumber++);
    }
    if (!activeEventChunk.addEvent(VM_Time.nanoTime(), gcStart, why)) {
      activeEventChunk.close();
      unwrittenEventChunks.enqueue(activeEventChunk);
      activeEventChunk = (EventChunk)availableEventChunks.dequeue();
      activeEventChunk.reset(1, sequenceNumber++);
      activeEventChunk.addEvent(VM_Time.nanoTime(), gcStart, why);
    }
  }

  public void gcStop() {
    if (activeEventChunk == null) {
      activeEventChunk = (EventChunk)availableEventChunks.dequeue();
      activeEventChunk.reset(1, sequenceNumber++);
    }
    if (!activeEventChunk.addEvent(VM_Time.nanoTime(), gcStop)) {
      activeEventChunk.close();
      unwrittenEventChunks.enqueue(activeEventChunk);
      activeEventChunk = (EventChunk)availableEventChunks.dequeue();
      activeEventChunk.reset(1, sequenceNumber++);
      activeEventChunk.addEvent(VM_Time.nanoTime(), gcStop);
    }
  }

}
