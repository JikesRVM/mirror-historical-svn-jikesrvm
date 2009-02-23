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
package org.jikesrvm.mm.mminterface;

import org.jikesrvm.VM;
import org.jikesrvm.mm.mmtk.Collection;
import org.jikesrvm.mm.mmtk.MMTk_Events;
import org.jikesrvm.mm.mmtk.ScanThread;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.runtime.Time;
import org.jikesrvm.scheduler.Synchronization;
import org.jikesrvm.scheduler.RVMThread;
import org.mmtk.plan.CollectorContext;
import org.mmtk.plan.Plan;
import org.vmmagic.pragma.BaselineNoRegisters;
import org.vmmagic.pragma.BaselineSaveLSRegisters;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.NoOptCompile;
import org.vmmagic.pragma.NonMoving;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.UnpreemptibleNoWarn;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;

/**
 * System thread used to perform garbage collection work.
 */
@NonMoving
public final class CollectorThread extends RVMThread {

  /***********************************************************************
   *
   * Class variables
   */
  
  /** The MMTk context associated with this thread */
  private CollectorContext context;

  /***********************************************************************
   *
   * Instance variables
   */

  /** used by collector threads to hold state during stack scanning */
  private final ScanThread threadScanner = new ScanThread();

  /** @return the thread scanner instance associated with this instance */
  @Uninterruptible
  public ScanThread getThreadScanner() { return threadScanner; }

  /***********************************************************************
   *
   * Initialization
   */

  /**
   * Constructor
   *
   * @param stack The stack this thread will run on
   * @param processorAffinity The processor with which this thread is
   * associated.
   */
  public CollectorThread(byte[] stack, CollectorContext context) {
    super(stack, context.getClass().getName() + " [" + nextId + "]");
    this.context = context;
    this.context.initCollector(nextId);
    makeDaemon(true); // this is redundant, but harmless
    nextId++;
  }

  /** Next collector thread id. Collector threads are not created concurrently. */
  private static int nextId = 0;

  /**
   * Is this the GC thread?
   * @return true
   */
  @Uninterruptible
  public boolean isGCThread() {
    return true;
  }

  /**
   * @return this thread's collector context.
   */
  @Uninterruptible
  public CollectorContext getCollectorContext() {
    return context;
  }

  /**
   * Collection entry point. Delegates the real work to MMTk.
   */
  @NoOptCompile
  // refs stored in registers by opt compiler will not be relocated by GC
  @BaselineNoRegisters
  // refs stored in registers by baseline compiler will not be relocated by GC, so use stack only
  @BaselineSaveLSRegisters
  // and store all registers from previous method in prologue, so that we can stack access them while scanning this thread.
  @Unpreemptible
  public void run() {
    context.run();
  }
}

