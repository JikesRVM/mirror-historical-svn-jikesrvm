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
import org.jikesrvm.mm.mmtk.Collection;
import org.jikesrvm.mm.mmtk.Lock;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.HeavyCondLock;
import org.vmmagic.pragma.LogicallyUninterruptible;
import org.vmmagic.pragma.Uninterruptible;

/**
 * Handshake handles mutator requests to initiate a collection, and
 * wait for a collection to complete.  It implements the process of
 * suspending all mutator threads executing in Java and starting all
 * the GC threads (CollectorThreads) for the processors that will
 * be participating in a collection.  This may not be all processors,
 * if we exclude those executing in native code.
 *
 * Because the threading strategy within RVM is currently under
 * revision, the logic here is also changing and somewhat "messy".
 *
 * @see CollectorThread
 */
public class Handshake {

  /***********************************************************************
   *
   * Class variables
   */
  public static final int verbose = 0;

  /***********************************************************************
   *
   * Instance variables
   */
  private HeavyCondLock lock;
  protected boolean requestFlag;
  protected boolean completionFlag;
  public int gcTrigger;  // reason for this GC
  private int collectorThreadsParked;

  public Handshake() {
    reset();
  }
  
  public void boot() {
    lock = new HeavyCondLock();
  }

  @Uninterruptible
  public void waitForGCToFinish() {
    if (verbose >= 1) VM.sysWriteln("GC Message: Handshake.requestAndAwaitCompletion - yielding");
    /* allow a gc thread to run */
    VM.sysWriteln("Thread #",RVMThread.getCurrentThreadSlot()," is waiting for the GC to finish.");
    lock.lock();
    while (!completionFlag) {
      lock.waitNicely();
    }
    lock.unlock();
    VM.sysWriteln("Thread #",RVMThread.getCurrentThreadSlot()," is done waiting for the GC.");
    if (verbose >= 1) VM.sysWriteln("GC Message: Handshake.requestAndAwaitCompletion - mutator running");
  }

  /**
   * Called by mutators to request a garbage collection and wait for
   * it to complete.
   *
   * Waiting is actually just yielding the processor to schedule the
   * collector thread, which will disable further thread switching on
   * the processor until it has completed the collection.
   */
  @LogicallyUninterruptible
  @Uninterruptible
  public void requestAndAwaitCompletion(int why) {
    if (request(why)) {
      waitForGCToFinish();
    }
  }

  /**
   * Called by mutators to request an asynchronous garbage collection.
   * After initiating a GC (if one is not already initiated), the
   * caller continues until it yields to the GC.  It may thus make
   * this call at an otherwise unsafe point.
   */
  @Uninterruptible
  public void requestAndContinue(int why) {
    request(why);
  }

  @Uninterruptible
  public void reset() {
    if (lock!=null) {
      lock.lock();
    }
    gcTrigger = Collection.UNKNOWN_GC_TRIGGER;
    requestFlag = false;
    if (lock!=null) {
      lock.unlock();
    }
  }
  
  void parkCollectorThread() {
    lock.lock();
    collectorThreadsParked++;
    lock.broadcast();
    VM.sysWriteln("GC Thread #",RVMThread.getCurrentThreadSlot()," parked.");
    while (!requestFlag) {
      VM.sysWriteln("GC Thread #",RVMThread.getCurrentThreadSlot()," waiting for request.");
      lock.await();
    }
    VM.sysWriteln("GC Thread #",RVMThread.getCurrentThreadSlot()," got request, unparking.");
    collectorThreadsParked--;
    lock.unlock();
  }

  /**
   * Wait for all GC threads to complete previous collection cycle.
   */
  @Uninterruptible
  private void waitForPrecedingGC() {
    VM.sysWriteln("Thread #",RVMThread.getCurrentThreadSlot()," is waiting for the preceding GC to finish");
    
    int maxCollectorThreads = RVMThread.numProcessors;

    /* Wait for all gc threads to finish preceeding collection cycle */
    if (verbose >= 1) {
      VM.sysWrite("GC Message: Handshake.initiateCollection ");
      VM.sysWriteln("checking if previous collection is finished");
    }
    
    lock.lock();
    while (collectorThreadsParked < maxCollectorThreads) {
      lock.await();
    }
    lock.unlock();

    VM.sysWriteln("Thread #",RVMThread.getCurrentThreadSlot()," is done waiting for the preceding GC to finish");
  }

  /**
   * Called by mutators to request a garbage collection.  If the
   * completionFlag is already set, return false.  Else, if the
   * requestFlag is not yet set (ie this is the first mutator to
   * request this collection) then initiate the collection sequence
   *
   * @return true if the completion flag is not already set.
   */
  @Uninterruptible
  private boolean request(int why) {
    VM.sysWriteln("Thread #",RVMThread.getCurrentThreadSlot()," is trying to make a GC request");
    lock.lock();
    VM.sysWriteln("Thread #",RVMThread.getCurrentThreadSlot()," acquired the lock for making a GC request");
    if (why > gcTrigger) gcTrigger = why;
    if (requestFlag) {
      if (verbose >= 1) {
        VM.sysWriteln("GC Message: mutator: already in progress");
      }
    } else {
      // first mutator initiates collection by making all gc threads
      // runnable at high priority
      if (verbose >= 1) VM.sysWriteln("GC Message: Handshake - mutator: initiating collection");

      if (!RVMThread.threadingInitialized) {
	VM.sysWrite("GC required before system fully initialized");
	VM.sysWriteln("Specify larger than default heapsize on command line");
	RVMThread.dumpStack();
	VM.shutdown(VM.EXIT_STATUS_MISC_TROUBLE);
      }

      waitForPrecedingGC();
      requestFlag = true;
      completionFlag = false;
      lock.broadcast();
    }
    lock.unlock();
    return true;
  }

  /**
   * Set the completion flag that indicates the collection has
   * completed.  Called by a collector thread after the collection has
   * completed.
   *
   * @see CollectorThread
   */
  @Uninterruptible
  void notifyCompletion() {
    VM.sysWriteln("Thread #",RVMThread.getCurrentThreadSlot()," is notifying the world that GC is done.");
    lock.lock();
    if (verbose >= 1) {
      VM.sysWriteln("GC Message: Handshake.notifyCompletion");
    }
    completionFlag = true;
    lock.broadcast();
    lock.unlock();
    VM.sysWriteln("Thread #",RVMThread.getCurrentThreadSlot()," is done notifying the world that GC is done.");
  }
}

/*
Local Variables:
   c-basic-offset: 2
End:
*/

