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

import org.jikesrvm.VM;
import org.jikesrvm.scheduler.RVMThread;


/**
 * A bytecode level Single step implementation.
 * 
 */
public class SingleStep {

  /**
   * Set a single mode flag for a suspended thread.
   * 
   * @param t The thread.
   */
  static void setSteppingMode(RVMThread t) {
    if (VM.VerifyAssertions) {
      VM._assert(false, "Not implemented");
    }
  }

  /**
   * Reset the suspended thread.
   * 
   * @param t The thread.
   */
  static void clearStepping(RVMThread t) {
    if (VM.VerifyAssertions) {
      VM._assert(false, "Not implemented");
    }
  }

  /**
   * Check single step was set, and insert the breakpoint at the next dynamic
   * byte code.
   * 
   * @param t The thread.
   */
  static void checkSingleStep(RVMThread t) {
    if (VM.VerifyAssertions) {
      VM._assert(false, "Not implemented");
    } 
  }

  /**
   * Decode the current bytecode the execute in a thread, find the next dynamic
   * bytecode, and set a Breakpoint.
   * 
   * @param t The thread.
   */
  static void setStepBreakpoint(RVMThread t) {
    if (VM.VerifyAssertions) {
      VM._assert(false, "Not implemented");
    }
  }
}
