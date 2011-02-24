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
package org.jikesrvm.mm.mmtk;

import org.mmtk.plan.CollectorContext;
import org.mmtk.plan.MutatorContext;
import org.mmtk.utility.options.Options;

import org.jikesrvm.ArchitectureSpecific;
import org.jikesrvm.VM;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.mm.mminterface.Selected;
import org.jikesrvm.mm.mminterface.CollectorThread;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.FinalizerThread;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.unboxed.Address;

@Uninterruptible
public class Collection extends org.mmtk.vm.Collection implements org.mmtk.utility.Constants,
                                                                  org.jikesrvm.Constants {

  /****************************************************************************
   *
   * Class variables
   */
  public static short onTheFlyPhase = 0;

  /**
   * Spawn a thread to execute the supplied collector context.
   */
  @Interruptible
  public void spawnCollectorContext(CollectorContext context) {
    byte[] stack = MemoryManager.newStack(ArchitectureSpecific.StackframeLayoutConstants.STACK_SIZE_COLLECTOR);
    CollectorThread t = new CollectorThread(stack, context);
    t.start();
  }

  /**
   * @return The default number of collector threads to use.
   */
  public int getDefaultThreads() {
    return RVMThread.numProcessors;
  }

  /**
   * Block for the garbage collector.
   */
  @Unpreemptible
  public void blockForGC() {
    // poll has advised that a GC is required, block the current thread
    // a concurrent GC may resume this thread later before the GC is actually complete
    RVMThread t = RVMThread.getCurrentThread();
    t.assertAcceptableStates(RVMThread.IN_JAVA, RVMThread.IN_JAVA_TO_BLOCK);
    RVMThread.observeExecStatusAtSTW(t.getExecStatus());
    if (Options.verbose.getValue() >= 8) VM.sysWriteln("Thread # about to block for GC ", t.threadSlot);
    RVMThread.getCurrentThread().block(RVMThread.gcBlockAdapter);
    if (Options.verbose.getValue() >= 8) VM.sysWriteln("Thread # back from blocking for GC ", t.threadSlot);
  }

  public boolean isBlockedForGC(MutatorContext m) {
    RVMThread t = ((Selected.Mutator) m).getThread();
    if (RVMThread.gcBlockAdapter.isBlocked(t)) {
      if (Options.verbose.getValue() >= 8) VM.sysWriteln("STW Mutator phase iterating over mutator thread ", t.threadSlot);
      return true;
    }
    // something hinky going on - probably some sort of system thread
    if (t.isTimerThread()) {
      if (Options.verbose.getValue() >= 8) VM.sysWriteln("STW Mutator phase iterating over timer thread ", t.threadSlot);
      return true;
    } else if (t.isCollectorThread()) {
      if (Options.verbose.getValue() >= 8) VM.sysWriteln("STW Mutator phase iterating over collector thread ", t.threadSlot);
      return true;
    } else if (RVMThread.notRunning(t.getExecStatus())) {
      if (Options.verbose.getValue() >= 8) VM.sysWriteln("STW Mutator phase iterating over NEW thread");
      return true;
    } else {
      if (Options.verbose.getValue() >= 8) VM.sysWriteln("STW Mutator phase found a thread of unknown type or in wrong state ", t.threadSlot);
    }
    return false;
  }


// @UninterruptibleNoWarn("This method is really unpreemptible, since it involves blocking")
  // public void onTheFlyMutatorCollectionPhase(short phaseId, boolean primary) {
  // if (VM.VerifyAssertions)
  // VM._assert(RVMThread.getCurrentThread().isCollectorThread(), "Designed to be called by a collector thread");
  // org.mmtk.vm.VM.activePlan.resetMutatorIterator();
  // RVMThread.acctLock.lockNoHandshake(); // ensure no new threads can be started
  // MutatorContext mutator;
  // while ((mutator = org.mmtk.vm.VM.activePlan.getNextMutator()) != null) {
  // RVMThread t = ((Selected.Mutator) mutator).getThread();
  // boolean stopThread = true;
  // if (t.ignoreHandshakesAndGC() || t.isCollectorThread()) {
  // // Safe to just call the collection phase on the thread
  // stopThread = false;
  // if (t.isCollectorThread())
  // VM.sysWriteln("On-the-fly mutator phase on collector thread ", t.threadSlot);
  // else
  // VM.sysWriteln("On-the-fly mutator phase on thread that ignores GC (probably TimerThread) ", t.threadSlot);
  // }
  // if (stopThread) {
  // // block t
  // VM.sysWriteln("On-the-fly mutator phase blocking a running mutator thread ", t.threadSlot);
  // t.beginPairHandshake();
  // }
  // VM.sysWriteln("Calling collectionPhase on behalf of thread ", t.threadSlot);
  // mutator.collectionPhase(phaseId, primary);
  // if (stopThread) {
  // // resume t
  // VM.sysWriteln("On-the-fly mutator phase resuming mutator thread ", t.threadSlot);
  // t.endPairHandshake();
  // }
  // }
  // RVMThread.acctLock.unlock(); // allow new threads to be started
  // org.mmtk.vm.VM.activePlan.resetMutatorIterator();
  // }
  
  

  /***********************************************************************
   *
   * Initialization
   */

  /**
   * Fail with an out of memory error.
   */
  @UninterruptibleNoWarn
  public void outOfMemory() {
    throw RVMThread.getOutOfMemoryError();
  }

  /**
   * Prepare a mutator for a collection.
   *
   * @param m the mutator to prepare
   */
  public final void prepareMutator(MutatorContext m) {
    /*
     * The collector threads of processors currently running threads
     * off in JNI-land cannot run.
     */
    RVMThread t = ((Selected.Mutator) m).getThread();
    t.monitor().lockNoHandshake();
    // are these the only unexpected states?
    t.assertUnacceptableStates(RVMThread.IN_JNI,RVMThread.IN_NATIVE);
    int execStatus = t.getExecStatus();
    // these next assertions are not redundant given the ability of the
    // states to change asynchronously, even when we're holding the lock, since
    // the thread may change its own state.  of course that shouldn't happen,
    // but having more assertions never hurts...
    if (VM.VerifyAssertions) VM._assert(execStatus != RVMThread.IN_JNI);
    if (VM.VerifyAssertions) VM._assert(execStatus != RVMThread.IN_NATIVE);
    if (execStatus == RVMThread.BLOCKED_IN_JNI) {
      if (Options.verbose.getValue() >= 8) {
        VM.sysWriteln("prepareMutator for thread #", t.getThreadSlot(), " setting up JNI stack scan");
        VM.sysWriteln("thread #",t.getThreadSlot()," has top java fp = ",t.getJNIEnv().topJavaFP());
      }

      /* thread is blocked in C for this GC.
       Its stack needs to be scanned, starting from the "top" java
       frame, which has been saved in the running threads JNIEnv.  Put
       the saved frame pointer into the threads saved context regs,
       which is where the stack scan starts. */
      t.contextRegisters.setInnermost(Address.zero(), t.getJNIEnv().topJavaFP());
    }
    t.monitor().unlock();
  }

  /**
   * Stop all mutator threads. This is current intended to be run by a single thread.
   *
   * Fixpoint until there are no threads that we haven't blocked. Fixpoint is needed to
   * catch the (unlikely) case that a thread spawns another thread while we are waiting.
   */
  @Unpreemptible
  public void stopAllMutators() {
    RVMThread.blockAllMutatorsForGC();
  }

  /**
   * Resume all mutators blocked for GC.
   */
  @Unpreemptible
  public void resumeAllMutators() {
    RVMThread.unblockAllMutatorsForGC();
  }

  private static RVMThread.SoftHandshakeVisitor mutatorFlushVisitor =
    new RVMThread.SoftHandshakeVisitor() {
      @Uninterruptible
      public boolean checkAndSignal(RVMThread t) {
        t.flushRequested = true;
        return true;
      }
      @Uninterruptible
      public void notifyStuckInNative(RVMThread t) {
        t.flush();
        t.flushRequested = false;
      }
      @Uninterruptible
      public boolean includeThread(RVMThread t) {
        return !t.isCollectorThread();
      }
    };

  /**
   * Request each mutator flush remembered sets. This method
   * will trigger the flush and then yield until all processors have
   * flushed.
   */
  @UninterruptibleNoWarn("This method is really unpreemptible, since it involves blocking")
  public void requestUpdateBarriers() {
    if (VM.VerifyAssertions)
      VM._assert(RVMThread.getCurrentThread().isCollectorThread(), "Designed to be called by a collector thread");
    // 1) update all threads
    org.mmtk.vm.VM.activePlan.resetMutatorIterator();
    MutatorContext mutator;
    while ((mutator = org.mmtk.vm.VM.activePlan.getNextMutator()) != null) {
      RVMThread t = ((Selected.Mutator) mutator).getThread();
      t.insertionBarrier = MutatorContext.globalViewInsertionBarrier;
      t.mutatorMustDoubleAllocate = MutatorContext.globalViewMutatorMustDoubleAllocate;
      t.mutatorMustReplicate = MutatorContext.globalViewMutatorMustReplicate;
      if (Options.verbose.getValue() >= 8) VM.sysWriteln("thread #", t.threadSlot, " Insertion barrier is ", t.insertionBarrier ? 1 : 0);
      if (Options.verbose.getValue() >= 8) VM.sysWriteln("thread #", t.threadSlot, " Double alloc barrier is ", t.mutatorMustDoubleAllocate ? 1 : 0);
      if (Options.verbose.getValue() >= 8) VM.sysWriteln("thread #", t.threadSlot, " Replication barrier is ", t.mutatorMustReplicate ? 1 : 0);
    }
    org.mmtk.vm.VM.activePlan.resetMutatorIterator();
    // 2) wait for running mutator threads to go past GC safe point
    RVMThread.softHandshake(mutatorUpdateBarriersVisitor);
  }
  
  private static RVMThread.SoftHandshakeVisitor mutatorUpdateBarriersVisitor = new RVMThread.SoftHandshakeVisitor() {
    @Uninterruptible
    public boolean checkAndSignal(RVMThread t) {
      if (Options.verbose.getValue() >= 8) VM.sysWriteln("Waiting on async handshake with mutator thread #", t.threadSlot, " barrier conditions");
      return true;
    }

    @Uninterruptible
    public void notifyStuckInNative(RVMThread t) {
      if (Options.verbose.getValue() >= 8) VM.sysWriteln("Blocked thread will see barriers correctly when it resumes thread #", t.threadSlot, " with pthreadId ", t.pthread_id);
    }

    @Uninterruptible
    public boolean includeThread(RVMThread t) {
      if (VM.VerifyAssertions && t.isCollectorThread() && Options.verbose.getValue() >= 8)
        VM.sysWriteln("mutatorUpdateBarrierVisitor ignoring GC thread #", t.threadSlot);
      return !t.isCollectorThread();
    }
  };

  @UninterruptibleNoWarn("This method is really unpreemptible, since it involves blocking")
  public void requestMutatorOnTheFlyProcessPhase(short phaseId) {
    if (VM.VerifyAssertions)
      VM._assert(RVMThread.getCurrentThread().isCollectorThread(), "Designed to be called by a collector thread");
    onTheFlyPhase = phaseId;
    RVMThread.softHandshake(mutatorOnTheFlyProcessVisitor);
    onTheFlyPhase = 0;
  }

  private static RVMThread.SoftHandshakeVisitor mutatorOnTheFlyProcessVisitor = new RVMThread.SoftHandshakeVisitor() {
    @Uninterruptible
    public boolean checkAndSignal(RVMThread t) {
      if (Options.verbose.getValue() >= 8) VM.sysWriteln("Requesting async process of on-the-fly mutator phase");
      t.mutatorProcessPhase = true;
      return true;
    }

    @Uninterruptible
    public void notifyStuckInNative(RVMThread t) {
      if (Options.verbose.getValue() >= 8) VM.sysWriteln("Performing process collectionPhase on behalf of blocked thread");
      t.collectionPhase(onTheFlyPhase, false); // LPJH: probably shouldn't just pass false for primary
      t.mutatorProcessPhase = false;
    }

    @Uninterruptible
    public boolean includeThread(RVMThread t) {
      return !t.isCollectorThread();
    }
  };

  /**
   * Request each mutator flush remembered sets. This method
   * will trigger the flush and then yield until all processors have
   * flushed.
   */
  @UninterruptibleNoWarn("This method is really unpreemptible, since it involves blocking")
  public void requestMutatorFlush() {
    Selected.Mutator.get().flush();
    RVMThread.softHandshake(mutatorFlushVisitor);
  }

  /***********************************************************************
   *
   * Finalizers
   */

  /**
   * Schedule the finalizerThread, if there are objects to be
   * finalized and the finalizerThread is on its queue (ie. currently
   * idle).  Should be called at the end of GC after moveToFinalizable
   * has been called, and before mutators are allowed to run.
   */
  @Uninterruptible
  public static void scheduleFinalizerThread() {
    int finalizedCount = FinalizableProcessor.countReadyForFinalize();
    if (finalizedCount > 0) {
      FinalizerThread.schedule();
    }
  }
}

