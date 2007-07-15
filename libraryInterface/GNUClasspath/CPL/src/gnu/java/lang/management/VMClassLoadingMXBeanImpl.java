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

import org.jikesrvm.VM;
import org.jikesrvm.VM_Callbacks.ClassLoadedMonitor;
import org.jikesrvm.classloader.VM_Class;

/**
 * Implementation of the class loading
 * bean for JikesRVM.
 *
 * @author Andrew John Hughes (gnu_andrew@member.fsf.org)
 */
public final class VMClassLoadingMXBeanImpl implements ClassLoadedMonitor {

  /**
   * The count of loaded classes.
   */
  private static int loadedCount;

  /**
   * Increase the count when a class is loaded.
   *
   * @param klass the loaded class (ignored).
   */
  public void notifyClassLoaded(VM_Class klass)
  {
    ++loadedCount;
  }

  /**
   * Count of number of classes loaded.
   */
  static int getLoadedClassCount() {
    return loadedCount;
  }

  /**
   * Count of number of classes unloaded.
   */
  static long getUnloadedClassCount() {
    return 0;
  }

  /**
   * Returns true if verbose class loading is on.
   */
  static boolean isVerbose() {
    return VM.verboseClassLoading;
  }

  /**
   * Turns verbose class loading on or off.
   *
   * @param verbose true if verbose information should
   *                be emitted on class loading.
   */
  static void setVerbose(boolean verbose) {
    VM.verboseClassLoading = verbose;
  }

}
