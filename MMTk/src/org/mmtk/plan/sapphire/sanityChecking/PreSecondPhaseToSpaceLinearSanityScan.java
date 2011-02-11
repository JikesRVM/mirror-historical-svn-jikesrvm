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
import org.mmtk.utility.alloc.LinearScan;
import org.mmtk.vm.VM;
import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * Callbacks from BumpPointer during a linear scan are dispatched through
 * a subclass of this object.
 */
@Uninterruptible
public class PreSecondPhaseToSpaceLinearSanityScan extends LinearScan {
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
        VM.assertions._assert(Sapphire.inToSpace(object));
        VM.assertions._assert(!ForwardingWord.isBusy(object)); // toSpace should never be marked BUSY
        VM.assertions._assert(!ForwardingWord.isForwarded(object)); // toSpace should never be marked FORWARDED
        ObjectReference bp = ForwardingWord.getReplicaPointer(object);
        VM.assertions._assert(bp.isNull()); // no BP in toSpace for the moment
      }
    }
  }
}
