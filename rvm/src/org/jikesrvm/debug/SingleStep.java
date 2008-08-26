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
package org.jikesrvm.debug;



import org.jikesrvm.ArchitectureSpecific;
import org.jikesrvm.Callbacks;
import org.jikesrvm.Constants;
import org.jikesrvm.UnimplementedError;
import org.jikesrvm.VM;
import org.jikesrvm.ArchitectureSpecific.CodeArray;
import org.jikesrvm.ArchitectureSpecific.Registers;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.baseline.BaselineCompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.compilers.opt.runtimesupport.OptCompiledMethod;
import org.jikesrvm.debug.RVMDebug.EventType;
import org.jikesrvm.jni.JNICompiledMethod;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.runtime.RuntimeEntrypoints;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.Scheduler;
import org.jikesrvm.util.LinkedListRVM;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.BaselineSaveLSRegisters;
import org.vmmagic.pragma.NoOptCompile;

/**
 * A bytecode level Single step implementation.
 */
public class SingleStep  {

  private static final boolean DEBUG = false;

  /** Note that the call to this method is asynchronous. */
  @BaselineSaveLSRegisters
  @NoOptCompile
  @Entrypoint
  public static void singleStepHit() {
    // Locate the break point from the register set.
    final NormalMethod method;
    final int bcIndex;

    if (DEBUG) {VM.sysWriteln("singleStepHit: finding location");}
    VM.disableGC(); //since we are dealing with Address.
    Address ip = Magic.getReturnAddress(Magic.getFramePointer());
    Address fp = Magic.getCallerFramePointer(Magic.getFramePointer());
    int cmid = Magic.getCompiledMethodID(fp);
    CompiledMethod cm = CompiledMethods.getCompiledMethod(cmid);
    if (cm instanceof BaselineCompiledMethod) {
      BaselineCompiledMethod bcm = (BaselineCompiledMethod) cm;
      Offset ip_offset = cm.getInstructionOffset(ip);
      bcIndex = bcm.findBytecodeIndexForInstruction(ip_offset);
      method = (NormalMethod) bcm.getMethod();
    } else {
      bcIndex = -1;
      method =null;
      if (VM.VerifyAssertions) {
        VM._assert(false, "Unexpected caller to the single step hit handler");
      }
    }
    VM.enableGC();

    if (DEBUG) {VM.sysWriteln("singleStepHit: location found");}

        // Notify the break point hit through a call back.
    if (method != null && bcIndex >= 0) {
      if (Scheduler.getCurrentThread().isSystemThread()) {
        if (JikesRVMJDWP.getVerbose() >= 3) {
          VM.sysWriteln("skipping a system thread's break point hit: bcindex ",
              bcIndex, method.toString());
        }
      } else {
        try {
          RVMDebug d = RVMDebug.getRVMDebug();
          RVMDebugState s = d.eventRequest;
          EventNotifier notifier = d.eventNotifier;
          RVMThread thread = Scheduler.getCurrentThread();
          if (DEBUG) {VM.sysWriteln("singleStepHit:  notifying the event.");}
          notifier.notifySingleStep(thread, method, bcIndex);
        } catch(Exception t) {
          VM._assert(false,
              "The break point call back should handle all the exception");
        }
      }
    } else {
      if (VM.VerifyAssertions) {
        VM._assert(false, "a break point trap from unknown source");
      }
    }
  }
}
