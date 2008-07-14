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
package org.jikesrvm.memorymanagers.mminterface;

import org.jikesrvm.ArchitectureSpecific;
import org.jikesrvm.VM;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.HeavyCondLock;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.LogicallyUninterruptible;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.NonMoving;

/**
 * Threads that perform collector work while mutators are active. these
 * threads wait for the collector to activate them.
 */
@NonMoving
public final class ConcurrentCollectorThread extends RVMThread {

  /***********************************************************************
   *
   * Class variables
   */
  private static final int verbose = 0;

  /** Name used by toString() and when we create the associated
   * java.lang.Thread.  */
  private static final String myName = "ConcurrentCollectorThread";


  private static HeavyCondLock schedLock;
  private static boolean triggerRun;
  private static int maxRunning = RVMThread.numProcessors;
  private static int running;

  /***********************************************************************
   *
   * Instance variables
   */

  /***********************************************************************
   *
   * Initialization
   */

  /**
   * Constructor
   *
   * @param stack The stack this thread will run on
   * @param isActive Whether or not this thread will participate in GC
   * @param processorAffinity The processor with which this thread is
   * associated.
   */
  ConcurrentCollectorThread(byte[] stack) {
    super(stack, myName);
    this.collectorContext = new Selected.Collector(this);
    makeDaemon(true); // this is redundant, but harmless
  }

  /**
   * Initialize for boot image.
   */
  @Interruptible
  public static void init() {
  }
  
  public static void boot() {
    schedLock = new HeavyCondLock();
  }

  /**
   * Make a concurrent collector thread.<p>
   *
   * Note: the new thread's stack must be in pinned memory: currently
   * done by allocating it in immortal memory.
   *
   * @param processorAffinity processor to run on
   * @return a new collector thread
   */
  @Interruptible
  public static ConcurrentCollectorThread createConcurrentCollectorThread() {
    byte[] stack = MM_Interface.newStack(ArchitectureSpecific.StackframeLayoutConstants.STACK_SIZE_COLLECTOR, true);
    return new ConcurrentCollectorThread(stack);
  }

  /**
   * Override Thread.toString
   *
   * @return A string describing this thread.
   */
  @Uninterruptible
  public String toString() {
    return myName;
  }

  /**
   * Run method for concurrent collector thread.
   */
  @LogicallyUninterruptible
  @Uninterruptible
  public void run() {
    if (verbose >= 1) VM.sysWriteln("GC Message: Concurrent collector thread entered run...");

    while (true) {
      /* suspend this thread: it will resume when the garbage collector
       * notifies it there is work to do. */
      schedLock.lock();
      running--;
      if (running==0) {
	schedLock.broadcast();
      }
      while (!triggerRun) {
	schedLock.waitNicely();
      }
      running++;
      if (running==maxRunning) {
	schedLock.broadcast();
      }
      // wait for the trigger to reset
      while (triggerRun) {
	schedLock.waitNicely();
      }
      schedLock.unlock();

      if (verbose >= 1) VM.sysWriteln("GC Message: Concurrent collector awake");
      Selected.Collector.get().concurrentCollect();
      if (verbose >= 1) VM.sysWriteln("GC Message: Concurrent collector finished");
    }
  }
  
  @Uninterruptible
  public static void scheduleConcurrentCollectorThreads() {
    schedLock.lock();
    // wait for previous concurrent collection cycle to finish
    while (running!=0) {
      schedLock.waitNicely();
    }
    // now start a new cycle
    triggerRun=true;
    schedLock.broadcast();
    // wait for all of them to start running
    while (running<maxRunning) {
      schedLock.waitNicely();
    }
    // reset the trigger
    triggerRun=false;
    schedLock.broadcast();
    schedLock.unlock();
  }

  /**
   * Is this a concurrent collector thread?  Concurrent collector threads
   * need to be stopped during GC.
   * @return true
   */
  @Uninterruptible
  public boolean isConcurrentGCThread() {
    return true;
  }

}

