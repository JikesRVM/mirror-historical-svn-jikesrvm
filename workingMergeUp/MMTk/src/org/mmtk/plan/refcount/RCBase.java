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
package org.mmtk.plan.refcount;

import org.mmtk.plan.StopTheWorld;
import org.mmtk.plan.Trace;
import org.mmtk.plan.refcount.cd.CD;
import org.mmtk.plan.refcount.cd.NullCD;
import org.mmtk.plan.refcount.cd.TrialDeletion;
import org.mmtk.policy.ExplicitFreeListSpace;
import org.mmtk.policy.ExplicitLargeObjectLocal;
import org.mmtk.policy.Space;
import org.mmtk.utility.deque.SharedDeque;
import org.mmtk.utility.heap.VMRequest;
import org.mmtk.utility.options.Options;
import org.mmtk.utility.statistics.EventCounter;

import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * This class implements the global state of a simple reference counting
 * collector.
 *
 * All plans make a clear distinction between <i>global</i> and
 * <i>thread-local</i> activities, and divides global and local state
 * into separate class hierarchies.  Global activities must be
 * synchronized, whereas no synchronization is required for
 * thread-local activities.  There is a single instance of Plan (or the
 * appropriate sub-class), and a 1:1 mapping of PlanLocal to "kernel
 * threads" (aka CPUs).  Thus instance
 * methods of PlanLocal allow fast, unsychronized access to functions such as
 * allocation and collection.
 *
 * The global instance defines and manages static resources
 * (such as memory and virtual memory resources).  This mapping of threads to
 * instances is crucial to understanding the correctness and
 * performance properties of MMTk plans.
 */
@Uninterruptible public abstract class RCBase extends StopTheWorld {

  /****************************************************************************
   * Constants
   */
  public static final boolean WITH_COALESCING_RC         = true;
  public static final boolean INC_DEC_ROOT               = true;
  public static final boolean INLINE_WRITE_BARRIER       = WITH_COALESCING_RC;
  public static final boolean GATHER_WRITE_BARRIER_STATS = false;
  public static final boolean FORCE_FULL_CD              = false;

  // Cycle detection selection
  public static final int     NO_CYCLE_DETECTOR = 0;
  public static final int     TRIAL_DELETION    = 1;
  public static final int     CYCLE_DETECTOR    = TRIAL_DELETION;

  /****************************************************************************
   * Class variables
   */
  public static final ExplicitFreeListSpace rcSpace = new ExplicitFreeListSpace("rc", DEFAULT_POLL_FREQUENCY, VMRequest.create(0.5f));
  public static final int REF_COUNT = rcSpace.getDescriptor();
  public static final ExplicitFreeListSpace smallCodeSpace = new ExplicitFreeListSpace("rc-sm-code", DEFAULT_POLL_FREQUENCY, VMRequest.create());
  public static final int RC_SMALL_CODE = smallCodeSpace.getDescriptor();

  // Counters
  public static EventCounter wbFast;
  public static EventCounter wbSlow;

  // Allocators
  public static final int ALLOC_RC = StopTheWorld.ALLOCATORS;
  public static final int ALLOCATORS = ALLOC_RC + 1;

  // Cycle Detectors
  private NullCD nullCD;
  private TrialDeletion trialDeletionCD;

  /****************************************************************************
   * Instance variables
   */

  public final Trace rcTrace;
  public final SharedDeque decPool;
  public final SharedDeque modPool;
  public final SharedDeque newRootPool;
  public final SharedDeque oldRootPool;
  protected int previousMetaDataPages;

  /**
   * Constructor.
 */
  public RCBase() {
    if (!SCAN_BOOT_IMAGE) {
      VM.assertions.fail("RC currently requires scan boot image");
    }
    if (GATHER_WRITE_BARRIER_STATS) {
      wbFast = new EventCounter("wbFast");
      wbSlow = new EventCounter("wbSlow");
    }
    previousMetaDataPages = 0;
    rcTrace = new Trace(metaDataSpace);
    decPool = new SharedDeque("decPool",metaDataSpace, 1);
    modPool = new SharedDeque("modPool",metaDataSpace, 1);
    newRootPool = new SharedDeque("newRootPool",metaDataSpace, 1);
    oldRootPool = new SharedDeque("oldRootPool",metaDataSpace, 1);
    switch (RCBase.CYCLE_DETECTOR) {
    case RCBase.NO_CYCLE_DETECTOR:
      nullCD = new NullCD();
      break;
    case RCBase.TRIAL_DELETION:
      trialDeletionCD = new TrialDeletion(this);
      break;
    }
  }

  /**
   * Perform any required initialization of the GC portion of the header.
   * Called for objects created at boot time.
   *
   * @param ref the object ref to the storage to be initialized
   * @param typeRef the type reference for the instance being created
   * @param size the number of bytes allocated by the GC system for
   * this object.
   * @param status the initial value of the status word
   * @return The new value of the status word
   */
  @Inline
  public Word setBootTimeGCBits(Address ref, ObjectReference typeRef,
                                int size, Word status) {
    if (WITH_COALESCING_RC) {
      status = status.or(SCAN_BOOT_IMAGE ? RCHeader.LOGGED : RCHeader.UNLOGGED);
    }
    return status;
  }

  /*****************************************************************************
   *
   * Collection
   */

  public static final boolean isRCObject(ObjectReference object) {
    return !object.isNull() && !Space.isInSpace(VM_SPACE, object);
  }

  /**
   * @return Whether last GC is a full GC.
   */
  public boolean lastCollectionFullHeap() {
    return performCycleCollection;
  }

  /**
   * Perform a (global) collection phase.
   *
   * @param phaseId Collection phase
   */
  public void collectionPhase(short phaseId) {
    if (phaseId == SET_COLLECTION_KIND) {
      super.collectionPhase(phaseId);
      if (CC_ENABLED) {
        performCycleCollection = (collectionAttempt > 1) || emergencyCollection || CC_FORCE_FULL;
        if (performCycleCollection && Options.verbose.getValue() > 0) Log.write(" [CC] ");
      }
      return;
    }

    if (phaseId == PREPARE) {
      VM.finalizableProcessor.clear();
      VM.weakReferences.clear();
      VM.softReferences.clear();
      VM.phantomReferences.clear();
      rootTrace.prepare();
      rcSpace.prepare();
      if (CC_BACKUP_TRACE && performCycleCollection) {
        backupTrace.prepare();
      }
      return;
    }

    if (phaseId == CLOSURE) {
      rootTrace.prepare();
      return;
    }

    if (phaseId == BT_CLOSURE) {
      if (CC_BACKUP_TRACE && performCycleCollection) {
        backupTrace.prepare();
      }
      return;
    }

    if (phaseId == PROCESS_OLDROOTBUFFER) {
      oldRootPool.prepare();
      return;
    }

    if (phaseId == PROCESS_NEWROOTBUFFER) {
      newRootPool.prepare();
      return;
    }


    if (phaseId == PROCESS_MODBUFFER) {
      modPool.prepare();
      return;
    }

    if (phaseId == PROCESS_DECBUFFER) {
      decPool.prepare();
      return;
    }

    if (phaseId == RELEASE) {
      rootTrace.release();
      if (CC_BACKUP_TRACE && performCycleCollection) {
        backupTrace.release();
        rcSpace.sweepCells(rcSweeper);
        rcloSpace.sweep(loScanSweeper);
        rcloSpace.sweep(loFreeSweeper);
      } else {
        rcSpace.release();
      }
      return;
    }

    super.collectionPhase(phaseId);
  }

  /*****************************************************************************
   *
   * Accounting
   */

  /**
   * Return the number of pages used given the pending
   * allocation.
   *
   * @return The number of pages reserved given the pending
   * allocation, excluding space reserved for copying.
   */
  public int getPagesUsed() {
    return (rcSpace.reservedPages() + rcloSpace.reservedPages() + super.getPagesUsed());
  }

  /**
   * Perform a linear scan across all objects in the heap to check for leaks.
   */
  public void sanityLinearScan(LinearScan scan) {
    //rcSpace.linearScan(scan);
  }

  /**
   * Return the expected reference count. For non-reference counting
   * collectors this becomes a true/false relationship.
   *
   * @param object The object to check.
   * @param sanityRootRC The number of root references to the object.
   * @return The expected (root excluded) reference count.
   */
  public int sanityExpectedRC(ObjectReference object, int sanityRootRC) {
    if (RCBase.isRCObject(object)) {
      int fullRC = RCHeader.getRC(object);
      if (fullRC == 0) {
        return SanityChecker.DEAD;
      }
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(fullRC >= sanityRootRC);
      return fullRC - sanityRootRC;
    }
    return SanityChecker.ALIVE;
  }

  /**
   * Register specialized methods.
   */
  @Interruptible
  protected void registerSpecializedMethods() {
    super.registerSpecializedMethods();
  }
}
