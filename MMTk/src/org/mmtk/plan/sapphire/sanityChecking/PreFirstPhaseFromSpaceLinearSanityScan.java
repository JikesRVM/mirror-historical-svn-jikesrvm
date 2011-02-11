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
public class PreFirstPhaseFromSpaceLinearSanityScan extends LinearScan {
  /**
   * Scan an object.
   * 
   * @param object
   *          The object to scan
   */
  public void scan(ObjectReference object) {
    // run during on-the-fly phase - so objects can be BUSY
    if (VM.VERIFY_ASSERTIONS) {
      if (!object.isNull()) {
        VM.assertions._assert(Sapphire.inFromSpace(object)); // ensure in right space
        VM.assertions._assert(!ForwardingWord.isForwarded(object)); // should not be marked forwarded
        VM.assertions._assert(ForwardingWord.getReplicaPointer(object).isNull()); // should not have a FP
        VM.objectModel.checkFromSpaceNotYetReplicatedObject(object); // check object is allow in fromSpace
        // if (VM.objectModel.interestingRef(object)) {
        // Log.writeln("PreFirstPhaseFromSpaceLinearSanityScan found interesting reference details are:");
        // VM.objectModel.dumpObject(object);
        // }
      }
    }
  }
}

