/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.*;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_ObjectModel;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;
import com.ibm.JikesRVM.VM_PragmaInterruptible;
import com.ibm.JikesRVM.VM_PragmaInline;
import com.ibm.JikesRVM.VM_PragmaNoInline;
import com.ibm.JikesRVM.VM_Scheduler;
import com.ibm.JikesRVM.VM_Thread;

/**
 * This class implements a simple semi-space collector. See the Jones
 * & Lins GC book, section 2.2 for an overview of the basic algorithm.
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
public final class Plan extends BasePlan { // implements Constants 
  public final static String Id = "$Id$"; 

  public static final boolean needsWriteBarrier = false;
  public static final boolean movesObjects = true;

  ////////////////////////////////////////////////////////////////////////////
  //
  // Public static methods (aka "class methods")
  //
  // Static methods and fields of Plan are those with global scope,
  // such as virtual memory and memory resources.  This stands in
  // contrast to instance methods which are for fast, unsychronized
  // access to thread-local structures such as bump pointers and
  // remsets.
  //


  ////////////////////////////////////////////////////////////////////////////
  //
  // Public instance methods
  //
  // Instances of Plan map 1:1 to "kernel threads" (aka CPUs or in
  // Jikes RVM, VM_Processors).  Thus instance methods allow fast,
  // unsychronized access to Plan utilities such as allocation and
  // collection.  Each instance rests on static resources (such as
  // memory and virtual memory resources) which are "global" and
  // therefore "static" members of Plan.
  //

  /**
   * Constructor
   */
  public Plan() {
    ss = new BumpPointer(ss0VM, ssMR);
    los = new LOSPointer(losVM, losMR);
    immortal = new BumpPointer(immortalVM, immortalMR);
  }

  /**
   * Allocate space (for an object)
   *
   * @param allocator The allocator number to be used for this allocation
   * @param bytes The size of the space to be allocated (in bytes)
   * @param isScalar True if the object occupying this space will be a scalar
   * @param advice Statically-generated allocation advice for this allocation
   * @return The address of the first byte of the allocated region
   */
  public VM_Address alloc(int allocator, EXTENT bytes, boolean isScalar, AllocAdvice advice) throws VM_PragmaInline {
    if (VM.VerifyAssertions) VM._assert(bytes == (bytes & (~3)));
    if (allocator == BP_ALLOCATOR && bytes > LOS_SIZE_THRESHOLD) allocator = LOS_ALLOCATOR;
    VM_Address region;
    switch (allocator) {
      case       BP_ALLOCATOR: region = ss.alloc(isScalar, bytes); break;
      case IMMORTAL_ALLOCATOR: region = immortal.alloc(isScalar, bytes); break;
      case      LOS_ALLOCATOR: region = los.alloc(isScalar, bytes); 
			       break;
      default:                 region = VM_Address.zero(); VM.sysFail("No such allocator");
    }
    if (VM.VerifyAssertions) VM._assert(Memory.isZeroed(region, bytes));
    return region;
  }

  public void postAlloc(int allocator, EXTENT bytes, Object obj) throws VM_PragmaInline {
    if (allocator == BP_ALLOCATOR && bytes > LOS_SIZE_THRESHOLD) allocator = LOS_ALLOCATOR;
    switch (allocator) {
      case       BP_ALLOCATOR: return;
      case IMMORTAL_ALLOCATOR: return;
      case      LOS_ALLOCATOR: los.postAlloc(obj); return;
      default:                 VM.sysFail("No such allocator");
    }
  }

  /**
   * Allocate space for copying an object (this method <i>does not</i>
   * copy the object, it only allocates space)
   *
   * @param original A reference to the original object
   * @param bytes The size of the space to be allocated (in bytes)
   * @param isScalar True if the object occupying this space will be a scalar
   * @return The address of the first byte of the allocated region
   */
  public VM_Address allocCopy(VM_Address original, EXTENT bytes, boolean isScalar) {
    return ss.alloc(isScalar, bytes);
  }

  /**
   * Advise the compiler/runtime which allocator to use for a
   * particular allocation.  This should be called at compile time and
   * the returned value then used for the given site at runtime.
   *
   * @param type The type id of the type being allocated
   * @param bytes The size (in bytes) required for this object
   * @param callsite Information identifying the point in the code
   * where this allocation is taking place.
   * @param hint A hint from the compiler as to which allocator this
   * site should use.
   * @return The allocator number to be used for this allocation.
   */
  public int getAllocator(Type type, EXTENT bytes, CallSite callsite, AllocAdvice hint) {
    return (bytes >= LOS_SIZE_THRESHOLD) ? LOS_ALLOCATOR : BP_ALLOCATOR;
  }

  /**
   * Give the compiler/runtime statically generated alloction advice
   * which will be passed to the allocation routine at runtime.
   *
   * @param type The type id of the type being allocated
   * @param bytes The size (in bytes) required for this object
   * @param callsite Information identifying the point in the code
   * where this allocation is taking place.
   * @param hint A hint from the compiler as to which allocator this
   * site should use.
   * @return Allocation advice to be passed to the allocation routine
   * at runtime
   */
  public AllocAdvice getAllocAdvice(Type type, EXTENT bytes,
				    CallSite callsite, AllocAdvice hint) { 
    return null;
  }

  /**
   * This method is called periodically by the allocation subsystem
   * (by default, each time a block is consumed), and provides the
   * collector with an opportunity to collect.<p>
   *
   * We trigger a collection whenever an allocation request is made
   * that would take the number of blocks in use (committed for use)
   * beyond the number of blocks available.  Collections are triggered
   * through the runtime, and ultimately call the
   * <code>collect()</code> method of this class or its superclass.
   *
   */

  public void poll(boolean mustCollect) {
    if (gcInProgress) return;
    if (mustCollect || getBlocksReserved() > getHeapBlocks()) {
      VM.sysWriteln("ss reserveBlocks = ", ssMR.reservedBlocks());
      VM.sysWriteln("los reserveBlocks = ", losMR.reservedBlocks());
      VM.sysWriteln("imm reserveBlocks = ", immortalMR.reservedBlocks());
      VM.sysWriteln("heapBlocks = ", getHeapBlocks());
      VM_Interface.triggerCollection();
    }
  }
  
   
  public static boolean isLive(VM_Address obj) {
    VM_Address addr = VM_ObjectModel.getPointerInMemoryRegion(obj);
    if (addr.LE(HEAP_END)) {
      if (addr.GE(SS_START))
	return Copy.isLive(obj);
      else if (addr.GE(LOS_START))
	return losVM.isLive(obj);
      else if (addr.GE(IMMORTAL_START))
	return true;
    } 
    return false;
  }

  /**
   * Trace a reference during GC.  This involves determining which
   * collection policy applies and calling the appropriate
   * <code>trace</code> method.
   *
   * @param obj The object reference to be traced.  This is <i>NOT</i> an
   * interior pointer.
   * @return The possibly moved reference.
   */
  public VM_Address traceObject(VM_Address obj) {
    VM_Address addr = VM_Interface.refToAddress(obj);
    if (addr.LE(HEAP_END)) {
      if (addr.GE(SS_START)) {
	if (hi) {
	  if (addr.LT(HIGH_SS_START))
	    return Copy.traceObject(obj);
	  return obj;
	}
	else {
	  if (addr.GE(HIGH_SS_START))
	    return Copy.traceObject(obj);
	  return obj;

	}
      }
      else if (addr.GE(LOS_START))
	return losVM.traceObject(obj);
      else if (addr.GE(IMMORTAL_START))
	return Immortal.traceObject(obj);
    } // else this is not a heap pointer
    return obj;
  }

  public boolean hasMoved(VM_Address obj) {
    VM_Address addr = VM_Interface.refToAddress(obj);
    if (addr.LE(HEAP_END)) {
      if (addr.GE(SS_START)) 
	return (hi ? ss1VM : ss0VM).inRange(addr);
      else if (addr.GE(LOS_START))
	return true;
      else if (addr.GE(IMMORTAL_START))
	return true;
    } 
    return true;
  }

  /**
   * Perform a collection.
   */
  public void collect () {
    // VM.sysWriteln("VM_Scheduler.threads = ", VM_Magic.objectAsAddress(VM_Scheduler.threads)); VM_Thread.dumpAll(1);
    prepare();
    super.collect();
    release();
    // VM.sysWriteln("VM_Scheduler.threads = ", VM_Magic.objectAsAddress(VM_Scheduler.threads)); VM_Thread.dumpAll(1);
  }

  public static final long freeMemory() throws VM_PragmaUninterruptible {
    VM.sysWriteln("freeMemory possibly not implemented correctly");
    return totalMemory() - usedMemory();
  }

  public static final long usedMemory() throws VM_PragmaUninterruptible {
    VM.sysWriteln("usedMemory possibly not implemented correctly");
    return Conversions.blocksToBytes(ssMR.committedBlocks());
  }

  public static final long totalMemory() throws VM_PragmaUninterruptible {
    VM.sysWriteln("totalMemory possibly not implemented correctly");
    return Conversions.blocksToBytes(getHeapBlocks());
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Private methods
  //

  /**
   * Return the number of blocks reserved for use.
   *
   * @return The number of blocks reserved given the pending allocation
   */
  private int getBlocksReserved() {

    int blocks = ssMR.reservedBlocks();
    // we must account for the worst case number of blocks required
    // for copying, which equals the number of semi-space blocks in
    // use plus a fudge factor which accounts for fragmentation
    blocks += blocks + COPY_FUDGE_BLOCKS;
    
    blocks += losMR.reservedBlocks();
    blocks += immortalMR.reservedBlocks();
    return blocks;
  }

  /**
   * Prepare for a collection.  In this case, it means flipping
   * semi-spaces and preparing each of the collectors.
   * Called by BasePlan which will make sure only one thread executes this.
   */
  protected void singlePrepare() {
    hi = !hi;          // flip the semi-spaces
    ssMR.release();    // reset the semispace memory resource, and
    // prepare each of the collected regions
    Copy.prepare(((hi) ? ss0VM : ss1VM), ssMR);
    losVM.prepare(losVM, losMR);
    Immortal.prepare(immortalVM, null);
  }

  protected void allPrepare() {
    // rebind the semispace bump pointer to the appropriate semispace.
    ss.rebind(((hi) ? ss1VM : ss0VM)); 
  }

  /* We reset the state for a GC thread that is not participating in this GC
   */
  public void prepareNonParticipating() {
    allPrepare();
    VM.sysWriteln("Plan.prepareNonParticipating not implemented - FIXME");
  }

  /**
   * Clean up after a collection.
   */
  protected void singleRelease() {
    // release each of the collected regions
    Copy.release(((hi) ? ss0VM : ss1VM), ssMR);
    losVM.release(losVM, losMR); 
    Immortal.release(immortalVM, null);
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Instance variables
  //
  private BumpPointer ss;
  private BumpPointer immortal;
  private LOSPointer los;
  

  ////////////////////////////////////////////////////////////////////////////
  //
  // Class variables
  //
  // virtual memory regions
  private static LOSVMResource losVM;
  private static MonotoneVMResource ss0VM;
  private static MonotoneVMResource ss1VM;
  private static ImmortalMonotoneVMResource immortalVM;

  // memory resources
  private static MemoryResource ssMR;
  private static MemoryResource losMR;
  private static MemoryResource immortalMR;

  // GC state
  private static boolean hi = false;   // If true, we are allocating from the "higher" semispace.


  //
  // Final class variables (aka constants)
  //
  private static final VM_Address IMMORTAL_START = VM_Address.fromInt(0x30000000);
  private static final VM_Address     BOOT_START = IMMORTAL_START;                 // check against jconfigure
  private static final EXTENT          BOOT_SIZE = 256 * 1024 * 1024;              // use the whole segment
  private static final VM_Address       BOOT_END = BOOT_START.add(BOOT_SIZE);
  private static final EXTENT      IMMORTAL_SIZE = BOOT_SIZE + 16 * 1024 * 1024;
  private static final VM_Address      LOS_START = VM_Address.fromInt(0x42000000);
  private static final EXTENT           LOS_SIZE = 48 * 1024 * 1024;
  private static final VM_Address       SS_START = VM_Address.fromInt(0x50000000);
  private static final EXTENT            SS_SIZE = 256 * 1024 * 1024;              // size of each space
  private static final VM_Address   LOW_SS_START = SS_START;
  private static final VM_Address  HIGH_SS_START = SS_START.add(SS_SIZE);
  private static final VM_Address       HEAP_END = HIGH_SS_START.add(SS_SIZE);
  private static final EXTENT LOS_SIZE_THRESHOLD = 16 * 1024;

  private static final int COPY_FUDGE_BLOCKS = 1;  // Steve - fix this

  public static final int DEFAULT_ALLOCATOR = 0;
  public static final int BP_ALLOCATOR = 0;
  public static final int LOS_ALLOCATOR = 1;
  public static final int IMMORTAL_ALLOCATOR = 2;


  /**
   * Class initializer.  This is executed <i>prior</i> to bootstrap
   * (i.e. at "build" time).
   */
  static {
    // virtual memory resources
    ss0VM      = new MonotoneVMResource("Lower semispace", LOW_SS_START,       SS_SIZE,       
					(byte) (VMResource.IN_VM | VMResource.MOVABLE));
    ss1VM      = new MonotoneVMResource("Upper semispace", HIGH_SS_START,  SS_SIZE,       
					(byte) (VMResource.IN_VM | VMResource.MOVABLE));
    losVM      = new LOSVMResource("Large obj space", LOS_START,      LOS_SIZE,      
				   VMResource.IN_VM);
    immortalVM = new ImmortalMonotoneVMResource("Immortal space",  IMMORTAL_START, IMMORTAL_SIZE, BOOT_END,
						(byte) (VMResource.IN_VM | VMResource.IMMORTAL));
    // memory resources
    ssMR = new MemoryResource();
    losMR = new MemoryResource();
    immortalMR = new MemoryResource();
  }

}
   
