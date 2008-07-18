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
package org.jikesrvm.memorymanagers.mminterface;

import org.jikesrvm.VM;
import org.jikesrvm.runtime.Magic;
import static org.jikesrvm.runtime.SysCall.sysCall;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Uninterruptible;

/**
 * A synchronization barrier used to synchronize collector threads,
 * and the processors they are running on, during parallel collections.
 *
 * The core barrier functionality is implemented by a barrier object.
 * The code in this class was in charge of VM-related idiosyncrasies like
 * computing how many processors are participating in a particular collection,
 * but that is all obsolete now that we're using native threads.  Hence this
 * class is somewhat vestigial.
 */
public final class SynchronizationBarrier {

  private static final int verbose = 0;

  // number of physical processors on running computer
  private int numRealProcessors;

  final Barrier barrier = new Barrier();
  /**
   * Constructor
   */
  public SynchronizationBarrier() {
    // initialize numRealProcessors to 1. Will be set to actual value later.
    numRealProcessors = 1;
  }

  /**
   * Wait for all other collectorThreads/processors to arrive at this barrier.
   */
  @Uninterruptible
  public int rendezvous(int where) {

    barrier.arrive(where);

    Magic.isync(); // so subsequent instructions won't see stale values

    // XXX This should be changed to return ordinal of current rendezvous rather than the one at the beginning
    return Magic.threadAsCollectorThread(RVMThread.getCurrentThread()).getGCOrdinal();
  }

  /**
   * First rendezvous for a collection, called by all CollectorThreads that arrive
   * to participate in a collection.  Thread with gcOrdinal==1 is responsible for
   * detecting detecting the number of processors (which is retarded since we already
   * know how many there are).
   */
  @Uninterruptible
  public void startupRendezvous() {

    CollectorThread th = Magic.threadAsCollectorThread(RVMThread.getCurrentThread());
    int myNumber = th.getGCOrdinal();

    if (verbose > 0) {
      VM.sysWriteln("GC Message: SynchronizationBarrier.startupRendezvous: thr ",
                    th.getThreadSlot(),
                    " ordinal ",
                    myNumber);
    }

    if (myNumber > 1) {
      barrier.arrive(8888); // wait for designated guy to do his job
      Magic.isync();     // so subsequent instructions won't see stale values
      if (verbose > 0) VM.sysWriteln("GC Message: startupRendezvous  leaving as ", myNumber);
      return;               // leave barrier
    }

    int numParticipating = RVMThread.numProcessors;

    if (verbose > 0) {
      VM.sysWriteln("GC Message: startupRendezvous  numParticipating = ", numParticipating);
    }
    barrier.setTarget(numParticipating); // retarded
    barrier.arrive(8888);    // all setup now complete and we can proceed
    Magic.sync();   // update main memory so other processors will see it in "while" loop
    Magic.isync();  // so subsequent instructions won't see stale values
    if (verbose > 0) {
      VM.sysWriteln("GC Message: startupRendezvous  designated proc leaving");
    }
  }  // startupRendezvous
}