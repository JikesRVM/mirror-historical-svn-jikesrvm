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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;

import java.util.List;

import org.mmtk.policy.Space;
import org.mmtk.utility.Conversions;
import org.mmtk.utility.Finalizer;
import org.mmtk.utility.options.Options;

/**
 * Implementation of the memory bean for JikesRVM.
 *
 * @author Andrew John Hughes (gnu_andrew@member.fsf.org)
 */
final class VMMemoryMXBeanImpl {

  /**
   * Return the sum of the usage in all heap-based
   * pools.
   *
   * @return the memory usage for heap-based pools.
   */
  static MemoryUsage getHeapMemoryUsage() {
    return getUsage(false);
  }

  /**
   * Return the sum of the usage in all non-heap-based
   * pools.
   *
   * @return the memory usage for non-heap-based pools.
   */
  static MemoryUsage getNonHeapMemoryUsage() {
    return getUsage(true);
  }

  /**
   * Return the number of objects waiting for finalization.
   *
   * @return the number of finalizable objects.
   */
  static int getObjectPendingFinalizationCount() {
    return Finalizer.countToBeFinalized();
  }

  /**
   * Returns true if some level of verbosity is on.
   *
   * @return true if verbosity is greater than 0.
   */
  static boolean isVerbose() {
    return Options.verbose.getValue() > 0;
  }

  /**
   * Turns on or off verbosity.  MMTk has a more detailed
   * level of verbosity, so we simply map true to level 1.
   *
   * @param verbose the new verbosity setting.
   */
  static void setVerbose(boolean verbose) {
    Options.verbose.setValue(verbose ? 1 : 0);
  }

  /**
   * Totals the memory usage from all the pools that are either
   * mortal or immortal.
   *
   * @param immortal true if the spaces counted should be immortal. 
   * @return the memory usage overall.
   */
  private static MemoryUsage getUsage(boolean immortal) {
    long committed = 0, used = 0, max = 0;
    for (int a = 0; a < Space.getSpaceCount(); ++a)
      {
	Space space = Space.getSpaces()[a];
	if (space.isImmortal() == immortal)
	  {
	    used += Conversions.pagesToBytes(space.reservedPages()).toLong();
	    committed += Conversions.pagesToBytes(space.committedPages()).toLong();
	    max += space.getExtent().toLong();
	  }
      }
    return new MemoryUsage(-1, used, committed, max);
  }

}

