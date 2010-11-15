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
import org.mmtk.utility.Log;
import org.mmtk.utility.heap.VMRequest;
import org.mmtk.utility.options.Options;
import org.mmtk.utility.options.Verbose;
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
  public static boolean low = true; // True if allocing to "lower" semispace

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

  static final PreGCToSpaceLinearSanityScan preGCToSpaceSanity = new PreGCToSpaceLinearSanityScan();
  static final PostGCToSpaceLinearSanityScan postGCToSpaceSanity = new PostGCToSpaceLinearSanityScan();
  static final PreGCFromSpaceLinearSanityScan preGCFromSpaceSanity = new PreGCFromSpaceLinearSanityScan();
  static final PostGCFromSpaceLinearSanityScan postGCFromSpaceSanity = new PostGCFromSpaceLinearSanityScan();

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

  /**
   * Constructor
   */
  public Sapphire() {
    globalFirstTrace = new Trace(metaDataSpace);
    globalSecondTrace = new Trace(metaDataSpace);
    /**
     * This is the phase that is executed to perform a collection.
     */
    collection = Phase.createComplex("collection", null,
        Phase.scheduleComplex(initPhase),
        Phase.scheduleComplex(rootClosurePhase),
        Phase.scheduleComplex(refTypeClosurePhase),
        Phase.scheduleComplex(forwardPhase),
        Phase.scheduleComplex(completeClosurePhase),
        Phase.scheduleComplex(rootClosurePhase),
        Phase.scheduleComplex(refTypeClosurePhase),
        Phase.scheduleComplex(forwardPhase),
        Phase.scheduleComplex(completeClosurePhase),
        Phase.scheduleComplex(finishPhase));
  }

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
    if (phaseId == Sapphire.PREPARE) {
      if (currentTrace == 1) {
        // scanning for the first time
        fromSpace().prepare(false); // Make fromSpace non moving for first trace
        // Log.writeln("*** Plan about to PreGC FromSpace deadFromSpaceBumpPointer");
        deadFromSpaceBumpPointers.linearScan(Sapphire.preGCFromSpaceSanity);
        // Log.writeln("*** Plan finished PreGC FromSpace deadFromSpaceBumpPointer");
        // Log.writeln("*** Plan about to PreGC ToSpace deadToSpaceBumpPointer");
        deadToSpaceBumpPointers.linearScan(Sapphire.preGCToSpaceSanity);
        // Log.writeln("*** Plan finished PreGC ToSpace deadToSpaceBumpPointer");
      } else if (currentTrace == 2) {
        fromSpace().prepare(true); // Make fromSpace moveable whilst GC in progress
      }
      super.collectionPhase(phaseId);
      return;
    }
    if (phaseId == CLOSURE) {
      getCurrentTrace().prepare();
      return;
    }
    if (phaseId == Sapphire.RELEASE) {
      if (currentTrace == 1) {
        // completed first scan
        if (Options.verbose.getValue() >= 8) Space.printVMMap();
        currentTrace = 2;
      } else if (currentTrace == 2) {
        // second go around
        low = !low; // flip the semi-spaces
        toSpace().release();
        deadFromSpaceBumpPointers.rebind(fromSpace()); // clear fromSpace bump pointer
        // Log.writeln("*** Plan about to PostGC FromSpace deadFromSpaceBumpPointer");
        deadFromSpaceBumpPointers.linearScan(Sapphire.postGCFromSpaceSanity);
        // Log.writeln("*** Plan finished PostGC FromSpace deadFromSpaceBumpPointer");
        // Log.writeln("*** Plan about to PostGC ToSpace deadToSpaceBumpPointer");
        deadToSpaceBumpPointers.linearScan(Sapphire.postGCToSpaceSanity);
        // Log.writeln("*** Plan finished PostGC ToSpace deadToSpaceBumpPointer");
        CopyLocal tmp = deadFromSpaceBumpPointers;
        deadFromSpaceBumpPointers = deadToSpaceBumpPointers;
        deadToSpaceBumpPointers = tmp;  // objects in toSpace are now in the fromSpace deadBumpPointers are will be scanned at next GC
        deadToSpaceBumpPointers.rebind(toSpace());
      } else {
        VM.assertions.fail("Unknown currentTrace value");
      }
      super.collectionPhase(phaseId);
      return;
    }
    if (phaseId == Sapphire.COMPLETE) {
      fromSpace().prepare(true); // make from space moving at last minute
      if (Sapphire.currentTrace == 2) {
        Sapphire.currentTrace = 0;
      }
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
    // we must account for the number of pages required for copying,
    // which equals the number of semi-space pages reserved
    return fromSpace().reservedPages() + super.getCollectionReserve();
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
    return super.getPagesUsed() + fromSpace().reservedPages();
  }

  /**
   * Return the number of pages available for allocation, <i>assuming
   * all future allocation is to the semi-space</i>.
   *
   * @return The number of pages available for allocation, <i>assuming
   * all future allocation is to the semi-space</i>.
   */
  public final int getPagesAvail() {
    return(super.getPagesAvail()) >> 1;
  }

  /**
   * This method controls the triggering of an atomic phase of a concurrent collection. It is called periodically during allocation.
   * @return True if a collection is requested by the plan.
   */
  @Override
  protected boolean concurrentCollectionRequired() {
    return false;
    // return !Phase.concurrentPhaseActive() &&
    // ((getPagesReserved() * 100) / getTotalPages()) > Options.concurrentTrigger.getValue();
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
}
