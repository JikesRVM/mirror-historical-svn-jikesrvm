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
package gnu.java.lang.management;

import org.jikesrvm.compilers.common.VM_RuntimeCompiler;

/**
 * Implementation of the compilation
 * bean for JikesRVM.
 *
 * @author Andrew John Hughes (gnu_andrew@member.fsf.org)
 */
final class VMCompilationMXBeanImpl {

  static long getTotalCompilationTime() {
    return Math.round(VM_RuntimeCompiler.getTotalCompilationTime(VM_RuntimeCompiler.JNI_COMPILER)
      + VM_RuntimeCompiler.getTotalCompilationTime(VM_RuntimeCompiler.BASELINE_COMPILER)
      + VM_RuntimeCompiler.getTotalCompilationTime(VM_RuntimeCompiler.OPT_COMPILER));
  }

}
