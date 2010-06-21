/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.plan;

import org.mmtk.utility.Log;
import org.mmtk.utility.options.Options;
import org.mmtk.vm.Monitor;
import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;

@Uninterruptible
public class ControllerCollectorContext extends CollectorContext {

  /** The lock to use to manage collection */
  private Monitor lock;

  /** The set of worker threads to use */
  private ParallelCollectorGroup workers;

  /** Flag used to control the 'race to request' */
  private boolean requestFlag;

  /** The current request index */
  private int requestCount;

  /** The request index that was last completed */
  private int lastRequestCount = -1;
  
  /** Is there concurrent collection activity */
  private volatile boolean concurrentCollection = false; // LPJH: Should be volatile as value can change behind this
                                                         // threads back whilst we wait for the worker threads

  /**
   * Create a controller context.
   *
   * @param workers The worker group to use for collection.
   */
  public ControllerCollectorContext(ParallelCollectorGroup workers) {
    this.workers = workers;
  }

  @Override
  @Interruptible
  public void initCollector(int id) {
    super.initCollector(id);
    lock = VM.newHeavyCondLock("CollectorControlLock");
  }

  /**
   * Main execution loop.
   */
  @Unpreemptible
  public void run() {
    while(true) {
      // Wait for a collection request.
      if (Options.verbose.getValue() >= 5) Log.writeln("[ControllerCollectorContext: Waiting for request...]");
      waitForAndThenClearRequest();
      if (Options.verbose.getValue() >= 5) Log.writeln("[ControllerCollectorContext: Request recieved.]");

      if (concurrentCollection) {
        if (Options.verbose.getValue() >= 5) Log.writeln("[ControllerCollectorContext: Stopping concurrent collectors...]");
        Plan.concurrentWorkers.abortCycle();
        Plan.concurrentWorkers.waitForCycle();
        Phase.clearConcurrentPhase();
        // Collector must re-request concurrent collection in this case.
        concurrentCollection = false;
      }

      // Trigger GC.
      if (Options.verbose.getValue() >= 5) Log.writeln("[ControllerCollectorContext: Triggering GC worker threads...]");
      workers.triggerCycle();

      // Wait for GC threads to complete.
      workers.waitForCycle();
      if (Options.verbose.getValue() >= 5)
        Log.writeln("[ControllerCollectorContext: GC worker threads complete!]");
      
      // Start threads that will perform concurrent collection work alongside mutators.
      if (concurrentCollection) {
        if (Options.verbose.getValue() >= 5) Log.writeln("[ControllerCollectorContext: Triggering concurrent collectors...]");
        Plan.concurrentWorkers.triggerCycle();
      }
    }
  }

  /**
   * Request that concurrent collection is performed after this stop-the-world increment.
   */
  public void requestConcurrentCollection() {
    concurrentCollection = true;
  }

  /**
   * Request a collection.
   */
  public void request() {
    // if (requestFlag) { // LPJH: unsafe optimisation, requestFlag must only ever be accessed when holding a lock
    // return;            // or requestFlag must be volatile to ensure correct value is seen
    // }
    lock.lock();
    if (!requestFlag) {
      requestFlag = true;
      requestCount++;
      lock.broadcast();
    }
    lock.unlock();
  }

  /**
   * Wait until a request is received.
   */
  private void waitForAndThenClearRequest() {
    lock.lock();
    lastRequestCount++;
    while (lastRequestCount == requestCount) {
      lock.await();
    }
    // Because mutator threads might not be stopped during the current collector activity we must clear the request flag before
    // releasing the request lock. That way if we ever see requestFlag as being true again we know there is a new pending mutator
    // request for a GC
    requestFlag = false;
    lock.unlock();
  }
}
