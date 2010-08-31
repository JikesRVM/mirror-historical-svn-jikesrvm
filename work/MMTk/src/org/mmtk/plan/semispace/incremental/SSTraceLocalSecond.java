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
import org.mmtk.policy.ReplicatingSpace;
import org.mmtk.policy.Space;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.Log;
import org.mmtk.utility.options.Options;
import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

@Uninterruptible
public class SSTraceLocalSecond extends TraceLocal {
  /**
   * Constructor
   */
  public SSTraceLocalSecond(Trace trace, boolean specialized) {
    super(specialized ? SS.SECOND_SCAN_SS : -1, trace);
  }

  /**
   * Constructor
   */
  public SSTraceLocalSecond(Trace trace) {
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
    if (Space.isInSpace(SS.fromSpace().getDescriptor(), object)) {
      return SS.fromSpace().isLive(object);
    } else if (Space.isInSpace(SS.toSpace().getDescriptor(), object)) {
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
    if (Space.isInSpace(SS.fromSpace().getDescriptor(), object)) {
      ObjectReference obj = SS.fromSpace().traceObject2(this, object, SS.ALLOC_SS, true);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(SS.inToSpace(obj.toAddress()));
      }
      return obj;
    }
    if (Space.isInSpace(SS.toSpace().getDescriptor(), object)) {
      ObjectReference obj = SS.toSpace().traceObject2(this, object, SS.ALLOC_SS, false);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(SS.inToSpace(obj.toAddress()));
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
    return !Space.isInSpace(SS.fromSpace().getDescriptor(), object);
  }
}
