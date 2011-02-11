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

import org.mmtk.plan.*;
import org.mmtk.plan.concurrent.ConcurrentCollector;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.LargeObjectLocal;
import org.mmtk.policy.Space;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.Log;
import org.mmtk.vm.VM;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

@Uninterruptible
public class SapphireCollector extends ConcurrentCollector {

  /****************************************************************************
   * Instance fields
   */

  protected final SapphireTraceLocalFirst localFirstTrace;
  protected final SapphireTraceLocalSecond localSecondTrace;
  protected final CopyLocal ss;
  protected final LargeObjectLocal los;


  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Constructor
   */
  public SapphireCollector() {
    this(new SapphireTraceLocalFirst(global().globalFirstTrace), new SapphireTraceLocalSecond(global().globalSecondTrace));
  }

  /**
   * Constructor
   * @param tr The trace to use
   */
  protected SapphireCollector(SapphireTraceLocalFirst tr, SapphireTraceLocalSecond tr2) {
    ss = new CopyLocal();
    ss.rebind(Sapphire.toSpace());
    los = new LargeObjectLocal(Plan.loSpace);
    localFirstTrace = tr;
    localSecondTrace = tr2;
  }

  /****************************************************************************
   *
   * Collection-time allocation
   */

  /**
   * Allocate space for copying an object (this method <i>does not</i>
   * copy the object, it only allocates space)
   *
   * @param original A reference to the original object
   * @param bytes The size of the space to be allocated (in bytes)
   * @param align The requested alignment.
   * @param offset The alignment offset.
   * @return The address of the first byte of the allocated region
   */
  @Inline
  public Address allocCopy(ObjectReference original, int bytes,
      int align, int offset, int allocator) {
    if (allocator == Plan.ALLOC_LOS) {
      if (VM.VERIFY_ASSERTIONS)
        VM.assertions._assert(bytes > Plan.MAX_NON_LOS_COPY_BYTES);
      VM.assertions.fail("Sapphire debugging - should not copy into LOS at present");
      return los.alloc(bytes, align, offset);
    } else {
      if (VM.VERIFY_ASSERTIONS) {
        // VM.assertions._assert(bytes <= Plan.MAX_NON_LOS_COPY_BYTES); // LPJH: when allow copying to LOS then uncomment this
        VM.assertions._assert(allocator == Sapphire.ALLOC_REPLICATING);
      }
      Address addy =  ss.alloc(bytes, align, offset);
      // Log.write("AllocCopy "); Log.writeln(addy.plus(16)); // hard coded nasty hack
      return addy;
    }
  }

  /**
   * Perform any post-copy actions.
   *
   * @param object The newly allocated object
   * @param typeRef the type reference for the instance being created
   * @param bytes The size of the space to be allocated (in bytes)
   */
  @Inline
  public void postCopy(ObjectReference from, ObjectReference to, ObjectReference typeRef,
      int bytes, int allocator) {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(ForwardingWord.isBusy(from));
      VM.assertions._assert(!ForwardingWord.isForwarded(from));
    }
    ForwardingWord.clearForwardingBits(to);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!ForwardingWord.isBusy(to));
      VM.assertions._assert(!ForwardingWord.isForwarded(to));
    }
    if (allocator == Plan.ALLOC_LOS)
      Plan.loSpace.initializeHeader(to, false);
    if (VM.VERIFY_ASSERTIONS) {
//      VM.assertions._assert(getCurrentTrace().isLive(from));  // FP is installed after Copy
      VM.assertions._assert(getCurrentTrace().willNotMoveInCurrentCollection(to));
    }
  }

  /**
   * Run-time check of the allocator to use for a given copy allocation
   *
   * At the moment this method assumes that allocators will use the simple
   * (worst) method of aligning to determine if the object is a large object
   * to ensure that no objects are larger than other allocators can handle.
   *
   * @param from The object that is being copied.
   * @param bytes The number of bytes to be allocated.
   * @param align The requested alignment.
   * @param allocator The allocator statically assigned to this allocation.
   * @return The allocator dyncamically assigned to this allocation.
   */
  @Inline
  public int copyCheckAllocator(ObjectReference from, int bytes, int align, int allocator) {
    // LPJH: later might want to copy some objects to the LOS
    return allocator;
  }

  /****************************************************************************
   *
   * Collection
   */

  /**
   * Perform a per-collector collection phase.
   *
   * @param phaseId The collection phase to perform
   * @param primary Perform any single-threaded activities using this thread.
   */
  @Inline
  public void collectionPhase(short phaseId, boolean primary) {
    if (phaseId == Sapphire.PREPARE) {
      if (Sapphire.currentTrace == 1) {
        // first trace
        // rebind the copy bump pointer to the appropriate semispace.
        ss.rebind(Sapphire.toSpace());
      } else {
        // second trace
      }
      los.prepare(true);
      super.collectionPhase(phaseId, primary);
      return;
    }

    if (phaseId == Sapphire.PRE_TRACE_LINEAR_SCAN) {
      if (Sapphire.currentTrace == 0) { // run *before* 1st trace
        Log.writeln("Collector running preFirstPhaseToSpaceLinearSanityScan");
        ss.linearScan(Sapphire.preFirstPhaseToSpaceLinearSanityScan);
        return;
      }
      if (Sapphire.currentTrace == 2) { // run *after* we switch to 2nd trace but *before* we actually do anything
        Log.writeln("Collector running preSecondPhaseToSpaceLinearSanityScan");
        ss.linearScan(Sapphire.preSecondPhaseToSpaceLinearSanityScan);
        return;
      }
    }

    if (phaseId == Sapphire.POST_TRACE_LINEAR_SCAN) {
      if (Sapphire.currentTrace == 1) {
        Log.writeln("Collector running postFirstPhaseToSpaceLinearSanityScan");
        ss.linearScan(Sapphire.postFirstPhaseToSpaceLinearSanityScan);
        return;
      }
      if (Sapphire.currentTrace == 2) {
        Log.writeln("Collector running postSecondPhaseToSpaceLinearSanityScan");
        ss.linearScan(Sapphire.postSecondPhaseToSpaceLinearSanityScan); // flip has occurred
        return;
      }
    }

    if (phaseId == Sapphire.CLOSURE) {
      getCurrentTrace().completeTrace();
      return;
    }

    if (phaseId == Sapphire.RELEASE) {
      getCurrentTrace().release();
      los.release(true);
      super.collectionPhase(phaseId, primary);
      return;
    }

    if (phaseId == Sapphire.COMPLETE) {
      if (Sapphire.currentTrace == 2) {
        // second trace
        Sapphire.deadBumpPointersLock.acquire();
        Sapphire.deadFromSpaceBumpPointers.tackOn(ss);
        Sapphire.deadBumpPointersLock.release();
        ss.reset(); // reset the bump pointer as it will not be used for any more alloc during this GC
      } else {
        // current trace 1
      }
      super.collectionPhase(phaseId, primary);
      return;
    }

    super.collectionPhase(phaseId, primary);
  }


  /****************************************************************************
   *
   * Object processing and tracing
   */

  /**
   * Return true if the given reference is to an object that is within
   * one of the semi-spaces.
   *
   * @param object The object in question
   * @return True if the given reference is to an object that is within
   * one of the semi-spaces.
   */
  public static boolean isSemiSpaceObject(ObjectReference object) {
    return Space.isInSpace(Sapphire.SS0, object) || Space.isInSpace(Sapphire.SS1, object);
  }

  /****************************************************************************
   *
   * Miscellaneous
   */

  /** @return The active global plan as an <code>SS</code> instance. */
  @Inline
  private static Sapphire global() {
    return (Sapphire) VM.activePlan.global();
  }

  /** @return the current trace object. */
  public TraceLocal getCurrentTrace() {
    if (Sapphire.currentTrace == 1)
      return localFirstTrace;
    else if (Sapphire.currentTrace == 2)
      return localSecondTrace;
    else {
      VM.assertions.fail("Unknown currentTrace value");
      return null;
    }
  }

  protected boolean concurrentTraceComplete() {
    return (!global().globalFirstTrace.hasWork()) && (!global().globalSecondTrace.hasWork());
  }
}
