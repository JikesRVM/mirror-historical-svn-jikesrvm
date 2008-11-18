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
package org.jikesrvm.mm.mminterface;

import org.jikesrvm.ArchitectureSpecific;
import org.jikesrvm.VM;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.HeavyCondLock;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Unpreemptible;
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

  private static Barrier barrier = new Barrier();

  private static HeavyCondLock schedLock;
  private static boolean triggerRun;
  private static int maxRunning;
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
    maxRunning=RVMThread.numProcessors;
    barrier.boot(maxRunning);
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
    byte[] stack = MemoryManager.newStack(ArchitectureSpecific.StackframeLayoutConstants.STACK_SIZE_COLLECTOR, true);

    schedLock.lock();
    running++;
    schedLock.unlock();

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
  @Unpreemptible
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
      schedLock.unlock();
      if (barrier.arrive(1)) {
        schedLock.lock();
        triggerRun=false;
        schedLock.broadcast();
        schedLock.unlock();
      }

      if (verbose >= 1) VM.sysWriteln("GC Message: Concurrent collector awake");
      Selected.Collector.get().concurrentCollect();
      if (verbose >= 1) VM.sysWriteln("GC Message: Concurrent collector finished");
    }
  }
  @Uninterruptible
  public static void scheduleConcurrentCollectorThreads() {
    schedLock.lock();
    if (true) {
      // this code is what is needed to make it work
      // but this is dangerous ... do we know that the concurrent workers will go into
      // quiescence before this is called?
      if (!triggerRun && running==0) {
        // now start a new cycle
        triggerRun=true;
        schedLock.broadcast();
      }
    } else {
      // this is the code I'd much rather be using
      // wait for previous concurrent collection cycle to finish
      while (triggerRun || running!=0) {
        schedLock.waitNicely();
      }
      // now start a new cycle
      triggerRun=true;
      schedLock.broadcast();
    }
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

/*
Local Variables:
   c-basic-offset: 2
End:
*/
