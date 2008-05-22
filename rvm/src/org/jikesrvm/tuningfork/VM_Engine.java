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
import org.jikesrvm.VM_Callbacks.ExitMonitor;

import com.ibm.tuningfork.tracegen.chunk.EventTypeChunk;
import com.ibm.tuningfork.tracegen.chunk.EventTypeSpaceChunk;
import com.ibm.tuningfork.tracegen.chunk.FeedHeaderChunk;
import com.ibm.tuningfork.tracegen.chunk.FeedletChunk;
import com.ibm.tuningfork.tracegen.chunk.PropertyTableChunk;
import com.ibm.tuningfork.tracegen.chunk.RawChunk;
import com.ibm.tuningfork.tracegen.types.EventTypeSpaceVersion;

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

  private FeedletChunk activeFeedletChunk;
  private EventTypeChunk activeEventTypeChunk;
  private PropertyTableChunk activePropertyTableChunk;

  private OutputStream outputStream;
  private State state;

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

    /* Create primary I/O thread */
    Thread ioThread = new Thread(new Runnable() {
      public void run() {
        ioThreadMainLoop();
      }}, "TuningFork Primary I/O thread");
    ioThread.setDaemon(true);
    ioThread.start();

    /*
     * Create shutdown hook to ensure the I/O thread has a chance
     * to get all the data to disk before the VM exits.
     */
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
    while (!unwrittenMetaChunks.isEmpty()) {
      RawChunk c = unwrittenMetaChunks.dequeue();
      try {
        c.write(outputStream);
      } catch (IOException e) {
        VM.sysWriteln("Exception while outputing trace TuningFork trace file");
        e.printStackTrace();
        return;
      }
      availableEventChunks.enqueue(c); /* reduce; reuse; recycle...*/
    }
  }

  private synchronized void writeEventChunks(boolean shouldShutDown) {
    // TODO: if shouldShutDown, we need to forcible flush all of the EventChunks
    //       that are currently attached to feedlets as well.
    while (!unwrittenEventChunks.isEmpty()) {
      RawChunk c = unwrittenEventChunks.dequeue();
      try {
        c.write(outputStream);
      } catch (IOException e) {
        VM.sysWriteln("Exception while outputing trace TuningFork trace file");
        e.printStackTrace();
        return;
      }
    }
  }
}
