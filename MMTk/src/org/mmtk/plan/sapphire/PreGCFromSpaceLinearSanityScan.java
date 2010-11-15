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

import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.Log;
import org.mmtk.utility.alloc.LinearScan;
import org.mmtk.vm.VM;
import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * Callbacks from BumpPointer during a linear scan are dispatched through
 * a subclass of this object.
 */
@Uninterruptible
public class PreGCFromSpaceLinearSanityScan extends LinearScan {
  /**
   * Scan an object. ToSpace must not contain pointers to fromSpace after a complete trace
   * @param object The object to scan
   */
  public void scan(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) {
      if (!object.isNull()) {
        // Log.write("PreGC FromSpace Scanning... "); Log.writeln(object);
        VM.assertions._assert(Sapphire.inFromSpace(object)); // ensure in right space
        // if (VM.scanning.pointsToForwardedObjects(object)) {
        // Log.write("PreGCFromSpaceLinearSanityScan: Object ");
        // Log.write(object);
        // Log.writeln(" contained references to a forwarded fromSpace object");
        // VM.assertions.fail("Died during linear sanity scan");
        // }

        VM.assertions._assert(!ForwardingWord.isBusy(object));
        VM.assertions._assert(!ForwardingWord.isForwarded(object)); // will not get to code below at the moment

        if (ForwardingWord.isForwardedOrBeingForwarded(object)) {
          VM.assertions._assert(ForwardingWord.isForwarded(object)); // can't be half way through copying the object
          ObjectReference forwarded = ForwardingWord.getReplicaPointer(object);
          VM.assertions._assert(Sapphire.inToSpace(forwarded));
          VM.assertions._assert(VM.assertions.validRef(forwarded));
          // follow FP and then follow BP - hope we end up at the same object!
          ObjectReference bpObj = ForwardingWord.getReplicaPointer(forwarded);
          VM.assertions._assert(object == bpObj);
          VM.objectModel.checkFromSpaceReplicatedObject(object, forwarded);
        } else {
          VM.objectModel.checkFromSpaceNotYetReplicatedObject(object);
        }
      }
    }
  }
}

