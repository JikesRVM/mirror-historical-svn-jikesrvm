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
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.LargeObjectLocal;
import org.mmtk.policy.Space;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.Log;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.vm.VM;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

@Uninterruptible
public class SapphireCollector extends StopTheWorldCollector {

  /****************************************************************************
   * Instance fields
   */

  protected final SapphireTraceLocalFirst firstTrace;
  protected final SapphireTraceLocalSecond secondTrace;
  protected final CopyLocal ss;
  protected final LargeObjectLocal los;

  private static final PreGCToSpaceLinearSanityScan preGCSanity = new PreGCToSpaceLinearSanityScan();
  private static final PostGCToSpaceLinearSanityScan postGCSanity = new PostGCToSpaceLinearSanityScan();

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Constructor
   */
  public SapphireCollector() {
    this(new SapphireTraceLocalFirst(global().toBeScannedRemset), new SapphireTraceLocalSecond(global().toBeCopiedRemset));
  }

  /**
   * Constructor
   * @param tr The trace to use
   */
  protected SapphireCollector(SapphireTraceLocalFirst tr, SapphireTraceLocalSecond tr2) {
    ss = new CopyLocal();
    ss.rebind(Sapphire.toSpace());
    los = new LargeObjectLocal(Plan.loSpace);
    firstTrace = tr;
    secondTrace = tr2;
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
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(bytes > Plan.MAX_NON_LOS_COPY_BYTES);
        VM.assertions.fail("Should not copy into LOS");
      }
      return los.alloc(bytes, align, offset);
    } else {
      if (VM.VERIFY_ASSERTIONS) {
//        VM.assertions._assert(bytes <= Plan.MAX_NON_LOS_COPY_BYTES);
        VM.assertions._assert(allocator == Sapphire.ALLOC_SS);
      }
      return ss.alloc(bytes, align, offset);
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
        ss.linearScan(preGCSanity);
      }
      
      los.prepare(true);
      super.collectionPhase(phaseId, primary);
      return;
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
        ss.linearScan(postGCSanity);
        Sapphire.tackOnLock.acquire();
        Sapphire.deadThreadsBumpPointer.tackOn(ss);
        Sapphire.tackOnLock.release();
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
      return firstTrace;
    else if (Sapphire.currentTrace == 2)
      return secondTrace;
    else {
      VM.assertions.fail("Unknown currentTrace value");
      return null;
    }
  }

  protected boolean concurrentTraceComplete() {
    return !global().toBeScannedRemset.hasWork();
  }
}
