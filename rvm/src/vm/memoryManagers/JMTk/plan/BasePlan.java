/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 * All rights reserved.
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;
import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_CollectorThread;
import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.memoryManagers.vmInterface.AddressSet;
import com.ibm.JikesRVM.memoryManagers.vmInterface.AddressPairSet;
import com.ibm.JikesRVM.memoryManagers.vmInterface.AddressTripleSet;
import com.ibm.JikesRVM.memoryManagers.vmInterface.ScanObject;
import com.ibm.JikesRVM.memoryManagers.vmInterface.ScanThread;
import com.ibm.JikesRVM.memoryManagers.vmInterface.ScanStatics;
import com.ibm.JikesRVM.memoryManagers.vmInterface.SynchronizationBarrier;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Time;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Offset;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_Uninterruptible;
import com.ibm.JikesRVM.VM_PragmaInline;
import com.ibm.JikesRVM.VM_PragmaNoInline;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;
import com.ibm.JikesRVM.VM_PragmaInterruptible;
import com.ibm.JikesRVM.VM_Processor;
import com.ibm.JikesRVM.VM_Scheduler;
import com.ibm.JikesRVM.VM_Thread;
import com.ibm.JikesRVM.VM_ObjectModel;

/**
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
public abstract class BasePlan implements Constants, VM_Uninterruptible {

  public final static String Id = "$Id$"; 

  public static int verbose = 1;

  public AddressSet values;                 // gray objects
  public AddressSet locations;              // locations containing white objects
  public AddressPairSet interiorLocations;  // interior locations

  protected AddressQueue valuesX;
  protected AddressQueue locationsX;
  protected AddressPairQueue interiorLocationsX;
  protected static SharedQueue valuePool;
  protected static SharedQueue locationPool;
  protected static SharedQueue interiorPool;
  private static MonotoneVMResource metaDataVM;
  protected static MemoryResource metaDataMR;
  private static RawPageAllocator metaDataRPA;
  protected static final EXTENT MAX_USER_VM = 512 * 1024 * 1024;
  protected static final VM_Address META_DATA_START = VM_Address.fromInt(VM_Interface.bootImageAddress).add(MAX_USER_VM);
  protected static final EXTENT     META_DATA_SIZE = 16 * 1024 * 1024;
  private static final VM_Address  META_DATA_END = META_DATA_START.add(META_DATA_SIZE);  

  static {
    metaDataMR = new MemoryResource();
    metaDataVM = new MonotoneVMResource("Meta data", metaDataMR, META_DATA_START, META_DATA_SIZE, VMResource.META_DATA);
    metaDataRPA = new RawPageAllocator(metaDataVM, metaDataMR);
    valuePool = new SharedQueue(metaDataRPA, 1);
    locationPool = new SharedQueue(metaDataRPA, 1);
    interiorPool = new SharedQueue(metaDataRPA, 2);
  }

  // private AddressQueue values;                 // gray objects
  // private AddressPairQueue interiorLocations;  // interior locations

  BasePlan() {
    values = new AddressSet(64 * 1024);
    valuesX = new AddressQueue(valuePool);
    valuePool.newClient();
    locations = new AddressSet(32 * 1024);
    locationsX = new AddressQueue(locationPool);
    locationPool.newClient();
    interiorLocations = new AddressPairSet(16 * 1024);
    interiorLocationsX = new AddressPairQueue(interiorPool);
    interiorPool.newClient();
  }

  /**
   * The boot method is called early in the boot process before any allocation.
   */
  static public void boot() throws VM_PragmaInterruptible {
  }

  /**
   * The boot method is called by the runtime immediately after command-line
   * arguments are available.  Note that allocation must be supported
   * prior to this point because the runtime infrastructure may
   * require allocation in order to parse the command line arguments.
   * For this reason all plans should operate gracefully on the
   * default minimum heap size until the point that boot is called.
   */
  static public void postBoot() {
  }

  protected static boolean gcInProgress = false;    // This flag should be turned on/off by subclasses.
  protected static int gcCount = 0;

  static public boolean gcInProgress() {
    return gcInProgress;
  }

  static public int gcCount() {
    return gcCount;
  }

  /**
   * Prepare for a collection.  In this case, it means flipping
   * semi-spaces and preparing each of the collectors.
   */
  protected final void prepare() {
    SynchronizationBarrier barrier = VM_CollectorThread.gcBarrier;
    double tmp = VM_Time.now();
    int id = barrier.rendezvous();
    if (id == 1) {
      gcInProgress = true;
      gcCount++;
      startTime = tmp;
      singlePrepare();
      resetComputeRoots();
      VM_Interface.prepareNonParticipating(); // The will fix collector threads that are not participating in thie GC.
    }
    VM_Interface.prepareParticipating();      // Every participating thread needs to adjust its context registers.
    barrier.rendezvous();
    allPrepare();
    barrier.rendezvous();
  }

  protected final void release() {
    SynchronizationBarrier barrier = VM_CollectorThread.gcBarrier;
    barrier.rendezvous();
    allRelease();
    int id = barrier.rendezvous();
    if (id == 1) {
      singleRelease();
      gcInProgress = false;    // GC is in progress until after release!
      stopTime = VM_Time.now();
      if (verbose > 0) {
	VM.sysWrite("    Collection time: ", (stopTime - startTime));
	VM.sysWriteln(" seconds");
      }
      locationPool.reset();
    }
    valuesX.reset();
    locationsX.reset();
    interiorLocationsX.reset();
    barrier.rendezvous();
  }

  // These abstract methods are called in the order singlePrepare, allPrepare, allRelease, 
  // and singleRelease.  They are all separated by a barrier.
  abstract protected void singlePrepare();
  abstract protected void allPrepare();
  abstract protected void allRelease();
  abstract protected void singleRelease();

  static SynchronizedCounter threadCounter = new SynchronizedCounter();
  static double startTime;
  static double stopTime;

  private void resetComputeRoots() {
    threadCounter.reset();
  }

  private void computeRoots() {

    AddressPairQueue codeLocations = VM_Interface.MOVES_OBJECTS ? interiorLocationsX : null;

    //    fetchRemsets(locations);
    ScanStatics.scanStatics(locationsX);

    while (true) {
      int threadIndex = threadCounter.increment();
      if (threadIndex >= VM_Scheduler.threads.length) break;
      VM_Thread th = VM_Scheduler.threads[threadIndex];
      if (th == null) continue;
      // VM.sysWrite("Proc ", VM_Processor.getCurrentProcessor().id); VM.sysWriteln(" scanning thread ", threadIndex);
      // See comment of ScanThread.scanThread
      //
      VM_Address thAddr = VM_Magic.objectAsAddress(th);
      VM_Thread th2 = VM_Magic.addressAsThread(Plan.traceObject(thAddr));
      if (VM_Magic.objectAsAddress(th2).EQ(thAddr))
	ScanObject.scan(thAddr);
      ScanObject.scan(VM_Magic.objectAsAddress(th.stack));
      if (th.jniEnv != null) {
	ScanObject.scan(VM_Magic.objectAsAddress(th.jniEnv));
	ScanObject.scan(VM_Magic.objectAsAddress(th.jniEnv.JNIRefs));
      }
      ScanObject.scan(VM_Magic.objectAsAddress(th.contextRegisters));
      ScanObject.scan(VM_Magic.objectAsAddress(th.contextRegisters.gprs));
      ScanObject.scan(VM_Magic.objectAsAddress(th.hardwareExceptionRegisters));
      ScanObject.scan(VM_Magic.objectAsAddress(th.hardwareExceptionRegisters.gprs));
      ScanThread.scanThread(th2, locationsX, codeLocations);
    }
    ScanObject.scan(VM_Magic.objectAsAddress(VM_Scheduler.threads));
    // VM.sysWriteln("locations size is ", locations.size());
    // VM.sysWriteln("values size is ", values.size());
    // VM.sysWriteln("interiorLocations size is ", interiorLocations.size());
  }

  // Add a gray object
  //
  static public void enqueue(VM_Address obj) throws VM_PragmaInline {
    VM_Interface.getPlan().valuesX.push(obj);
  }

  private void processAllWork() throws VM_PragmaNoInline {

    while (true) {
      while (!valuesX.isEmpty()) {
	VM_Address v = valuesX.pop();
	ScanObject.scan(v);  // NOT traceObject
      }
      while (!locationsX.isEmpty()) {
	VM_Address loc = locationsX.pop();
	traceObjectLocation(loc);
      }
      while (!interiorLocationsX.isEmpty()) {
	VM_Address obj = interiorLocationsX.pop1();
	VM_Address interiorLoc = interiorLocationsX.pop2();
	VM_Address interior = VM_Magic.getMemoryAddress(interiorLoc);
	VM_Address newInterior = traceInteriorReference(obj, interior);
	VM_Magic.setMemoryAddress(interiorLoc, newInterior);
      }

      if (valuesX.isEmpty() && locationsX.isEmpty() && interiorLocationsX.isEmpty())
	break;
    }

  }

  protected void collect() {
    computeRoots();
    processAllWork();
  }

  public static int getTotalPages() throws VM_PragmaUninterruptible { 
    heapPages = Conversions.bytesToPages(Options.initialHeapSize);
    return heapPages; 
  }

  private static int heapPages;

  /**
   * Perform a write barrier operation for the putField bytecode.<p> <b>By default do nothing,
   * override if appropriate.</b>
   *
   * @param srcObj The address of the object containing the pointer to
   * be written to
   * @param offset The offset from srcObj of the slot the pointer is to be written into
   * @param tgt The address to which the source will point
   */
  public void putFieldWriteBarrier(VM_Address srcObj, int offset, VM_Address tgt){
  }

  /**
   * Perform a write barrier operation for the putStatic bytecode.<p> <b>By default do nothing,
   * override if appropriate.</b>
   *
   * @param srcObj The address of the object containing the pointer to
   * be written to
   * @param offset The offset from static table (JTOC) of the slot the pointer is to be written into
   * @param tgt The address to which the source will point
   */
  public void putStaticWriteBarrier(VM_Address slot, VM_Address tgt){
  }
  public void arrayStoreWriteBarrier(VM_Address ref, int index, VM_Address value) {
  }
  public void arrayCopyWriteBarrier(VM_Address ref, int startIndex, int endIndex) {
  }

  /**
   * Perform a read barrier operation of the getField bytecode.<p> <b>By default do nothing,
   * override if appropriate.</b>
   *
   * @param tgtObj The address of the object containing the pointer
   * about to be read
   * @param offset The offset from tgtObj of the field to be read from
   */
  public void getFieldReadBarrier(VM_Address tgtObj, int offset) {}

  /**
   * Perform a read barrier operation for the getStatic bytecode.<p> <b>By default do nothing,
   * override if appropriate.</b>
   *
   * @param tgtObj The address of the object containing the pointer
   * about to be read
   * @param offset The offset from tgtObj of the field to be read from
   */
  public void getStaticReadBarrier(int staticOffset) {}

  /**
   * Trace a reference during GC.  This involves determining which
   * collection policy applies and calling the appropriate
   * <code>trace</code> method.
   *
   * @param obj The object reference to be traced.  This is <i>NOT</i> an
   * interior pointer.
   * @return The possibly moved reference.
   */
  // abstract static public VM_Address traceObject(VM_Address obj);

  /**
   * Answers true if the given object will not move during this GC
   * or else has already moved.
   *
   * @param obj The object reference whose movability status must be answered.
   * @return whether object has moved or will not move.
   */
  // abstract static public boolean hasMoved(VM_Address obj);


  /**
   * Trace a reference during GC.  This involves determining which
   * collection policy applies and calling the appropriate
   * <code>trace</code> method.
   *
   * @param objLoc The location containing the object reference to be traced.  
   *    The object reference is <i>NOT</i> an interior pointer.
   * @return void
   */
  final static public void traceObjectLocation(VM_Address objLoc) throws VM_PragmaInline {
    VM_Address obj = VM_Magic.getMemoryAddress(objLoc);
    VM_Address newObj = Plan.traceObject(obj);
    VM_Magic.setMemoryAddress(objLoc, newObj);
  }


  /**
   * Trace a reference during GC.  This involves determining which
   * collection policy applies and calling the appropriate
   * <code>trace</code> method.
   *
   * @param obj The object reference to be traced.  This is <i>NOT</i> an
   * interior pointer.
   * @param interiorRef The interior reference inside obj that must be traced.
   * @return The possibly moved interior reference.
   */
  final static public VM_Address traceInteriorReference(VM_Address obj, VM_Address interiorRef) {
    VM_Offset offset = interiorRef.diff(obj);
    VM_Address newObj = Plan.traceObject(obj);
    if (VM.VerifyAssertions) {
      if (offset.toInt() > (1<<24)) {  // There is probably no object this large
	VM.sysWriteln("ERROR: Suspciously large delta of interior pointer from object base");
	VM.sysWriteln("       object base = ", obj);
	VM.sysWriteln("       interior reference = ", interiorRef);
	VM.sysWriteln("       delta = ", offset.toInt());
	VM._assert(false);
      }
    }
    return newObj.add(offset);
  }

}
