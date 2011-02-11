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
import org.mmtk.policy.Space;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.Log;
import org.mmtk.utility.options.Options;
import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

@Uninterruptible
public class SapphireTraceLocalFirst extends TraceLocal {
  /**
   * Constructor
   */
  public SapphireTraceLocalFirst(Trace trace, boolean specialized) {
    super(specialized ? Sapphire.FIRST_SCAN_SS : -1, trace);
  }

  /**
   * Constructor
   */
  public SapphireTraceLocalFirst(Trace trace) {
    this(trace, false); // LPJH: disable specialized scanning
  }

  /****************************************************************************
   *
   * Externally visible Object processing and tracing
   */

  /**
   * Should reference values be overwritten as the heap is traced?
   */
  protected boolean overwriteReferenceDuringTrace() {
    return false;
  }

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
      VM.assertions.fail("First trace should not have got hold of a toSpace reference");
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
      ObjectReference obj = traceObject(object, Sapphire.ALLOC_REPLICATING);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(Sapphire.inFromSpace(obj)); // 1st trace should return from-space obj
        VM.assertions._assert(isLive(object)); // object should be considered live after tracing
      }
      return obj;
    }
    if (Sapphire.inToSpace(object)) {
      if (VM.VERIFY_ASSERTIONS)
        VM.assertions.fail("Should not have a toSpace reference during first trace");
      return object;
    }
    return super.traceObject(object);
  }

  public void completeTrace() {
    logMessage(4, "Processing GC in parallel with Sapphire Hack");
    if (!rootLocations.isEmpty()) {
      processRoots();
    }
    logMessage(5, "processing gray objects with Sapphire Hack");
    assertMutatorRemsetsFlushed();
    do {
      while (!values.isEmpty()) {
        ObjectReference v = values.pop();
        // this work here is necessary because we don't want the insertion barrier to do any work (allocation)
        // therefore the insertion barrier can't mark objects as live so we must do that before we scan the object
        traceObject(v); // the object we just popped is not yet live, mark it as live
        scanObject(v);  // now that we have made the object live, scan it's children
      }
      processRememberedSets();
    } while (!values.isEmpty());
    assertMutatorRemsetsFlushed();
  }

  /**
   * Process GC work until either complete or workLimit
   * units of work are completed.
   *
   * @param workLimit The maximum units of work to perform.
   * @return True if all work was completed within workLimit.
   */
  @Inline
  public boolean incrementalTrace(int workLimit) {
    logMessage(4, "Continuing GC in parallel (incremental) with Sapphire Hack");
    logMessage(5, "processing gray objects with Sapphire hack");
    int units = 0;
    do {
      while (!values.isEmpty() && units < workLimit) {
        ObjectReference v = values.pop();
        // this work here is necessary because we don't want the insertion barrier to do any work (allocation)
        // therefore the insertion barrier can't mark objects as live so we must do that before we scan the object
        traceObject(v); // the object we just popped is not yet live, mark it as live
        scanObject(v);  // now that we have made the object live, scan it's children
        units++;
      }
    } while (!values.isEmpty() && units < workLimit);
    return values.isEmpty();
  }

  /**
   * Will this object move from this point on, during the current trace ?
   *
   * @param object The object to query.
   * @return True if the object will not move.
   */
  public boolean willNotMoveInCurrentCollection(ObjectReference object) {
    // if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!Sapphire.inToSpace(object));  // does not work, called with toSpace ref from postCopy
    return !Sapphire.inFromSpace(object);
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
  private ObjectReference traceObject(ObjectReference object, int allocator) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(Sapphire.inFromSpace(object));

    // Check if already has a ForwardingPointer
    ObjectReference forwarded = ForwardingWord.getReplicaPointer(object);
    if (!forwarded.isNull()) {
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isLive(object));
      return object;  // return fromSpace reference
    }

    // attempt to CAS busy into object, if we win alloc space for replica
    ForwardingWord.atomicMarkBusy(object);
    forwarded = ForwardingWord.getReplicaPointer(object);
    if (forwarded.isNull()) {
      // we are designated thread to alloc space
      ObjectReference newObject = VM.objectModel.createBlankReplica(object, allocator);
      ForwardingWord.setReplicaPointer(object, newObject);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(!newObject.isNull());
        VM.assertions._assert(Sapphire.inToSpace(newObject));
        VM.assertions._assert(isLive(object));
        if (Options.verbose.getValue() >= 9) {
          Log.write("Trace 1 R[");
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
      processNode(object); // Scan it later // LPJH: don't think we need to do this
      ForwardingWord.markNotBusy(object);
      return object;  // return fromSpace reference
    } else {
      // someone else has copied the object behind our back
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(!ForwardingWord.isBusy(forwarded)); // toSpace should not be busy
        VM.assertions._assert(Sapphire.inToSpace(forwarded));
        VM.assertions._assert(isLive(object));
      }
      ForwardingWord.markNotBusy(object);
      return object;  // return fromSpace reference
    }
  }
}
