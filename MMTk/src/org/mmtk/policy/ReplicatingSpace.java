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
package org.mmtk.policy;

import org.mmtk.plan.TransitiveClosure;
import org.mmtk.plan.sapphire.Sapphire;
import org.mmtk.utility.heap.*;
import org.mmtk.utility.options.Options;
import org.mmtk.utility.Constants;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.Log;

import org.mmtk.vm.VM;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * This class implements tracing functionality for a simple copying
 * space.  Since no state needs to be held globally or locally, all
 * methods are static.
 */
@Uninterruptible
public final class ReplicatingSpace extends CopySpace
  implements Constants {

  /****************************************************************************
   *
   * Class variables
   */
  public static final int LOCAL_GC_BITS_REQUIRED = 2; // BUSY and FORWARDED
  public static final int GLOBAL_GC_BITS_REQUIRED = 0;
  public static final int GC_HEADER_WORDS_REQUIRED = 1; // Whole word for forwarding pointer

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * The caller specifies the region of virtual memory to be used for
   * this space.  If this region conflicts with an existing space,
   * then the constructor will fail.
   *
   * @param name The name of this space (used when printing error messages etc)
   * @param pageBudget The number of pages this space may consume
   * before consulting the plan
   * @param fromSpace The does this instance start life as from-space
   * (or to-space)?
   * @param vmRequest An object describing the virtual memory requested.
   */
  public ReplicatingSpace(String name, VMRequest vmRequest) {
    super(name, vmRequest);
  }

  @Inline
  public ObjectReference traceObject(TransitiveClosure trace, ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(Sapphire.currentTrace == 1 || Sapphire.currentTrace == 0); // LPJH: ==0 only here for debugging (testing
                                                                                       // insertion and allocation barrier outside of GC)
      VM.assertions._assert(Sapphire.inFromSpace(object));
    }
    if (ForwardingWord.getReplicaPointer(object).isNull()) trace.processNode(object); // trace obj and give it a replica
    return object;
  }

  public boolean isLive(ObjectReference object) {
    if (!fromSpace) return true; // toSpace live by definition
    if (Sapphire.currentTrace == 2) {
      // must have FP and be FORWARDED to be live
      return (ForwardingWord.isForwardedOrBeingForwarded(object) && !ForwardingWord.getReplicaPointer(object).isNull());
    } else if (Sapphire.currentTrace == 1){
      // live just means having a FP
      return (!ForwardingWord.getReplicaPointer(object).isNull());
    } else if (Sapphire.currentTrace == 0) {
      return true; // always live outside of GC
    } else {
      VM.assertions.fail("Unknown currentTrace value");
      return false;
    }
  }
}
