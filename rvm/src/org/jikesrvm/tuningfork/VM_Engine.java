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
import org.jikesrvm.VM_Options;
import org.jikesrvm.VM_Callbacks.ExitMonitor;
import org.jikesrvm.scheduler.VM_Processor;
import org.jikesrvm.util.VM_HashSet;
import org.vmmagic.pragma.Uninterruptible;

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

/**
 * TuningFork Trace Engine (roughly functionally equivalent to the
 * Logger classes in the TuningFork JavaTraceGenerationLibrary).
 *
 */
public final class VM_Engine {

  public enum State { STARTING_UP, RUNNING_FILE, SHUTTING_DOWN, SHUT_DOWN };

  public static final VM_Engine engine = new VM_Engine();
  private static final int IO_INTERVAL_MS = 100;

  private final VM_ChunkQueue unwrittenMetaChunks = new VM_ChunkQueue();
  private final VM_ChunkQueue unwrittenEventChunks = new VM_ChunkQueue();
  private final VM_ChunkQueue availableEventChunks = new VM_ChunkQueue();

  private FeedletChunk activeFeedletChunk = new FeedletChunk();
  private EventTypeChunk activeEventTypeChunk = new EventTypeChunk();
  private PropertyTableChunk activePropertyTableChunk = new PropertyTableChunk();

  private int nextFeedletId = 0;
  private final VM_HashSet<VM_Feedlet> activeFeedlets = new VM_HashSet<VM_Feedlet>();

  private OutputStream outputStream;
  private State state = State.STARTING_UP;

  private VM_Engine() {
    /* Feed header and EventTypeSpaceChunk go first, so create & enqueue during bootimage writing */
    unwrittenMetaChunks.enqueue(new FeedHeaderChunk());
    unwrittenMetaChunks.enqueue(new EventTypeSpaceChunk(new EventTypeSpaceVersion("org.jikesrvm", 1)));

    for (int i=0; i<32; i++) {
      availableEventChunks.enqueue(new EventChunk());
    }
  }


  public void earlyStageBooting() {
    if (VM_Options.TuningForkTraceFile == null) {
      /* tracing not enabled on this run, shut down engine to minimize overhead */
      VM_Processor.getCurrentFeedlet().enabled = false;
      state = State.SHUT_DOWN;
    } else {
      unwrittenMetaChunks.enqueue(new VM_SpaceDescriptorChunk());
    }
  }

  public void fullyBootedVM() {
    if (state != State.SHUT_DOWN) {
      String traceFile = VM_Options.TuningForkTraceFile;
      if (!traceFile.endsWith(".trace")) {
        traceFile = traceFile+".trace";
      }

      File f = new File(traceFile);
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
  }


  /**
   * Put some basic properties about this vm build & current execution into the feed.
   */
  private void writeInitialProperites() {
    addProperty("rvm version", VM_Configuration.RVM_VERSION_STRING);
    addProperty("rvm config", VM_Configuration.RVM_CONFIGURATION);
    addProperty("Tick Frequency", "1000000000"); /* a tick is one nanosecond */
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
    if (state == State.SHUT_DOWN) return null;
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
    if (state == State.SHUT_DOWN) return null;
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
    if (state == State.SHUT_DOWN) return null;
    EventType result = new EventType(name, description, attributes);
    internalDefineEvent(result);
    return result;
  }

  private synchronized void internalDefineEvent(EventType et) {
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

  /*
   * Support for Properties
   */

  /**
   * Add a Property (key, value) pair to the Feed.
   * @param key the key for the property
   * @param value the value for the property
   */
  public synchronized void addProperty(String key, String value) {
    if (state == State.SHUT_DOWN) return;
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
   * Support for Feedlets
   */
  public synchronized VM_Feedlet makeFeedlet(String name, String description) {
    VM_Feedlet f = new VM_Feedlet(this, nextFeedletId++);
    if (state == State.SHUT_DOWN) {
      f.enabled = false;
      return f;
    }
    if (!activeFeedletChunk.add(f.getFeedletIndex(), name, description)) {
      activeFeedletChunk.close();
      unwrittenMetaChunks.enqueue(activeFeedletChunk);
      activeFeedletChunk = new FeedletChunk();
      if (!activeFeedletChunk.addProperty(f.getFeedletIndex(), name, description)) {
        if (VM.VerifyAssertions) {
          VM.sysFail("FeedletChunk is too small to to add feedlet "+name+" (" +description+")");
        }
      }
    }

    activeFeedlets.add(f);
    return f;
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
      // TODO: This isn't bulletproof.
      //       We should be snapshotting each feedlet's event chunk and then closing/writing it.
      for (VM_Feedlet f : activeFeedlets) {
        f.enabled = false; /* will stop new events from being added; in flight addEvents keep going */
      }
      for (VM_Feedlet f : activeFeedlets) {
        try {
          EventChunk ec = f.stealEvents();
          if (ec != null) {
            ec.write(outputStream);
          }
        } catch (IOException e) {
          VM.sysWriteln("Exception while outputing trace TuningFork trace file");
          e.printStackTrace();
        }
      }
    }
  }

  @Uninterruptible
  EventChunk getEventChunk() {
    return (EventChunk)availableEventChunks.dequeue();
  }

  @Uninterruptible
  public void returnFullEventChunk(EventChunk events) {
    unwrittenEventChunks.enqueue(events);
  }

}
