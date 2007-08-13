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
package java.lang.management;

import gnu.java.lang.management.VMMemoryPoolMXBeanImpl;

import java.util.Iterator;

import org.jikesrvm.memorymanagers.mminterface.Selected;

/**
 * Implementation of the management factory for Jikes RVM.
 *
 * @author Andrew John Hughes (gnu_andrew@member.fsf.org)
 */
final class VMManagementFactory
{
  /**
   * We maintain a 1:1 correspondence between memory pools
   * and spaces, and so return the names of all our spaces
   * here, which causes a bean to be created for each one.
   *
   * @return a list of memory pools or
   *         {@link org.mmtk.policy.Space}s.
   */
  static String[] getMemoryPoolNames() {
    return VMMemoryPoolMXBeanImpl.getPoolNames();
  }

  /**
   * This method returns the names of general memory managers
   * (those that aren't garbage collectors) and a bean is created
   * for each one.  We only have one memory manager (represented
   * by the active plan) and this is a garbage collector, so we
   * just return an empty array here.
   *
   * @return an empty array.
   */
  static String[] getMemoryManagerNames() {
    return new String[] {};
  }

  /**
   * This method returns the name of the currently active plan,
   * which corresponds to our memory manager.
   *
   * @return the name of the currently active plan.
   */
  static String[] getGarbageCollectorNames() {
    return new String[] { Selected.name };
  }
}
