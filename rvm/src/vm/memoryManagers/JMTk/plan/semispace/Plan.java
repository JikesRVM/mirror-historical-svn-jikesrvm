/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 * All rights reserved.
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

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


  /**
   * Class initializer.  This is executed <i>prior</i> to bootstrap
   * (i.e. at "build" time).
   */
  {
    // virtual memory resources
    ss0VM = new VMResource(SS_START, SS_SIZE);
    ss1VM = new VMResource(SS_START + SS_SIZE, SS_SIZE);
    losVM = new VMResource(LOS_START, LOS_SIZE);
    immortalVM = new VMResource(IMMORTAL_START, IMMORTAL_SIZE);

    // memory resources
    ssMR = new MemoryResource();
    losMR = new MemoryResource();
    immortalMR = new MemoryResource();
  }


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
  Plan() {
    ss = new AllocatorBumpPointer(ss0VM, ssMR);
    los = new AllocatorLOS(losVM, losMR);
    immortal = new AllocatorBumpPointer(immortalVM, immortalMR);
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
  public VM_Address alloc(int allocator, Extent bytes, boolean isScalar, AllocAdvice advice) {
    if ((allocator == BP_ALLOCATOR) && (bytes.LT(LOS_SIZE_THRESHOLD)))
      return ss.alloc(isScalar, bytes);
    else if (allocator == IMMORTAL_ALLOCATOR)
      return immortal.alloc(isScalar, bytes);
    else 
      return los.alloc(isScalar, bytes);
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
  public VM_Address allocCopy(VM_Address original, Extent bytes, boolean isScalar) {
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
  public int getAllocator(TypeID type, Extent bytes, CallSite callsite, 
			  AllocAdvice hint) {
    if (bytes.GE(LOS_SIZE_THRESHOLD))
      return LOS_ALLOCATOR;
    else
      return BP_ALLOCATOR;
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
  public AllocAdvice getAllocAdvice(TypeID type, Extent bytes,
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

  public void poll() {
    if (getBlocksReserved() > getHeapBlocks())
      MM.triggerCollection();
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
    if (ref.LE(HEAP_END)) {
      if (ref.GE(LOS_START))
	return CollectorLOS.traceReference(obj);
      else if (ref.GE(SS_START))
	return CollectorCopying.traceReference(obj);
      else if (ref.GE(IMMORTAL_START))
	return CollectorImmortal.traceReference(obj);
    } // else this is not a heap pointer
  }


  /**
   * Perform a collection.
   */
  public void collect() {
    prepare();
    collect(null);
    release();
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
    int blocks;

    blocks = ssMR.reservedBlocks();
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
   */
  private void prepare() {
    if (Synchronize.acquireBarrier()) {
      gcInProgres = true;
      hi = !hi;       // flip the semi-spaces
      ssMR.reset();    // reset the semispace memory resource, and
      // rebind the semispace bump pointer to the appropriate semispace.
      ss.rebindVM(((hi) ? ss1VM : ss0VM)); 
      
      // prepare each of the collected regions
      CollectorCopying.prepare(((hi) ? ss0VM : ss1VM), ssMR);
      CollectorLOS.prepare(losVM, losMR);
      CollectorImmortal.prepare(immortalVM, null);
      Synchronize.releaseBarrier();
    }
  }

  /**
   * Clean up after a collection.
   */
  private void release() {
    if (Synchronize.acquireBarrier()) {
      // release each of the collected regions
      CollectorCopying.release(((hi) ? ss0VM : ss1VM), ssMR);
      CollectorLOS.release(losVM, losMR);
      CollectorImmortal.release(immortalVM, null);
      gcInProgres = false;
      Synchronize.releaseBarrier();
    }
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Instance variables
  //
  private AllocatorBumpPointer ss;
  private AllocatorLOS los;
  private AllocatorBumpPointer immortal;

  ////////////////////////////////////////////////////////////////////////////
  //
  // Class variables
  //
  // virtual memory regions
  private static VMResource ss0VM;
  private static VMResource ss1VM;
  private static VMResource losVM;
  private static VMResource immortalVM;

  // memory resources
  private static MemoryResource ssMR;
  private static MemoryResource losMR;
  private static MemoryResource immortalMR;

  ////////////////////////////////////////////////////////////////////////////
  //
  // Final class variables (aka constants)
  //
  private static final VM_Address       SS_START = 0x46000000;
  private static final Extent            SS_SIZE = 128 * 1024 * 1024;
  private static final VM_Address      LOS_START = 0x42000000;
  private static final Extent           LOS_SIZE = 48 * 1024 * 1024;
  private static final VM_Address IMMORTAL_START = 0x40000000;
  private static final Extent      IMMORTAL_SIZE = 16 * 1024 * 1024;
  private static final Extent           LOS_SIZE = 48 * 1024 * 1024;

  private static final int BP_ALLOCATOR = 0;
  private static final int LOS_ALLOCATOR = 1;
  private static final int IMMORTAL_ALLOCATOR = 2;
}
   
