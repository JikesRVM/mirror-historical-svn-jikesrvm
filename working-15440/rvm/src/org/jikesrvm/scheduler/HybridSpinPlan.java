/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.scheduler;

import org.jikesrvm.VM;
import org.jikesrvm.Constants;
import org.jikesrvm.Services;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.objectmodel.ThinLockConstants;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.NoNullCheck;
import org.vmmagic.pragma.NonMoving;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

public class HybridSpinPlan extends AbstractSpinPlan {
  WaitingSpinPlan waiting=new WaitingSpinPlan();
  YieldingSpinPlan yielding=new YieldingSpinPlan();
  
  public void interruptibleSpin(int cnt,int latestContenderID) {
    if (cnt<2) {
      waiting.interruptibleSpin(cnt,latestContenderID);
    } else {
      yielding.interruptibleSpin(cnt,latestContenderID);
    }
  }
  
  @Uninterruptible
  public void uninterruptibleSpin(int cnt,int latestContenderID) {
    if (cnt<2) {
      waiting.uninterruptibleSpin(cnt,latestContenderID);
    } else {
      yielding.uninterruptibleSpin(cnt,latestContenderID);
    }
  }
}


