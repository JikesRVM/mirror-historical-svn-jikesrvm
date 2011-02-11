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
public class SapphireTraceLocalSecond extends TraceLocal {
  /**
   * Constructor
   */
  public SapphireTraceLocalSecond(Trace trace, boolean specialized) {
    super(specialized ? Sapphire.SECOND_SCAN_SS : -1, trace);
  }

  /**
   * Constructor
   */
  public SapphireTraceLocalSecond(Trace trace) {
    this(trace, false); // LPJH: disable specialized scanning
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
    if (Sapphire.inFromSpace(object)) {
      return Sapphire.fromSpace().isLive(object);
    } else if (Sapphire.inToSpace(object)) {
      return true;
    } else
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
    if (Sapphire.inFromSpace(object)) {
      ObjectReference obj = traceObject(this, object, Sapphire.ALLOC_REPLICATING, true);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(Sapphire.inToSpace(obj));
      }
      return obj;
    }
    if (Sapphire.inToSpace(object)) {
      ObjectReference obj = traceObject(this, object, Sapphire.ALLOC_REPLICATING, false);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(Sapphire.inToSpace(obj));
      }
      return obj;
    }
    return super.traceObject(object);
  }

  /**
   * Will this object move from this point on, during the current trace ?
   *
   * @param object The object to query.
   * @return True if the object will not move.
   */
  public boolean willNotMoveInCurrentCollection(ObjectReference object) {
    return !Sapphire.inFromSpace(object);
  }

  /**
   * Trace an object under a copying collection policy. We use a tri-state algorithm to deal with races to forward the object. The
   * tracer must wait if the object is concurrently being forwarded by another thread. If the object is already forwarded, the copy
   * is returned. Otherwise, the object is forwarded and the copy is returned.
   * 
   * @param trace
   *          The trace being conducted.
   * @param object
   *          The object to be forwarded.
   * @param allocator
   *          The allocator to use when copying.
   * @return The forwarded object.
   */
  @Inline
  public ObjectReference traceObject(TransitiveClosure trace, ObjectReference object, int allocator, boolean fromSpace) {
    /* If the object in question is already in to-space, then do nothing */
    if (!fromSpace) {
      if (VM.VERIFY_ASSERTIONS) {
        if (Options.verbose.getValue() >= 9) {
          Log.write("2 Tracing ToSpace... ");
          Log.writeln(object);
        }
        VM.assertions._assert(Sapphire.inToSpace(object));
        VM.assertions._assert(!ForwardingWord.isBusy(object)); // toSpace should not be marked busy
      }
      return object;
    }

    if (VM.VERIFY_ASSERTIONS) {
      if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) {
        Log.write("2 Tracing FromSpace... ");
        Log.writeln(object);
      }
      VM.assertions._assert(Sapphire.inFromSpace(object));
      if (ForwardingWord.getReplicaPointer(object).isNull()) { // should already have a FP
        Log.write("Argh... Missing replica pointer ");
        Log.writeln(object);
        VM.objectModel.dumpObject(object);
        Log.flush();
        Space.printVMMap();
        VM.assertions.fail("DEAD");
      }
      VM.assertions._assert(!ForwardingWord.isBusy(object));
    }

    /* Try to forward the object */
    Word forwardingWord = ForwardingWord.attemptToDirectlyMarkForward(object);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!ForwardingWord.stateIsBeingForwarded(forwardingWord)); // must not be marked
                                                                                                            // busy
    if (ForwardingWord.stateIsForwardedOrBeingForwarded(forwardingWord)) {
      /* Somebody else got to it first just extract the FP and return */
      if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9)
        Log.writeln("2nd Trace: state is Being Forwarded or Forwarded, returning FP... ");
      /* Now extract the object reference from the forwarding word and return it */
      ObjectReference obj = ForwardingWord.getReplicaPointer(object);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(Sapphire.inToSpace(obj));
        VM.assertions._assert(isLive(object));
        VM.assertions._assert(!ForwardingWord.isBusy(object));
      }
      return obj;
    } else {
      /* We are the designated copier, so forward it and enqueue it */
      if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) {
        Log.write("2nd Trace: we are desingated copier... ");
        Log.writeln(object);
      }
      ObjectReference newObject = ForwardingWord.getReplicaPointer(object);
      VM.objectModel.fillInReplica(object, newObject);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(!ForwardingWord.isBusy(newObject));
        VM.assertions._assert(!ForwardingWord.isForwarded(newObject));
        VM.assertions._assert(Sapphire.inToSpace(newObject));
        VM.assertions._assert(isLive(object));
        VM.assertions._assert(!ForwardingWord.isBusy(object)); // we should have never set BUSY flag
      }
      trace.processNode(newObject); // Scan it later
      if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) {
        Log.write("2nd Trace F[");
        Log.write(object);
        Log.write("/");
        Log.write(Space.getSpaceForObject(object).getName());
        Log.write("] -> ");
        Log.write(newObject);
        Log.write("/");
        Log.write(Space.getSpaceForObject(newObject).getName());
        Log.writeln("]");
      }
      return newObject;
    }
  }
}
