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

import java.lang.management.MemoryUsage;

import java.util.Map;
import java.util.HashMap;

import org.jikesrvm.VM_UnimplementedError;
import org.jikesrvm.memorymanagers.mminterface.Selected;

import org.mmtk.policy.Space;
import org.mmtk.utility.Conversions;

/**
 * Implementation of the memory pool bean for JikesRVM.
 *
 * @author Andrew John Hughes (gnu_andrew@member.fsf.org)
 */
public final class VMMemoryPoolMXBeanImpl {

  /**
   * A map of pool names to their indicies within
   * the array maintained in Space.
   */
  private static Map<String,Integer> pools;

  /**
   * Retrieves a list of names for all the pools.
   *
   * @return a list of names of the pools.
   */
  public static String[] getPoolNames() {
    if (pools == null)
      {
	pools = new HashMap<String,Integer>();
	for (int a = 0; a < Space.spaceCount; ++a)
	  pools.put(Space.spaces[a].getName(), a);
      }
    return pools.keySet().toArray(new String[Space.spaceCount]);
  }

  /**
   * Collection usage refers to memory usage within the specified pool
   * after a garbage collection run.  We currently do no support
   * this feature and so return <code>null</code>.
   *
   * @param name the name of the pool whose usage should be returned.
   * @return <code>null</code>.
   */
  static MemoryUsage getCollectionUsage(String name) {
    return null;
  }

  /**
   * Returns the current threshold level for collection usage on the
   * specified pool.  This is never called as we don't set the appropriate
   * property.
   *
   * @param name the name of the pool whose usage threshold should be returned.
   * @return the threshold level.
   */
  static long getCollectionUsageThreshold(String name) {
    throw new VM_UnimplementedError();
  }

  /**
   * Returns the number of times the threshold level for collection usage
   * has been met or exceeded for the specified pool.  This is never called
   * as we don't set the appropriate property.
   *
   * @param name the name of the pool whose usage threshold count should be returned.
   * @return the number of times the threshold level.
   */
  static long getCollectionUsageThresholdCount(String name) {
    throw new VM_UnimplementedError();
  }

  /**
   * Returns the name of the memory manager which manages
   * the specified pool.  All our pools are managed by our
   * single memory manager (the active MMTk plan) and so we
   * just return the name of that, regardless of the pool
   * given.
   *
   * @param name the name of the pool whose memory managers should
   *             be returned.
   * @return the name of the active plan.
   */
  static String[] getMemoryManagerNames(String name) {
   return new String[] { Selected.name };
  }

  /**
   * Returns the peak usage of the specified pool.
   *
   * @param name the name of the pool whose peak usage should be returned.
   * @return the peak memory usage.
   */
  static MemoryUsage getPeakUsage(String name) {
    /* FIXME: Implement this */
    throw new VM_UnimplementedError();
  }

  /**
   * Returns the type of the specified pool, which can be
   * either "HEAP" or "NON_HEAP".  We consider immortal spaces
   * to be non-heap allocated and all others to be from the heap
   * (as objects can be both created and removed from them).
   *
   * @param name the name of the pool whose peak usage should be returned.
   * @return the type of the memory pool.
   */
  static String getType(String name) {
    return (Space.spaces[pools.get(name)].isImmortal() ? "NON_HEAP" : "HEAP");
  }

  /**
   * Returns the memory usage of the specified pool.  The total
   * memory is considered to be the size of the extent of the pool,
   * while the used and committed sizes refer to the reserved and
   * committed pages respectively.  All sizes are in bytes and the initial
   * size is assumed to be zero.
   *
   * @param name the name of the pool whose usage should be returned.
   * @return the usage of the specified pool.
   */
  static MemoryUsage getUsage(String name) {
    Space space = Space.spaces[pools.get(name)];
    return new MemoryUsage(0,
			   Conversions.pagesToBytes(space.reservedPages()).toLong(),
			   Conversions.pagesToBytes(space.committedPages()).toLong(),
			   space.getExtent().toLong());
  }

  /**
   * Returns the current threshold level for usage on the
   * specified pool.  This is never called as we don't set the appropriate
   * property.
   *
   * @param name the name of the pool whose usage threshold should be returned.
   * @return the threshold level.
   */
  static long getUsageThreshold(String name) {
    throw new VM_UnimplementedError();
  }

  /**
   * Returns the number of times the threshold level for usage has been
   * met or exceeded for the specified pool.  This is never called
   * as we don't set the appropriate property.
   *
   * @param name the name of the pool whose usage threshold count should be returned.
   * @return the number of times the threshold level.
   */
  static long getUsageThresholdCount(String name) {
    throw new VM_UnimplementedError();
  }

  /**
   * We simply assume a pool is valid if it is in the list of pool names
   * we maintain.
   *
   * @param name the pool whose validity should be checked.
   * @return true if the pool is valid.
   */
  static boolean isValid(String name) {
    return pools.get(name) != null;
  }

  /**
   * Resets the current peak usage value to the current usage.
   */
  static void resetPeakUsage() {
    /* FIXME: Implement this */
    throw new VM_UnimplementedError();
  }

  /**
   * Sets the threshold level for collection usage.  This method
   * is never called as we don't set the appropriate property.
   *
   * @param name the name of the pool whose threshold should be set.
   * @param threshold the new threshold value.
   */
  static void setCollectionUsageThreshold(String name, long threshold) {
    throw new VM_UnimplementedError();
  }

  /**
   * Sets the threshold level for memory usage.  This method
   * is never called as we don't set the appropriate property.
   *
   * @param name the name of the pool whose threshold should be set.
   * @param threshold the new threshold value.
   */
  static void setUsageThreshold(String name, long threshold) {
    throw new VM_UnimplementedError();
  }

}
