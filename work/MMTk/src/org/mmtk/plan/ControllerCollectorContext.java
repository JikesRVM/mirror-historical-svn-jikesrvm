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
package org.mmtk.plan;

import org.mmtk.utility.heap.HeapGrowthManager;
import org.mmtk.vm.HeavyCondLock;
import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;

@Uninterruptible
public class ControllerCollectorContext extends CollectorContext {

  /** The lock to use to manage collection */
  private HeavyCondLock lock;

  /** The set of worker threads to use */
  private ParallelCollectorGroup workers;

  /** Flag used to control the 'race to request' */
  private boolean requestFlag;

  /** The current request index */
  private int requestCount;

  /** The request index that was last completed */
  private int lastRequestCount = -1;

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
      waitForRequest();

      // The start time.
      long startTime = VM.statistics.nanoTime();

      // Stop all mutator threads
      VM.collection.stopAllMutators();

      // Was this user triggered?
      boolean userTriggeredCollection = VM.activePlan.global().isUserTriggeredCollection();

      // Clear the request
      clearRequest();

      // Trigger GC.
      workers.triggerCycle();

      // Wait for GC threads to complete.
      workers.waitForCycle();

      // Heap growth logic
      long elapsedTime = VM.statistics.nanoTime() - startTime;
      HeapGrowthManager.recordGCTime(VM.statistics.nanosToMillis(elapsedTime));
      if (VM.activePlan.global().lastCollectionFullHeap()) {
        if (!userTriggeredCollection) {
          // Don't consider changing the heap size if the application triggered the collection
          HeapGrowthManager.considerHeapSize();
        }
        HeapGrowthManager.reset();
      }

      // Resume all mutators
      VM.collection.resumeAllMutators();
    }
  }

  /**
   * Request a collection.
   */
  public void request() {
    if (requestFlag) {
      return;
    }
    lock.lock();
    if (!requestFlag) {
      requestFlag = true;
      requestCount++;
      lock.broadcast();
    }
    lock.unlock();
  }

  /**
   * Clear the collection request, making future requests incur an
   * additional collection cycle.
   */
  private void clearRequest() {
    lock.lock();
    requestFlag = false;
    lock.unlock();
  }

  /**
   * Wait until a request is received.
   */
  private void waitForRequest() {
    lock.lock();
    lastRequestCount++;
    while (lastRequestCount == requestCount) {
      lock.await();
    }
    lock.unlock();
  }
}
