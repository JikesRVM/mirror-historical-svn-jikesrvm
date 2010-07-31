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

import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.Trace;
import org.mmtk.plan.TransitiveClosure;
import org.mmtk.policy.Space;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.Log;
import org.mmtk.utility.options.Options;
import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

@Uninterruptible
public class SSTraceLocal extends TraceLocal {
  /**
   * Constructor
   */
  public SSTraceLocal(Trace trace, boolean specialized) {
    super(specialized ? SS.SCAN_SS : -1, trace);
  }

  /**
   * Constructor
   */
  public SSTraceLocal(Trace trace) {
    this(trace, true);
  }

  final static int numCopiesPerGCAllowed = 80000; // total number of objects copied is allowed to be 1 more than this
  volatile int numObjectsCopied = 0;

  public int getNumObjectsCopied() {
    return numObjectsCopied;
  }

  /****************************************************************************
   *
   * Externally visible Object processing and tracing
   */

  /**
   * Return true if <code>obj</code> is a live object.
   *
   * @param object The object in question
   * @return True if <code>obj</code> is a live object.
   */
  public boolean isLive(ObjectReference object) {
    if (object.isNull()) return false;
    if (Space.isInSpace(SS.fromSpace().getDescriptor(), object)) {
      if (SS.copyingAllComplete == true) {
        return ForwardingWord.isForwarded(object);
      } else
        return true;
    }
    if (Space.isInSpace(SS.toSpace().getDescriptor(), object))
      return true;
    return super.isLive(object);
  }


  /**
   * This method is the core method during the trace of the object graph.
   * The role of this method is to:
   *
   * 1. Ensure the traced object is not collected.
   * 2. If this is the first visit to the object enqueue it to be scanned.
   * 3. Return the forwarded reference to the object.
   *
   * @param object The object to be traced.
   * @return The new reference to the same object instance.
   */
  @Inline
  public ObjectReference traceObject(ObjectReference object) {
    if (object.isNull()) return object;
    if (Space.isInSpace(SS.fromSpace().getDescriptor(), object))
      return traceObject(this, object, SS.ALLOC_SS, true);
    if (Space.isInSpace(SS.toSpace().getDescriptor(), object))
      return traceObject(this, object, SS.ALLOC_SS, false);
    return super.traceObject(object);
  }

  /**
   * Will this object move from this point on, during the current trace ?
   *
   * @param object The object to query.
   * @return True if the object will not move.
   */
  public boolean willNotMoveInCurrentCollection(ObjectReference object) {
    // return !Space.isInSpace(SS.fromSpace().getDescriptor(), object);
    return true;
  }

  /**
   * Trace an object under a copying collection policy. We use a tri-state algorithm to deal with races to forward the object. The
   * tracer must wait if the object is concurrently being forwarded by another thread. If the object is already forwarded, the copy
   * is returned. Otherwise, the object is forwarded and the copy is returned.
   * @param trace The trace being conducted.
   * @param object The object to be forwarded.
   * @param allocator The allocator to use when copying.
   * @param fromSpace if we are tracing an object in fromSpace
   * @return The forwarded object.
   */
  @Inline
  public ObjectReference traceObject(TransitiveClosure trace, ObjectReference object, int allocator, boolean fromSpace) {
    /* If the object in question is already in to-space, then do nothing */
    if (!fromSpace)
      return object;

    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(Space.isInSpace(SS.fromSpace().getDescriptor(), object));
    }

    // Check if already has FP
    if (ForwardingWord.isForwardedOrBeingForwarded(object)) {
      Word forwardingWord;
      do {
        forwardingWord = VM.objectModel.readAvailableBitsWord(object);
      } while (ForwardingWord.stateIsBeingForwarded(forwardingWord));
      return object; // ForwardingWord.getReplicatingFP(object);
    }

    // Not yet copied - Check copying budget for this GC cycle
    if (SS.copyingAllComplete || numObjectsCopied <= numCopiesPerGCAllowed) {
      /* Try to forward the object */
      Word forwardingWord = ForwardingWord.attemptToForward(object);

      if (ForwardingWord.stateIsForwardedOrBeingForwarded(forwardingWord)) {
        /* Somebody else got to it first. */

        /* We must wait (spin) if the object is not yet fully forwarded */
        while (ForwardingWord.stateIsBeingForwarded(forwardingWord))
          forwardingWord = VM.objectModel.readAvailableBitsWord(object);

        /* Now extract the object reference from the forwarding word and return it */
        return object; // ForwardingWord.getReplicatingFP(object);
      } else {
        /* We are the designated copier, so forward it and enqueue it */
        numObjectsCopied++; // increment count of objects copied
        ObjectReference newObject = VM.objectModel.copy(object, allocator);
        ForwardingWord.setReplicatingFP(object, newObject);
        // ForwardingWord.setForwardingPointer(object, newObject);
        trace.processNode(newObject); // Scan it later

        if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) {
          Log.write("C[");
          Log.write(object);
          Log.write("/");
          Log.write(SS.fromSpace().getName());
          Log.write("] -> ");
          Log.write(newObject);
          Log.write("/");
          Log.write(Space.getSpaceForObject(newObject).getName());
          Log.writeln("]");
        }
        return object; // newObject;
      }
    }

    // Copy not allowed at this stage
    return object;
  }
}
