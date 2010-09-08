/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.harness.vm;

import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class Statistics extends org.mmtk.vm.Statistics {
  @Override
  public void perfEventRead(int x,long[] y) {
    // TODO
  }
  @Override
  public void perfEventInit(String events) {
    // TODO
  }

  /**
   * Read cycle counter
   */
  @Override
  public long nanoTime() {
    return System.nanoTime();
  }

  /**
   * Convert nanoseconds to milliseconds
   */
  @Override
  public double nanosToMillis(long c) {
    return (c) / 1e6;
  }

  /**
   * Convert nanoseconds to seconds
   */
  @Override
  public double nanosToSecs(long c) {
    return (c) / 1e9;
  }

  /**
   * Convert milliseconds to nanoseconds
   */
  @Override
  public long millisToNanos(double t) {
    return (long)(t * 1e6);
  }

  /**
   * Convert seconds to nanoseconds
   */
  @Override
  public long secsToNanos(double t) {
    return (long)(t * 1e9);
  }

  /**
   * Read the cycle counter
   */
  @Override
  public long cycles() {
    return System.nanoTime();
  }

  /**
   * Initialize performance counters
   *
   * @param metric An integer identifying the metric being read
   */
  public void perfCtrInit(int metric) {
    //Assert.notImplemented();
  }

  /**
   * Read the current cycle count from the perfctr libraries
   *
   * @return the current cycle count from the perfctr libraries
   */
  public long perfCtrReadCycles() {
    //Assert.notImplemented();
    return 0;
  }

  /**
   * Read the current event count for the metric being measured by the
   * perfctr libraries
   *
   * @return the current event count for the metric being measured by the
   * perfctr libraries
   */
  public long perfCtrReadMetric() {
    //Assert.notImplemented();
    return 0;
  }
}
