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
package org.mmtk.plan.semispace.incremental;

import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.ReplicatingSpace;
import org.mmtk.policy.Space;
import org.mmtk.plan.*;
import org.mmtk.utility.Log;
import org.mmtk.utility.heap.VMRequest;
import org.mmtk.utility.options.Options;
import org.mmtk.vm.Lock;
import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

@Uninterruptible
public class SS extends StopTheWorld {

  /****************************************************************************
   *
   * Class variables
   */

  /** True if allocating into the "higher" semispace */
  public static boolean low = true; // True if allocing to "lower" semispace

  /** One of the two semi spaces that alternate roles at each collection */
  public static final ReplicatingSpace repSpace0 = new ReplicatingSpace("rep-ss0", DEFAULT_POLL_FREQUENCY, VMRequest.create());
  public static final int SS0 = repSpace0.getDescriptor();

  /** One of the two semi spaces that alternate roles at each collection */
  public static final ReplicatingSpace repSpace1 = new ReplicatingSpace("rep-ss1", DEFAULT_POLL_FREQUENCY, VMRequest.create());
  public static final int SS1 = repSpace1.getDescriptor();

  public final Trace toBeScannedRemset;
  public final Trace toBeCopiedRemset;
  public static volatile int currentTrace = 1;

  public static CopyLocal deadThreadsBumpPointer = new CopyLocal();

  static {
    fromSpace().prepare(true);
    toSpace().prepare(false);
  }

  static Lock tackOnLock = VM.newLock("tackOnLock");

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Class variables
   */
  public static final int ALLOC_SS = Plan.ALLOC_DEFAULT;

  public static final int FIRST_SCAN_SS = 0;
  public static final int SECOND_SCAN_SS = 0;

  /**
   * Constructor
   */
  public SS() {
    toBeScannedRemset = new Trace(metaDataSpace);
    toBeCopiedRemset = new Trace(metaDataSpace);
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
    if (phaseId == SS.PREPARE) {
      if (currentTrace == 1) {
        // scanning for the first time
        fromSpace().prepare(false); // Make fromSpace non moving for first trace
        deadThreadsBumpPointer.linearScan(SSMutator.preGCSanity);
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
    if (phaseId == SS.RELEASE) {
      if (currentTrace == 1) {
        // first scan
        currentTrace = 2;
      } else if (currentTrace == 2) {
        // second go around
        low = !low; // flip the semi-spaces
        toSpace().release();
        deadThreadsBumpPointer.rebind(fromSpace());
        deadThreadsBumpPointer.linearScan(SSMutator.postGCSanity);
      } else {
        VM.assertions.fail("Unknown currentTrace value");
      }
      super.collectionPhase(phaseId);
      return;
    }
    if (phaseId == SS.COMPLETE) {
      fromSpace().prepare(true); // make from space moving at last minute
      if (SS.currentTrace == 2) {
        SS.currentTrace = 1;
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
    TransitiveClosure.registerSpecializedScan(FIRST_SCAN_SS, SSTraceLocalFirst.class);
    TransitiveClosure.registerSpecializedScan(SECOND_SCAN_SS, SSTraceLocalSecond.class);
    super.registerSpecializedMethods();
  }

  public static boolean inFromSpace(Address slot) {
    return Space.isInSpace(fromSpace().getDescriptor(), slot);
  }

  public static boolean inToSpace(Address slot) {
    return Space.isInSpace(toSpace().getDescriptor(), slot);
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
  
  /** @return the current trace object. */
  public Trace getCurrentTrace() {
    if (currentTrace == 1)
      return toBeScannedRemset;
    else if (currentTrace == 2)
      return toBeCopiedRemset;
    else {
      VM.assertions.fail("Unknown trace count");
      return null;
    }
  }
}
