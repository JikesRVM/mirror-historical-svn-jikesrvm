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

import org.mmtk.policy.Space;
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
public class PostGCToSpaceLinearSanityScan extends LinearScan {
  /**
   * Scan an object. ToSpace must not contain pointers to fromSpace after a complete trace
   * @param object The object to scan
   */
  public void scan(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) {
      if (!object.isNull()) {
        // Log.write("Scanning... "); Log.writeln(object);
        // if (VM.scanning.pointsToForwardedObjects(object)) {
        // Log.write("PostGCToSpaceLinearSanityScan: Object ");
        // Log.write(object);
        // Log.writeln(" contained references to a forwarded fromSpace");
        // VM.assertions.fail("Died during linear sanity scan");
        // }

        // flip has occured
        VM.assertions._assert(!ForwardingWord.isBusy(object));
        VM.assertions._assert(!ForwardingWord.isForwarded(object));
        VM.assertions._assert(ForwardingWord.getReplicatingFP(object).isNull());
        VM.assertions._assert(Space.isInSpace(Sapphire.fromSpace().getDescriptor(), object));

        if (VM.scanning.pointsTo(object, Sapphire.toSpace().getDescriptor())) {
          Log.write("PostGCToSpaceLinearSanityScan: Object ");
          Log.write(object);
          Log.writeln(" contained references to toSpace");
          VM.assertions.fail("Died during linear sanity scan");
        }
      }
    }
  }
}
