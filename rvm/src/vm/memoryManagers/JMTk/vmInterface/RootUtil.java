/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

package com.ibm.JikesRVM.memoryManagers.watson;

import VM_Thread;
import VM_Type;
import VM_Magic;
import VM_ObjectModel;
import VM;
import VM_Address;
import VM_Scheduler;
import VM_Array;
import VM_Memory;
import VM_Class;
import VM_Constants;
import VM_PragmaUninterruptible;

/**
 * Shared utility code for copying collectors.
 * Code originally written by Steve Smith;
 * refactored and moved here by Dave Grove so
 * it could be shared by all copying collectors.
 * 
 * @author Steve Smith
 */
class RootUtil 
  implements VM_Constants, VM_GCConstants {

  /**
   * Scans all threads in the VM_Scheduler threads array.  A threads stack
   * will be copied if necessary and any interior addresses relocated.
   * Each threads stack is scanned for object references, which will
   * becomes Roots for a collection. <p>
   * 
   * All collector threads execute here in parallel, and compete for
   * individual threads to process.  Each collector thread processes
   * its own thread object and stack.
   * 
   * @param fromHeap the heap that we are copying objects out of.
   */
  static void scanThreads (VM_Heap fromHeap)  throws VM_PragmaUninterruptible {
    // get ID of running GC thread
    int myThreadId = VM_Thread.getCurrentThread().getIndex();
    int[] oldstack;
    
    for (int i=0; i<VM_Scheduler.threads.length; i++ ) {
      VM_Thread t = VM_Scheduler.threads[i];
      VM_Address ta = VM_Magic.objectAsAddress(t);
      
      if (t == null) {
	// Nothing to do (no thread object...)
      } else if (i == myThreadId) {  
	// let each GC thread scan its own thread object

	// GC threads are assumed not to have native processors.  if this proves
	// false, then we will have to deal with its write buffers
	if (VM.VerifyAssertions) VM.assert(t.nativeAffinity == null);
	
	// all threads should have been copied out of fromspace earlier
	if (VM.VerifyAssertions) VM.assert(!fromHeap.refInHeap(ta));
	
	if (VM.VerifyAssertions) oldstack = t.stack;    // for verifying gc stacks not moved
	VM_ScanObject.scanObjectOrArray(t);
	if (VM.VerifyAssertions) VM.assert(oldstack == t.stack);
	
	if (t.jniEnv != null) VM_ScanObject.scanObjectOrArray(t.jniEnv);

	VM_ScanObject.scanObjectOrArray(t.contextRegisters);

	VM_ScanObject.scanObjectOrArray(t.hardwareExceptionRegisters);

	VM_ScanStack.scanStack(t, VM_Address.zero(), true);
	
      } else if (t.isGCThread && (VM_Magic.threadAsCollectorThread(t).gcOrdinal > 0)) {
	// skip other collector threads participating (have ordinal number) in this GC
      } else if (VM_GCLocks.testAndSetThreadLock(i)) {
	// have thread to be processed, compete for it with other GC threads
	
	if (VM_Allocator.verbose >= 3) VM.sysWriteln("    Processing mutator thread ",i);
	
	// all threads should have been copied out of fromspace earlier
	if (VM.VerifyAssertions) VM.assert(!(fromHeap.refInHeap(ta)));
	
	// scan thread object to force "interior" objects to be copied, marked, and
	// queued for later scanning.
	oldstack = t.stack;    // remember old stack address before scanThread
	VM_ScanObject.scanObjectOrArray(t);
	
	// if stack moved, adjust interior stack pointers
	if (oldstack != t.stack) {
	  if (VM_Allocator.verbose >= 3) VM.sysWriteln("    Adjusting mutator stack ",i);
	  t.fixupMovedStack(VM_Magic.objectAsAddress(t.stack).diff(VM_Magic.objectAsAddress(oldstack)));
	}
	
	// the above scanThread(t) will have marked and copied the threads JNIEnvironment object,
	// but not have scanned it (likely queued for later scanning).  We force a scan of it now,
	// to force copying of the JNI Refs array, which the following scanStack call will update,
	// and we want to ensure that the updates go into the "new" copy of the array.
	//
	if (t.jniEnv != null) VM_ScanObject.scanObjectOrArray(t.jniEnv);
	
	// Likewise we force scanning of the threads contextRegisters, to copy 
	// contextRegisters.gprs where the threads registers were saved when it yielded.
	// Any saved object references in the gprs will be updated during the scan
	// of its stack.
	//
	VM_ScanObject.scanObjectOrArray(t.contextRegisters);

	VM_ScanObject.scanObjectOrArray(t.hardwareExceptionRegisters);
	
	// all threads in "unusual" states, such as running threads in
	// SIGWAIT (nativeIdleThreads, nativeDaemonThreads, passiveCollectorThreads),
	// set their ContextRegisters before calling SIGWAIT so that scans of
	// their stacks will start at the caller of SIGWAIT
	//
	// fp = -1 case, which we need to add support for again
	// this is for "attached" threads that have returned to C, but
	// have been given references which now reside in the JNIEnv sidestack

	if (VM_Allocator.verbose >= 3) VM.sysWriteln("    Scanning stack for thread ",i);
	VM_ScanStack.scanStack(t, VM_Address.zero(), true);
      } 
    } 
  }
  

}

