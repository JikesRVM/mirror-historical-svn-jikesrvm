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

import org.jikesrvm.VM_CommandLineArgs;
import org.jikesrvm.VM_Configuration;
import org.jikesrvm.runtime.VM_Process;
import org.jikesrvm.runtime.VM_Time;

/**
 * Implementation of the runtime bean for JikesRVM.
 *
 * @author Andrew John Hughes (gnu_andrew@member.fsf.org)
 */
final class VMRuntimeMXBeanImpl {

  static String[] getInputArguments() {
    return VM_CommandLineArgs.getInputArgs();
  }

  static String getName() {
    return (VM_Configuration.BuildFor32Addr ? "32-bit" : "64-bit") +
      " JikesRVM on GNU Classpath";
  }

  static long getStartTime() {
    return VM_Time.bootTime() / 1000;
  }

}
