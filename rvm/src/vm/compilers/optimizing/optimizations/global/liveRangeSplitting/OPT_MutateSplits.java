/*
 * This file is part of the Jikes RVM project (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright IBM Corp. 2001
 */
//$Id$
package com.ibm.JikesRVM.opt;

import java.util.Enumeration;
import com.ibm.JikesRVM.opt.ir.*;

/**
 * Change SPLIT operations inserting for live range splitting into Moves.
 *
 * @author Stephen Fink
 */
final class OPT_MutateSplits extends OPT_CompilerPhase implements OPT_Operators{

  public final boolean shouldPerform (OPT_Options options) {
    return options.LIVE_RANGE_SPLITTING;
  }

  public final String getName () {
    return "Mutate Splits";
  }

  /**
   * The main entrypoint for this pass.
   */
  public final void perform(OPT_IR ir) {
    for (Enumeration e = ir.forwardInstrEnumerator(); e.hasMoreElements();) {
      OPT_Instruction s = (OPT_Instruction)e.nextElement();
      if (s.operator == SPLIT) {
        OPT_RegisterOperand lhs = Unary.getResult(s);
        OPT_Operator mv = OPT_IRTools.getMoveOp(lhs.type);
        OPT_Operand rhs = Unary.getVal(s);
        Move.mutate(s,mv,lhs,rhs);
      }
    }
  }
}
