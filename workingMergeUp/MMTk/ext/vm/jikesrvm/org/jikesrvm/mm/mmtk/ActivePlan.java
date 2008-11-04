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

import org.mmtk.plan.Plan;
import org.mmtk.plan.CollectorContext;
import org.mmtk.plan.MutatorContext;
import org.mmtk.plan.PlanConstraints;
import org.mmtk.utility.Log;

import org.jikesrvm.mm.mminterface.Selected;
import org.jikesrvm.scheduler.RVMThread;

import org.vmmagic.pragma.*;

/**
 * This class contains interfaces to access the current plan, plan local and
 * plan constraints instances.
 */
@Uninterruptible public final class ActivePlan extends org.mmtk.vm.ActivePlan {

  /* Collector and Mutator Context Management */
  private static final int MAX_CONTEXTS = 128; // ??
  private static Selected.Collector[] collectors = new Selected.Collector[MAX_CONTEXTS];
  private static int collectorCount = 0; // Number of collector instances
  private static SynchronizedCounter mutatorCounter = new SynchronizedCounter();

  /** @return The active Plan instance. */
  @Inline
  public Plan global() {
    return Selected.Plan.get();
  }

  /** @return The active PlanConstraints instance. */
  @Inline
  public PlanConstraints constraints() {
    return Selected.Constraints.get();
  }

  /** @return The active CollectorContext instance. */
  @Inline
  public CollectorContext collector() {
    return Selected.Collector.get();
  }

  /** @return The active MutatorContext instance. */
  @Inline
  public MutatorContext mutator() {
    return Selected.Mutator.get();
  }

  /** @return The log for the active thread */
  public Log log() {
    return Selected.Mutator.get().getLog();
  }

  /** Flush the mutator remembered sets (if any) for this active plan */
  public static void flushRememberedSets() {
     Selected.Mutator.get().flushRememberedSets();
  }

  /**
   * Return the CollectorContext instance given its unique identifier.
   *
   * @param id The identifier of the CollectorContext to return
   * @return The specified CollectorContext
   */
  @Inline
  public CollectorContext collector(int id) {
    return collectors[id];
  }
  
  /**
   * Return the MutatorContext instance given its unique identifier.
   *
   * @param id The identifier of the MutatorContext to return
   * @return The specified MutatorContext
   */
  @Inline
  public MutatorContext mutator(int id) {
    RVMThread t=RVMThread.threadBySlot[id];
    if (t!=null && t.registeredMutator) {
      return t;
    } else {
      return null;
    }
  }

  /**
   * Return the Selected.Collector instance given its unique identifier.
   *
   * @param id The identifier of the Selected.Collector to return
   * @return The specified Selected.Collector
   */
  @Inline
  public Selected.Collector selectedCollector(int id) {
    return collectors[id];
  }
  /**
   * Return the Selected.Mutator instance given its unique identifier.
   *
   * @param id The identifier of the Selected.Mutator to return
   * @return The specified Selected.Mutator
   */
  @Inline
  public Selected.Mutator selectedMutator(int id) {
    RVMThread t=RVMThread.threadBySlot[id];
    if (t!=null && t.registeredMutator) {
      return t;
    } else {
      return null;
    }
  }


  /** @return The number of registered CollectorContext instances. */
  @Inline
  public int collectorCount() {
    return collectorCount;
  }

  /** @return The number of registered MutatorContext instances. */
  public int mutatorCount() {
    return RVMThread.nextSlot;
  }

  /** Reset the mutator iterator */
  public void resetMutatorIterator() {
    mutatorCounter.reset();
  }

  /**
   * Return the next <code>MutatorContext</code> in a
   * synchronized iteration of all mutators.
   *
   * @return The next <code>MutatorContext</code> in a
   *  synchronized iteration of all mutators, or
   *  <code>null</code> when all mutators have been done.
   */
  public MutatorContext getNextMutator() {
    for (;;) {
      int idx = mutatorCounter.increment();
      if (idx >= RVMThread.numThreads) {
	return null;
      } else {
	RVMThread t=RVMThread.threads[idx];
	if (t.registeredMutator) {
	  return t;
	}
      }
    }
  }

  /**
   * Register a new CollectorContext instance.
   *
   * @param collector The CollectorContext to register
   * @return The CollectorContext's unique identifier
   */
  @Interruptible
  public synchronized int registerCollector(CollectorContext collector) {
    collectors[collectorCount] = (Selected.Collector) collector;
    return collectorCount++;
  }

  /**
   * Register a new MutatorContext instance.
   *
   * @param mutator The MutatorContext to register
   * @return The MutatorContext's unique identifier
   */
  public void registerMutator(MutatorContext mutator) {
    RVMThread t=(RVMThread)mutator;
    t.id = t.threadSlot;
    t.registeredMutator = true;
  }
}
/*
Local Variables:
   c-basic-offset: 2
End:
*/
