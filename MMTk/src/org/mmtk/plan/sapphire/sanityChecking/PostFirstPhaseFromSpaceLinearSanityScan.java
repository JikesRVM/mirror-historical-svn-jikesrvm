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
package org.mmtk.plan.sapphire.sanityChecking;

import org.mmtk.plan.sapphire.Sapphire;
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
public class PostFirstPhaseFromSpaceLinearSanityScan extends LinearScan {
  /**
   * Scan an object.
   * 
   * @param object
   *          The object to scan
   */
  public void scan(ObjectReference object) {
    // run during STW phase
    if (VM.VERIFY_ASSERTIONS) {
      if (!object.isNull()) {
        VM.assertions._assert(Sapphire.inFromSpace(object)); // ensure in right space
        VM.assertions._assert(!ForwardingWord.isForwarded(object)); // should not be marked forwarded
        VM.assertions._assert(!ForwardingWord.isBusy(object));
        ObjectReference fp = ForwardingWord.getReplicaPointer(object);
        if (fp == null) {
          VM.objectModel.checkFromSpaceNotYetReplicatedObject(object); // object will not be considered live at end of this trace
        } else {
          // get the replica
          VM.assertions._assert(Sapphire.inToSpace(fp));
          VM.assertions._assert(!ForwardingWord.isBusy(fp)); // toSpace should not be marked BUSY
          ObjectReference bp = ForwardingWord.getReplicaPointer(fp);
          VM.assertions._assert(bp.isNull()); // no BP in toSpace for the moment
          VM.objectModel.checkFromSpaceReplicatedObject(object, fp, false);
        }
        if (VM.scanning.pointsTo(object, Sapphire.toSpace().getDescriptor())) {
          Log.write("PostFirstPhaseFromSpaceLinearSanityScan: Object ");
          Log.write(object);
          Log.writeln(" contained references to toSpace");
          VM.assertions.fail("Died during linear sanity scan");
        }
        // if (VM.objectModel.interestingRef(object)) {
        // Log.writeln("PostFirstPhaseFromSpaceLinearSanityScan found interesting reference details are:");
        // VM.objectModel.dumpObject(object);
        // }
      }
    }
  }
}
