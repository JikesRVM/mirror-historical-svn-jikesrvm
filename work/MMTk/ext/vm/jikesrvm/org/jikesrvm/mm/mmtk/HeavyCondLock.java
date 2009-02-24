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
package org.jikesrvm.mm.mmtk;

import org.vmmagic.pragma.Uninterruptible;

/**
 * Provides MMTk access to a heavy lock with condition variable.
 * Functionally similar to Java monitors, but safe in the darker corners of runtime code.
 */
@Uninterruptible
public final class HeavyCondLock extends org.mmtk.vm.HeavyCondLock {

  private final org.jikesrvm.scheduler.HeavyCondLock theLock;

  public HeavyCondLock(String name) {
    this.theLock = new org.jikesrvm.scheduler.HeavyCondLock();
  }

  /**
   * Block until the lock is acquired.
   */
  public void lock() {
    theLock.lock();
  }

  /**
   * Release the lock.
   */
  public void unlock() {
    theLock.unlock();
  }

  /**
   * Wait for a broadcast.
   */
  public void await() {
    theLock.await();
  }

  /**
   * Send a broadcast.
   */
  public void broadcast() {
    theLock.broadcast();
  }
}
