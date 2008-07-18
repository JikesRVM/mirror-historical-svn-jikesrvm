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

import org.jikesrvm.VM;
import org.jikesrvm.Constants;
import org.jikesrvm.ArchitectureSpecificOpt.OSR_BaselineExecStateExtractor;
import org.jikesrvm.ArchitectureSpecificOpt.OSR_CodeInstaller;
import org.jikesrvm.ArchitectureSpecificOpt.OSR_OptExecStateExtractor;
import org.jikesrvm.adaptive.controller.Controller;
import org.jikesrvm.adaptive.controller.ControllerPlan;
import org.jikesrvm.adaptive.util.AOSLogging;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.compilers.opt.driver.CompilationPlan;
import org.jikesrvm.osr.OSR_ExecStateExtractor;
import org.jikesrvm.osr.OSR_ExecutionState;
import org.jikesrvm.osr.OSR_Profiler;
import org.jikesrvm.osr.OSR_SpecialCompiler;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.unboxed.Offset;

/**
 * A OSR_ControllerOnStackReplacementPlan is scheduled by ControllerThread,
 * and executed by the RecompilationThread.
 *
 * It has the suspended thread whose activation being replaced,
 * and a compilation plan.
 *
 * The execution of this plan compiles the method, installs the new
 * code, and reschedule the thread.
 */
public class OSR_OnStackReplacementPlan implements Constants {
  private final int CMID;
  private final Offset tsFromFPoff;
  private final Offset ypTakenFPoff;

  /* Status is write-only at the moment, but I suspect it comes in
   * handy for debugging -- RJG
   */
  @SuppressWarnings("unused")
  private byte status;

  private final RVMThread suspendedThread;
  private final CompilationPlan compPlan;

  private int timeInitiated = 0;
  private int timeCompleted = 0;

  public OSR_OnStackReplacementPlan(RVMThread thread, CompilationPlan cp, int cmid, int source, Offset tsoff,
                                    Offset ypoff, double priority) {
    this.suspendedThread = thread;
    this.compPlan = cp;
    this.CMID = cmid;
    this.tsFromFPoff = tsoff;
    this.ypTakenFPoff = ypoff;
    this.status = ControllerPlan.UNINITIALIZED;
  }

  public int getTimeInitiated() { return timeInitiated; }

  public void setTimeInitiated(int t) { timeInitiated = t; }

  public int getTimeCompleted() { return timeCompleted; }

  public void setTimeCompleted(int t) { timeCompleted = t; }

  public void setStatus(byte newStatus) {
    status = newStatus;
  }

  public void execute() {
    // 1. extract stack state
    // 2. recompile the specialized method
    // 3. install the code
    // 4. reschedule the thread to new code.

    AOSLogging.logOsrEvent("OSR compiling " + compPlan.method);

    setTimeInitiated(Controller.controllerClock);

    {
      OSR_ExecStateExtractor extractor = null;

      CompiledMethod cm = CompiledMethods.getCompiledMethod(this.CMID);

      boolean invalidate = true;
      if (cm.getCompilerType() == CompiledMethod.BASELINE) {
        extractor = new OSR_BaselineExecStateExtractor();
        // don't need to invalidate when transitioning from baseline
        invalidate = false;
      } else if (cm.getCompilerType() == CompiledMethod.OPT) {
        extractor = new OSR_OptExecStateExtractor();
      } else {
        if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
        return;
      }

      ////////
      // states is a list of state: callee -> caller -> caller
      OSR_ExecutionState state = extractor.extractState(suspendedThread, this.tsFromFPoff, this.ypTakenFPoff, CMID);

      if (invalidate) {
        AOSLogging.debug("Invalidate cmid " + CMID);
        OSR_Profiler.notifyInvalidation(state);
      }

      // compile from callee to caller
      CompiledMethod newCM = OSR_SpecialCompiler.recompileState(state, invalidate);

      setTimeCompleted(Controller.controllerClock);

      if (newCM == null) {
        setStatus(ControllerPlan.ABORTED_COMPILATION_ERROR);
        AOSLogging.logOsrEvent("OSR compilation failed!");
      } else {
        setStatus(ControllerPlan.COMPLETED);
        // now let OSR_CodeInstaller generate a code stub,
        // and OSR_PostThreadSwitch will install the stub to run.
        OSR_CodeInstaller.install(state, newCM);
        AOSLogging.logOsrEvent("OSR compilation succeeded! " + compPlan.method);
      }
    }

    suspendedThread.osrUnpark();
  }
}