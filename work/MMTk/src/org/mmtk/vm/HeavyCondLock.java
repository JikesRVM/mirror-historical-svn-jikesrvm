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
package org.mmtk.vm;

import org.vmmagic.pragma.Uninterruptible;

/**
 * Provides MMTk access to a heavy lock with condition variable.
 * Functionally similar to Java monitors, but safe in the darker corners of runtime code.
 */
@Uninterruptible
public abstract class HeavyCondLock {

  /**
   * Block until the lock is acquired.
   */
  public abstract void lock();

  /**
   * Release the lock.
   */
  public abstract void unlock();

  /**
   * Wait for a broadcast.
   */
  public abstract void waitNicely();

  /**
   * Send a broadcast.
   */
  public abstract void broadcast();
}
