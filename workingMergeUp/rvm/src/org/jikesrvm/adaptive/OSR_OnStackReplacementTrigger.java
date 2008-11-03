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
package org.jikesrvm.adaptive;

import org.jikesrvm.adaptive.controller.Controller;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Offset;

/**
 * Trigger an OSR from a running thread.
 */
public class OSR_OnStackReplacementTrigger {

  /**
   * Trigger an OSR from a running thread.
   */
  @NoInline
  @Uninterruptible
  public static void trigger(int ypTakenInCMID, Offset tsFromFPoff, Offset ypTakenFPoff, int whereFrom) {

    RVMThread thread = RVMThread.getCurrentThread();
    CompiledMethod ypTakenInCM = CompiledMethods.getCompiledMethod(ypTakenInCMID);
    RVMMethod ypTakenInMethod = ypTakenInCM.getMethod();
    boolean isInBootImage = ypTakenInMethod.getDeclaringClass().isInBootImage();

    if (isInBootImage) return;
    OSR_OnStackReplacementEvent event = (OSR_OnStackReplacementEvent) thread.onStackReplacementEvent;
    event.suspendedThread = thread;
    event.whereFrom = whereFrom;
    event.CMID = ypTakenInCMID;
    event.tsFromFPoff = tsFromFPoff;
    event.ypTakenFPoff = ypTakenFPoff;

    thread.monitor().lock();
    thread.requesting_osr = true;
    thread.monitor().unlock();

    Controller.osrOrganizer.activate();
    
    // PNT: make sure there isn't anything funny going on with the context regs
    thread.monitor().lock();
    while (!thread.osr_done) {
      thread.monitor().waitNicely();
    }
    thread.osr_done=false;
    thread.monitor().unlock();
  }
}
