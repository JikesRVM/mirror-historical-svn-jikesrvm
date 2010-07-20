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
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;
import org.mmtk.plan.*;
import org.mmtk.utility.heap.VMRequest;
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
  public static final CopySpace copySpace0 = new CopySpace("ss0", DEFAULT_POLL_FREQUENCY, VMRequest.create());
  public static final int SS0 = copySpace0.getDescriptor();

  /** One of the two semi spaces that alternate roles at each collection */
  public static final CopySpace copySpace1 = new CopySpace("ss1", DEFAULT_POLL_FREQUENCY, VMRequest.create());
  public static final int SS1 = copySpace1.getDescriptor();

  public final Trace ssTrace;

  public static volatile boolean copyingAllComplete = true;
  public static volatile int linearScannedSoFar = 0;
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

  public static final int SCAN_SS = 0;

  /**
   * Constructor
   */
  public SS() {
    ssTrace = new Trace(metaDataSpace);
  }

  /**
   * @return The to space for the current collection.
   */
  @Inline
  public static CopySpace toSpace() {
    return low ? copySpace1 : copySpace0;
  }

  /**
   * @return The from space for the current collection.
   */
  @Inline
  public static CopySpace fromSpace() {
    return low ? copySpace0 : copySpace1;
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
      ssTrace.prepare();
      fromSpace().prepare(true); // Make fromSpace moveable whilst GC in progress
      deadThreadsBumpPointer.linearScan(SSMutator.preGCSanity);
      super.collectionPhase(phaseId);
      return;
    }
    if (phaseId == CLOSURE) {
      copyingAllComplete = false;
      // Log.writeln("Closure for dead Thread mutator context");
      deadThreadsBumpPointer.linearScan(SSCollector.linearTrace);
      // Log.writeln("Linear scanned so far: ", SS.linearScannedSoFar);
      ssTrace.prepare();
      fromSpace().prepare(false); // no more objects can be copied from fromSpace in this GC cycle
      return;
    }
    if (phaseId == SS.RELEASE) {
      if (copyingAllComplete) {
        low = !low; // flip the semi-spaces
        toSpace().release();
        deadThreadsBumpPointer.rebind(fromSpace());
      }
      deadThreadsBumpPointer.linearScan(SSMutator.postGCSanity);
      super.collectionPhase(phaseId);
      return;
    }
    if (phaseId == SS.COMPLETE) {
      fromSpace().prepare(true); // make from space moving at last minute
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
    TransitiveClosure.registerSpecializedScan(SCAN_SS, SSTraceLocal.class);
    super.registerSpecializedMethods();
  }
}
