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

import com.ibm.tuningfork.tracegen.chunk.EventTypeSpaceChunk;
import com.ibm.tuningfork.tracegen.chunk.FeedHeaderChunk;
import com.ibm.tuningfork.tracegen.chunk.RawChunk;
import com.ibm.tuningfork.tracegen.types.EventTypeSpaceVersion;

/**
 * TuningFork Trace Engine (roughly functionally equivalent to the
 * Logger classes in the TuningFork JavaTraceGenerationLibrary).
 *
 */
public class VM_Engine {

  public static final VM_Engine engine = new VM_Engine();

  private boolean tracingEnabled = false;
  private RawChunk[] fullChunks = new RawChunk[100];
  private int fullChunkCursor = 0;
  private OutputStream outputStream;

  public void earlyStageBooting() {
    pushChunk(new FeedHeaderChunk());
    pushChunk(new EventTypeSpaceChunk(new EventTypeSpaceVersion("org.jikesrvm", 1)));

    // TODO: make this conditional on command line argument.
    tracingEnabled = true;
  }

  public synchronized void pushChunk(RawChunk chunk) {
    fullChunks[fullChunkCursor++] = chunk;
  }

  public void fullyBootedVM() {
    File f = new File("rvm.trace");
    try {
      outputStream = new FileOutputStream(f);
    } catch (FileNotFoundException e) {
      VM.sysWriteln("Unable to open trace file "+f.getAbsolutePath());
      VM.sysWriteln("continuing, but TuningFork trace generation is disabled.");
      tracingEnabled = false;
    }

    // Complete hack.  For initial dumb prototype do all the IO on VM exit.
    VM_Callbacks.addExitMonitor(new VM_Callbacks.ExitMonitor() {
      public void notifyExit(int value) {
        for (int i = 0; i<fullChunkCursor; i++) {
          try {
            fullChunks[i].write(outputStream);
          } catch (IOException e) {
            VM.sysWriteln("Exception while outputing trace TuningFork trace file");
            e.printStackTrace();
            return;
          }
        }
      }
    });
   }



}
