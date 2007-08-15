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

/**
 * Implementation of the memory manager bean for JikesRVM.
 *
 * @author Andrew John Hughes (gnu_andrew@member.fsf.org)
 */
final class VMMemoryManagerMXBeanImpl {

  /**
   * We ignore the name as we only have one manager,
   * and simply return the same as the management factory
   * would.
   *
   * @param name the name of the memory manager whose pools
   *             should be returned (ignored).
   * @return the list of pools.
   */
  static String[] getMemoryPoolNames(String name) {
    return VMMemoryPoolMXBeanImpl.getPoolNames();
  }

  /**
   * We assume that our manager is always valid.
   *
   * @param name the name of the memory manager whose pools
   *             should be returned (ignored).
   * @return <code>true</code>.
   */
  static boolean isValid(String name) {
    return true;
  }

}
