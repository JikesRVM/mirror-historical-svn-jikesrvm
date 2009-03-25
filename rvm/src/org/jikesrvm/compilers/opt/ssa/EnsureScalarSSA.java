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

package org.jikesrvm.compilers.opt.ssa;

import org.jikesrvm.compilers.opt.driver.CompilerPhase;
import org.jikesrvm.compilers.opt.ir.IR;

/**
 * A compiler phase that ensures that ScalarSSA form is valid.
 * This phase assumes that dominators have already been computed
 * and are available on the IR object.
 */
public final class EnsureScalarSSA extends CompilerPhase {

  public String getName() {
    return "Ensure SSA";
  }

  public void perform(IR ir) {
    ir.desiredSSAOptions = new SSAOptions();
    new EnterSSA().perform(ir);
  }

  /**
   * This phase contains no per-compilation instance fields.
   */
  public CompilerPhase newExecution(IR ir) {
    return this;
  }
}
