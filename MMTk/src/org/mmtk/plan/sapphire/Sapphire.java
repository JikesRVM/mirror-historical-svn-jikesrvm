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
package org.mmtk.plan.sapphire;

import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.ReplicatingSpace;
import org.mmtk.policy.Space;
import org.mmtk.plan.*;
import org.mmtk.plan.concurrent.Concurrent;
import org.mmtk.plan.sapphire.sanityChecking.*;
import org.mmtk.utility.Log;
import org.mmtk.utility.heap.VMRequest;
import org.mmtk.utility.options.Options;
import org.mmtk.utility.statistics.Stats;
import org.mmtk.vm.Lock;
import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

@Uninterruptible
public class Sapphire extends Concurrent {

  /****************************************************************************
   *
   * Class variables
   */

  /** True if allocating into the "higher" semispace */
  public static volatile boolean low = true; // True if allocing to "lower" semispace, volatile so collector thread always reads the
                                             // right value

  public static boolean iuTerminationMustCheckRoots = true;

  /** One of the two semi spaces that alternate roles at each collection */
  public static final ReplicatingSpace repSpace0 = new ReplicatingSpace("rep-ss0", VMRequest.create());
  public static final int SS0 = repSpace0.getDescriptor();

  /** One of the two semi spaces that alternate roles at each collection */
  public static final ReplicatingSpace repSpace1 = new ReplicatingSpace("rep-ss1", VMRequest.create());
  public static final int SS1 = repSpace1.getDescriptor();

  public final Trace globalFirstTrace;
  public final Trace globalSecondTrace;
  public static volatile int currentTrace = 0;

  protected static CopyLocal deadFromSpaceBumpPointers = new CopyLocal();
  protected static CopyLocal deadToSpaceBumpPointers = new CopyLocal();
  protected static Lock deadBumpPointersLock = VM.newLock("tackOnLock");

  static {
    fromSpace().prepare(true);
    toSpace().prepare(false);
    deadFromSpaceBumpPointers.rebind(fromSpace());
    deadToSpaceBumpPointers.rebind(toSpace());
  }

  static final PreFirstPhaseFromSpaceLinearSanityScan   preFirstPhaseFromSpaceLinearSanityScan   =  new PreFirstPhaseFromSpaceLinearSanityScan();
  static final PreFirstPhaseToSpaceLinearSanityScan     preFirstPhaseToSpaceLinearSanityScan     =  new PreFirstPhaseToSpaceLinearSanityScan();
  static final PostFirstPhaseFromSpaceLinearSanityScan  postFirstPhaseFromSpaceLinearSanityScan  =  new PostFirstPhaseFromSpaceLinearSanityScan();
  static final PostFirstPhaseToSpaceLinearSanityScan    postFirstPhaseToSpaceLinearSanityScan    =  new PostFirstPhaseToSpaceLinearSanityScan();
  static final PreSecondPhaseFromSpaceLinearSanityScan  preSecondPhaseFromSpaceLinearSanityScan  =  new PreSecondPhaseFromSpaceLinearSanityScan();
  static final PreSecondPhaseToSpaceLinearSanityScan    preSecondPhaseToSpaceLinearSanityScan    =  new PreSecondPhaseToSpaceLinearSanityScan();
  static final PostSecondPhaseFromSpaceLinearSanityScan postSecondPhaseFromSpaceLinearSanityScan =  new PostSecondPhaseFromSpaceLinearSanityScan();
  static final PostSecondPhaseToSpaceLinearSanityScan   postSecondPhaseToSpaceLinearSanityScan   =  new PostSecondPhaseToSpaceLinearSanityScan();

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Class variables
   */
  protected static final int ALLOC_REPLICATING = Plan.ALLOC_DEFAULT;

  public static final int FIRST_SCAN_SS = 0;
  public static final int SECOND_SCAN_SS = 0;

  public static volatile boolean mutatorsEnabled = true;

  /**
   * Constructor
   */
  public Sapphire() {
    globalFirstTrace = new Trace(metaDataSpace1);
    globalSecondTrace = new Trace(metaDataSpace2);
    /**
     * This is the phase that is executed to perform a collection.
     */
    collection = Phase.createComplex("collection", null,
        Phase.schedulePlaceholder(PRE_SANITY_PLACEHOLDER),
        Phase.scheduleComplex(initPhase),
        Phase.scheduleGlobal(SAPPHIRE_PREPARE_FIRST_TRACE),
        Phase.scheduleOnTheFlyMutator(FLUSH_MUTATOR),
        Phase.scheduleComplex(transitiveClosure),
        Phase.scheduleGlobal(INSERTION_BARRIER_TERMINATION_CONDITION),
        Phase.scheduleSpecial(DISABLE_MUTATORS),
        Phase.scheduleComplex(completeClosurePhase),
        Phase.schedulePlaceholder(POST_SANITY_PLACEHOLDER),
        // Phase.scheduleGlobal(SANITY_SET_POSTGC),
        // Phase.scheduleComplex(sanityBuildPhase),
        // Phase.scheduleComplex(sanityCheckPhase),
        Phase.scheduleSTWmutator(PREPARE), // STW here to help verify mutators are stopped, change later
        Phase.scheduleGlobal     (PREPARE),
        Phase.scheduleCollector  (PREPARE),
        Phase.scheduleGlobal(SAPPHIRE_PREPARE_SECOND_TRACE),
        Phase.schedulePlaceholder(PRE_SANITY_PLACEHOLDER),
        Phase.scheduleComplex(transitiveClosure),
        Phase.scheduleComplex(forwardPhase),
        Phase.scheduleComplex(completeClosurePhase),
        Phase.schedulePlaceholder(POST_SANITY_PLACEHOLDER),
        Phase.scheduleComplex(finishPhase),
        Phase.scheduleGlobal(SAPPHIRE_PREPARE_ZERO_TRACE),
        Phase.scheduleSpecial(ENABLE_MUTATORS));
  }
  
  protected static final short INSERTION_BARRIER_TERMINATION_CONDITION = Phase.createSimple("insertionBarrierTerminationCondition");

  protected static final short transitiveClosure = Phase.createComplex("transitiveClosure",
      Phase.scheduleComplex(rootClosurePhase),
      Phase.scheduleComplex(refTypeClosurePhase));

  /****************************************************************************
   *
   * Collection
   */

  /**
   * Perform a (global) collection phase.
   *
   * @param phaseId Collection phase
   */
  @Inline
  public void collectionPhase(short phaseId) {
    if (phaseId == SAPPHIRE_PREPARE_FIRST_TRACE) {
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(Sapphire.currentTrace == 0);
        VM.assertions._assert(!globalFirstTrace.hasWork());
        VM.assertions._assert(!globalSecondTrace.hasWork());
      }
      if (Options.verbose.getValue() >= 8) Log.writeln("Switching to 1st trace");
      Sapphire.currentTrace = 1;
      iuTerminationMustCheckRoots = true;;
      MutatorContext.globalViewInsertionBarrier = true;
      MutatorContext.globalViewMutatorMustDoubleAllocate = false; // must not yet turn on allocation barrier until all threads have
                                                                  // seen insertion barrier - this is required because otherwise new
                                                                  // objects would be allocated grey and never scanned, if the
                                                                  // insertion barrier was yet turned on then references could
                                                                  // be hidden in these objects
      MutatorContext.globalViewMutatorMustReplicate = false;
      if (Options.verbose.getValue() >= 8) Log.writeln("Global set insertion barrier about to request handshake");
      VM.collection.requestUpdateBarriers();
      MutatorContext.globalViewMutatorMustDoubleAllocate = true; // all mutator threads have now seen insertion barrier
      if (Options.verbose.getValue() >= 8) Log.writeln("Global set double allocation barrier about to request handshake");
      VM.collection.requestUpdateBarriers(); // all threads have now seen allocation barrier
      return;
    }
    
    if (phaseId == INSERTION_BARRIER_TERMINATION_CONDITION) {
      if (Options.verbose.getValue() >= 8) {
        Log.writeln("INSERTION_BARRIER_TERMINATION_CONDITION checking for work");
        Space.printVMMap();
      }
      // first check the global write buffer
      if (globalFirstTrace.hasWork()) {
        // found some work in a buffer 

        if (Options.verbose.getValue() >= 8) Log.writeln("INSERTION_BARRIER_TERMINATION_CONDITION globalFirstTrace already contains work, mustCheckRoots will be set true");
        Phase.pushScheduledPhase(Phase.scheduleGlobal(INSERTION_BARRIER_TERMINATION_CONDITION)); // ensure we will rerun the termination check
        Phase.pushScheduledPhase(Phase.scheduleComplex(transitiveClosure)); // do another transitive closure
        iuTerminationMustCheckRoots = true; // we will need to check for work again
        return;
      }
      if (iuTerminationMustCheckRoots) { // do we need to check the roots?
        if (Options.verbose.getValue() >= 8) Log.writeln("INSERTION_BARRIER_TERMINATION_CONDITION mustCheckRoots true, will scan roots");
        // in reverse order do...
        Phase.pushScheduledPhase(Phase.scheduleGlobal(INSERTION_BARRIER_TERMINATION_CONDITION)); // ensure we will rerun the termination check
        Phase.pushScheduledPhase(Phase.scheduleCollector(FLUSH_COLLECTOR)); // flush collector threads that scanned the stacks
        Phase.pushScheduledPhase(Phase.scheduleOnTheFlyMutator(FLUSH_MUTATOR)); // flush here so we can tell if there is any work left on the queues
        Phase.pushScheduledPhase(Phase.scheduleComplex(rootScanPhase)); // do another transitive closure
        iuTerminationMustCheckRoots = false; // will be set to true if globalFirstTrace finds work
        return;
      }
      if (Options.verbose.getValue() >= 8) Log.writeln("INSERTION_BARRIER_TERMINATION_CONDITION found no work and did not have to scan roots, done");
      return;
    }

    if (phaseId == PRE_TRACE_LINEAR_SCAN) {
      if (currentTrace == 0) { // run *before* 1st trace
        Log.writeln("Global running preFirstPhaseFromSpaceLinearSanityScan and preFirstPhaseToSpaceLinearSanityScan");
        deadFromSpaceBumpPointers.linearScan(Sapphire.preFirstPhaseFromSpaceLinearSanityScan);
        deadToSpaceBumpPointers.linearScan(Sapphire.preFirstPhaseToSpaceLinearSanityScan);
        return;
      }
      if (currentTrace == 2) { // run *after* we switch to 2nd trace but *before* we actually do anything
        Log.writeln("Global running preSecondPhaseFromSpaceLinearSanityScan and preSecondPhaseToSpaceLinearSanityScan");
        deadFromSpaceBumpPointers.linearScan(Sapphire.preSecondPhaseFromSpaceLinearSanityScan);
        deadToSpaceBumpPointers.linearScan(Sapphire.preSecondPhaseToSpaceLinearSanityScan);
        return;
      }
    }

    if (phaseId == POST_TRACE_LINEAR_SCAN) {
      if (currentTrace == 1) {
        Log.writeln("Global running postFirstPhaseFromSpaceLinearSanityScan and postFirstPhaseToSpaceLinearSanityScan");
        deadFromSpaceBumpPointers.linearScan(Sapphire.postFirstPhaseFromSpaceLinearSanityScan);
        deadToSpaceBumpPointers.linearScan(Sapphire.postFirstPhaseToSpaceLinearSanityScan);
        return;
      }
      if (currentTrace == 2) {
        Log.writeln("Global running postSecondPhaseFromSpaceLinearSanityScan and postSecondPhaseToSpaceLinearSanityScan");
        deadFromSpaceBumpPointers.linearScan(Sapphire.postSecondPhaseFromSpaceLinearSanityScan);
        deadToSpaceBumpPointers.linearScan(Sapphire.postSecondPhaseToSpaceLinearSanityScan);
        return;
      }
    }

    if (phaseId == Sapphire.PREPARE) {
      if (currentTrace == 0) {
        // scanning for the first time
        fromSpace().prepare(false); // Make fromSpace non moving for first trace
        globalFirstTrace.prepareNonBlocking();
      } else if (currentTrace == 1) {
        fromSpace().prepare(true); // Make fromSpace moveable whilst GC in progress
        globalSecondTrace.prepareNonBlocking();
      } else {
        VM.assertions.fail("Unknown currentTrace value");
      }
      super.collectionPhase(phaseId);
      return;
    }

    if (phaseId == CLOSURE) {
      return;
    }

    if (phaseId == SAPPHIRE_PREPARE_SECOND_TRACE) {
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(Sapphire.currentTrace == 1);
        VM.assertions._assert(!globalFirstTrace.hasWork());
        VM.assertions._assert(!globalSecondTrace.hasWork());
      }
      if (Options.verbose.getValue() >= 8) Log.writeln("Switching to 2nd trace");
      currentTrace = 2;
      return;
    }

    if (phaseId == Sapphire.RELEASE) {
      if (currentTrace == 1) {
        // completed first scan
        if (Options.verbose.getValue() >= 8) {
          Space.printVMMap();
          Space.printUsageMB();
          Space.printUsagePages();
        }
      } else if (currentTrace == 2) {
        // second go around
        low = !low; // flip the semi-spaces
        toSpace().release();
        Sapphire.deadBumpPointersLock.acquire();
        deadFromSpaceBumpPointers.rebind(fromSpace()); // clear fromSpace bump pointer
        CopyLocal tmp = deadFromSpaceBumpPointers;
        deadFromSpaceBumpPointers = deadToSpaceBumpPointers;
        deadToSpaceBumpPointers = tmp;  // objects in toSpace are now in the fromSpace deadBumpPointers are will be scanned at next GC
        deadToSpaceBumpPointers.rebind(toSpace());
        Sapphire.deadBumpPointersLock.release();
      } else {
        VM.assertions.fail("Unknown currentTrace value");
      }
      super.collectionPhase(phaseId);
      return;
    }

    if (phaseId == Sapphire.COMPLETE) {
      fromSpace().prepare(true); // make from space moving at last minute
    }

    if (phaseId == SAPPHIRE_PREPARE_ZERO_TRACE) {
      VM.assertions._assert(Sapphire.currentTrace == 2);
      if (Options.verbose.getValue() >= 8) Log.writeln("Switching to 0th trace");
      Sapphire.currentTrace = 0;
      return;
    }

    if (phaseId == Simple.PREPARE_STACKS) {
      stacksPrepared = false;
      return; // never globally acknowledge that *all* stacks have been prepared
    }

    super.collectionPhase(phaseId);
  }

  /****************************************************************************
   *
   * Accounting
   */

  /**
   * Return the number of pages reserved for copying.
   *
   * @return The number of pages reserved given the pending
   * allocation, including space reserved for copying.
   */
  public final int getCollectionReserve() {
    // our copy reserve is the size of fromSpace less any copying we have done so far
    return (fromSpace().reservedPages() - toSpace().reservedPages()) + super.getCollectionReserve();
  }

  /**
   * Return the number of pages reserved for use given the pending
   * allocation.  This is <i>exclusive of</i> space reserved for
   * copying.
   *
   * @return The number of pages reserved given the pending
   * allocation, excluding space reserved for copying.
   */
  public int getPagesUsed() {
    return super.getPagesUsed() + toSpace().reservedPages() + fromSpace().reservedPages();
  }

  /**
   * This method controls the triggering of a GC. It is called periodically
   * during allocation. Returns true to trigger a collection.
   *
   * @param spaceFull Space request failed, must recover pages within 'space'.
   * @param space TODO
   * @return True if a collection is requested by the plan.
   */
  protected boolean collectionRequired(boolean spaceFull, Space space) {
    if (space == Sapphire.toSpace()) {
      // toSpace allocation must always succeed
      logPoll(space, "To-space collection requested - ignoring request");
      return false;
    }

    return super.collectionRequired(spaceFull, space);
  }

  /**
   * This method controls the triggering of an atomic phase of a concurrent collection. It is called periodically during allocation.
   * @return True if a collection is requested by the plan.
   */
  @Override
  protected boolean concurrentCollectionRequired(Space space) {
    if (space == Sapphire.toSpace()) {
      // toSpace allocation must always succeed
      logPoll(space, "To-space collection requested - ignoring request");
      return false;
    }

    return !Phase.concurrentPhaseActive() && ((getPagesReserved() * 100) / getTotalPages()) > Options.concurrentTrigger.getValue();
  }

  /**
   * @see org.mmtk.plan.Plan#willNeverMove
   *
   * @param object Object in question
   * @return True if the object will never move
   */
  @Override
  public boolean willNeverMove(ObjectReference object) {
    if (Space.isInSpace(SS0, object) || Space.isInSpace(SS1, object))
      return false;
    return super.willNeverMove(object);
  }

  /**
   * Register specialized methods.
   */
  @Interruptible
  protected void registerSpecializedMethods() {
    TransitiveClosure.registerSpecializedScan(FIRST_SCAN_SS, SapphireTraceLocalFirst.class);
    TransitiveClosure.registerSpecializedScan(SECOND_SCAN_SS, SapphireTraceLocalSecond.class);
    super.registerSpecializedMethods();
  }

  /****************************************************************************
   * Space checks
   */

  /**
   * @return The to space for the current collection.
   */
  @Inline
  public static ReplicatingSpace toSpace() {
    return low ? repSpace1 : repSpace0;
  }

  /**
   * @return The from space for the current collection.
   */
  @Inline
  public static ReplicatingSpace fromSpace() {
    return low ? repSpace0 : repSpace1;
  }

  public static boolean inFromSpace(ObjectReference obj) {
    return inFromSpace(VM.objectModel.refToAddress(obj));
  }

  public static boolean inToSpace(ObjectReference obj) {
    return inToSpace(VM.objectModel.refToAddress(obj));
  }

  public static boolean inFromSpace(Address slot) {
    return Space.isInSpace(fromSpace().getDescriptor(), slot);
  }

  public static boolean inToSpace(Address slot) {
    return Space.isInSpace(toSpace().getDescriptor(), slot);
  }

  /** @return the current trace object. */
  public Trace getCurrentTrace() {
    if (currentTrace == 1)
      return globalFirstTrace;
    else if (currentTrace == 2)
      return globalSecondTrace;
    else {
      VM.assertions.fail("Unknown trace count");
      return null;
    }
  }
  
  /** Build and validate a sanity table */
  // LPJH: will need to change this later
  protected static final short preSanityPhase = Phase.createComplex("pre-sanity", null,
      Phase.scheduleSpecial (STOP_MUTATORS),
      Phase.scheduleGlobal     (SANITY_SET_PREGC),
      Phase.scheduleComplex    (sanityBuildPhase),
      Phase.scheduleComplex    (sanityCheckPhase),
      Phase.scheduleSpecial (RESTART_MUTATORS),
      Phase.scheduleOnTheFlyMutator(PRE_TRACE_LINEAR_SCAN),
      Phase.scheduleCollector(PRE_TRACE_LINEAR_SCAN),
      Phase.scheduleGlobal(PRE_TRACE_LINEAR_SCAN));

  /** Build and validate a sanity table */
  protected static final short postSanityPhase = Phase.createComplex("post-sanity", null,
      Phase.scheduleSpecial (STOP_MUTATORS),
      Phase.scheduleGlobal     (SANITY_SET_POSTGC),
      Phase.scheduleComplex    (sanityBuildPhase),
      Phase.scheduleComplex    (sanityCheckPhase),
      Phase.scheduleSpecial (RESTART_MUTATORS),
      Phase.scheduleSTWmutator(POST_TRACE_LINEAR_SCAN),
      Phase.scheduleCollector(POST_TRACE_LINEAR_SCAN),
      Phase.scheduleGlobal(POST_TRACE_LINEAR_SCAN));

  /**
   * The processOptions method is called by the runtime immediately after command-line arguments are available. Allocation must be
   * supported prior to this point because the runtime infrastructure may require allocation in order to parse the command line
   * arguments. For this reason all plans should operate gracefully on the default minimum heap size until the point that
   * processOptions is called.
   */
  @Interruptible
  public void processOptions() {
    // don't call Super's processOptions otherwise it will overwrite the PLACEHOLDER's
    VM.statistics.perfEventInit(Options.perfEvents.getValue());
    if (Options.verbose.getValue() > 2) Space.printVMMap();
    if (Options.verbose.getValue() > 3) VM.config.printConfig();
    if (Options.verbose.getValue() > 0) Stats.startAll();
    if (Options.eagerMmapSpaces.getValue()) Space.eagerlyMmapMMTkSpaces();

    /* Set up the concurrent marking phase */
    replacePhase(Phase.scheduleCollector(CLOSURE), Phase.scheduleComplex(concurrentClosure));

    // if (Options.sanityCheck.getValue()) {
    // Log.writeln("Sapphire Collection sanity checking enabled.");
    // replacePhase(Phase.schedulePlaceholder(PRE_SANITY_PLACEHOLDER), Phase.scheduleComplex(preSanityPhase));
    // replacePhase(Phase.schedulePlaceholder(POST_SANITY_PLACEHOLDER), Phase.scheduleComplex(postSanityPhase));
    // }
  }
}
