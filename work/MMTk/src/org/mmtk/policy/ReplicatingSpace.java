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
  public static final int LOCAL_GC_BITS_REQUIRED = 2;
  public static final int GLOBAL_GC_BITS_REQUIRED = 0;
  public static final int GC_HEADER_WORDS_REQUIRED = 1;

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
  public ReplicatingSpace(String name, int pageBudget, VMRequest vmRequest) {
    super(name, pageBudget, vmRequest);
  }

  // @Inline
  // public ObjectReference traceObject(TransitiveClosure trace, ObjectReference object) {
  // trace.processNode(object); // LPJH: probably want to conditionalise this to only if obj is not forwarded
  // return object;
  // }

  public boolean isLive(ObjectReference object) {
    if (Sapphire.currentTrace == 2) {
      // must have FP and be FORWARDED to be live
      return (ForwardingWord.isForwardedOrBeingForwarded(object) && !ForwardingWord.getReplicatingFP(object).isNull());
    } else if (Sapphire.currentTrace == 1 ){
      // live just means having a FP
      return (!ForwardingWord.getReplicatingFP(object).isNull());
    } else {
      VM.assertions.fail("Unknown currentTrace value");
      return false;
    }
  }
  
  /**
   * Trace an object under a copying collection policy.
   *
   * We use a tri-state algorithm to deal with races to forward
   * the object.  The tracer must wait if the object is concurrently
   * being forwarded by another thread.
   *
   * If the object is already forwarded, the copy is returned.
   * Otherwise, the object is forwarded and the copy is returned.
   *
   * @param trace The trace being conducted.
   * @param object The object to be forwarded.
   * @param allocator The allocator to use when copying.
   * @return The forwarded object.
   */
  @Inline
  public ObjectReference traceObject(TransitiveClosure trace, ObjectReference object, int allocator, boolean fromSpace) {
    /* If the object in question is already in to-space, then do nothing */
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(fromSpace, "Should not have any toSpace referecnes in first trace");
      VM.assertions._assert(Space.isInSpace(Sapphire.fromSpace().getDescriptor(), object));
      VM.assertions._assert(!ForwardingWord.isBusy(object));
    }

    // Check if already has a ForwardingPointer
    ObjectReference forwarded = ForwardingWord.getReplicatingFP(object);
    if (!forwarded.isNull()) {
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(Sapphire.inToSpace(forwarded.toAddress()));
        VM.assertions._assert(isLive(object));
      }
      return object;  // return fromSpace reference
    }

    // attempt to CAS busy into object, if we win alloc space for replica 
    ForwardingWord.atomicMarkBusy(object);
    forwarded = ForwardingWord.getReplicatingFP(object);
    if (forwarded.isNull()) {
      // we are designated copier
      ObjectReference newObject = VM.objectModel.copy(object, allocator); // LPJH: this needs optimising (this is doing the copy)
      if (VM.VERIFY_ASSERTIONS) {
        if (!Sapphire.inToSpace(newObject.toAddress())) {
          Log.writeln("Copy returned an object with an address not in toSpace:");
          Log.writeln(newObject.toAddress());
          Space.printVMMap();
          Log.flush();
        }
      }
      ForwardingWord.setReplicatingFP(object, newObject);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(ForwardingWord.isBusy(object));
        VM.assertions._assert(!newObject.isNull());
        VM.assertions._assert(Sapphire.inToSpace(newObject.toAddress()));
        VM.assertions._assert(isLive(object));
        if (Options.verbose.getValue() >= 9) {
          Log.write("Trace 1 C[");
          Log.write(object);
          Log.write("/");
          Log.write(Sapphire.fromSpace().getName());
          Log.write("] -> ");
          Log.write(newObject);
          Log.write("/");
          Log.write(Space.getSpaceForObject(newObject).getName());
          Log.writeln("]");
        }
      }
      trace.processNode(object); // Scan it later
      ForwardingWord.markNotBusy(object);
      return object;  // return fromSpace reference
    } else {
      // someone else has copied the object behind our back
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(ForwardingWord.isBusy(object));
        VM.assertions._assert(!ForwardingWord.isBusy(forwarded)); // toSpace should not be busy
        VM.assertions._assert(Sapphire.inToSpace(forwarded.toAddress()));
        VM.assertions._assert(isLive(object));
      }
      ForwardingWord.markNotBusy(object);
      return object;  // return fromSpace reference
    }
  }
  
  /**
   * Trace an object under a copying collection policy.
   *
   * We use a tri-state algorithm to deal with races to forward
   * the object.  The tracer must wait if the object is concurrently
   * being forwarded by another thread.
   *
   * If the object is already forwarded, the copy is returned.
   * Otherwise, the object is forwarded and the copy is returned.
   *
   * @param trace The trace being conducted.
   * @param object The object to be forwarded.
   * @param allocator The allocator to use when copying.
   * @return The forwarded object.
   */
  @Inline
  public ObjectReference traceObject2(TransitiveClosure trace, ObjectReference object, int allocator, boolean fromSpace) {
    /* If the object in question is already in to-space, then do nothing */
    if (!fromSpace) {
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(Sapphire.inToSpace(object.toAddress()));
        VM.assertions._assert(!ForwardingWord.isBusy(object));  // toSpace should not be marked busy
        if (Options.verbose.getValue() >= 9) {
          Log.write("2 Tracing ToSpace... "); Log.writeln(object);
        }
      }
      return object;
    }

    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(Space.isInSpace(Sapphire.fromSpace().getDescriptor(), object));
      VM.assertions._assert(!ForwardingWord.getReplicatingFP(object).isNull()); // should already have a FP
      VM.assertions._assert(!ForwardingWord.isBusy(object));
      if (VM.VERIFY_ASSERTIONS &&  Options.verbose.getValue() >= 9) {
        Log.write("2 Tracing FromSpace... "); Log.writeln(object);
      }
    }
    
    /* Try to forward the object */
    Word forwardingWord = ForwardingWord.attemptToDirectlyMarkForward(object);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!ForwardingWord.stateIsBeingForwarded(forwardingWord)); // must not be marked busy
    if (ForwardingWord.stateIsForwardedOrBeingForwarded(forwardingWord)) {
      /* Somebody else got to it first just extract the FP and return*/
      if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) 
        Log.write("2 Tracing state is Being Forwarded... ");
      /* Now extract the object reference from the forwarding word and return it */
      ObjectReference obj =  ForwardingWord.getReplicatingFP(object);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(Sapphire.inToSpace(obj.toAddress()));
        VM.assertions._assert(isLive(object));
        VM.assertions._assert(!ForwardingWord.isBusy(object));
      }
      return obj;
    } else {
      /* We are the designated copier, so forward it and enqueue it */
      if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) {
        Log.write("2 Tracing we are desingated copier... "); Log.writeln(object);
      }
      // LPJH: must add copying logic here
      ObjectReference newObject =  ForwardingWord.getReplicatingFP(object);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(Sapphire.inToSpace(newObject.toAddress()));
        VM.assertions._assert(isLive(object));
        VM.assertions._assert(!ForwardingWord.isBusy(object)); // we should have never set BUSY flag
      }
      trace.processNode(newObject); // Scan it later
      if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) {
        Log.write("Trace 2 C["); Log.write(object); Log.write("/");
        Log.write(getName()); Log.write("] -> ");
        Log.write(newObject); Log.write("/");
        Log.write(Space.getSpaceForObject(newObject).getName());
        Log.writeln("]");
      }
      return newObject;
    }
  }
}
