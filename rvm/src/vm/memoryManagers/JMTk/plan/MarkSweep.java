/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 */
package org.mmtk.plan;

import org.mmtk.policy.ImmortalSpace;
import org.mmtk.policy.MarkSweepSpace;
import org.mmtk.policy.MarkSweepLocal;
import org.mmtk.policy.TreadmillSpace;
import org.mmtk.policy.TreadmillLocal;
import org.mmtk.utility.alloc.AllocAdvice;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.utility.alloc.BumpPointer;
import org.mmtk.utility.CallSite;
import org.mmtk.utility.Conversions;
import org.mmtk.utility.heap.*;
import org.mmtk.utility.Log;
import org.mmtk.utility.Options;
import org.mmtk.utility.scan.*;
import org.mmtk.vm.Assert;
import org.mmtk.vm.Collection;
import org.mmtk.vm.Memory;
import org.mmtk.vm.ObjectModel;
import org.mmtk.vm.Plan;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * This class implements a simple mark-sweep collector.
 *
 * All plans make a clear distinction between <i>global</i> and
 * <i>thread-local</i> activities.  Global activities must be
 * synchronized, whereas no synchronization is required for
 * thread-local activities.  Instances of Plan map 1:1 to "kernel
 * threads" (aka CPUs or in Jikes RVM, VM_Processors).  Thus instance
 * methods allow fast, unsychronized access to Plan utilities such as
 * allocation and collection.  Each instance rests on static resources
 * (such as memory and virtual memory resources) which are "global"
 * and therefore "static" members of Plan.  This mapping of threads to
 * instances is crucial to understanding the correctness and
 * performance proprties of this plan.
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 *
 * @version $Revision$
 * @date $Date$
 */
public class MarkSweep extends StopTheWorldGC implements Uninterruptible {
  public final static String Id = "$Id$"; 

  /****************************************************************************
   *
   * Class variables
   */
  public static final boolean MOVES_OBJECTS = false;
  public static final int GC_HEADER_BITS_REQUIRED = MarkSweepSpace.LOCAL_GC_BITS_REQUIRED;
  public static final int GC_HEADER_BYTES_REQUIRED = MarkSweepSpace.GC_HEADER_BYTES_REQUIRED;

  // virtual memory resources
  private static FreeListVMResource msVM;
  private static FreeListVMResource losVM;

  // memory resources
  private static MemoryResource msMR;
  private static MemoryResource losMR;

  // MS collector
  private static MarkSweepSpace msSpace;
  private static TreadmillSpace losSpace;

  // GC state
  private static int msReservedPages;
  private static int availablePreGC;

  // Allocators
  public static final byte MS_SPACE = 0;
  public static final byte DEFAULT_SPACE = MS_SPACE;

  // Miscellaneous constants
  // XXX 512<<10 should be a named constant
  private static final int MS_PAGE_RESERVE = (512<<10)>>>LOG_BYTES_IN_PAGE; // 1M
  private static final double MS_RESERVE_FRACTION = 0.1;
  private static final int POLL_FREQUENCY = DEFAULT_POLL_FREQUENCY;

  // Memory layout constants
  public  static final long            AVAILABLE = Memory.MAXIMUM_MAPPABLE.diff(PLAN_START).toLong();
  private static final Extent        LOS_SIZE = Conversions.roundDownVM(Extent.fromIntZeroExtend((int)(AVAILABLE * 0.3)));
  private static final Extent         MS_SIZE = Conversions.roundDownVM(Extent.fromIntZeroExtend((int)(AVAILABLE * 0.7)));
  public  static final Extent        MAX_SIZE = MS_SIZE;

  private static final Address      LOS_START = PLAN_START;
  private static final Address        LOS_END = LOS_START.add(LOS_SIZE);
  private static final Address       MS_START = LOS_END;
  private static final Address         MS_END = MS_START.add(MS_SIZE);
  private static final Address       HEAP_END = MS_END;

  /****************************************************************************
   *
   * Instance variables
   */

  // allocators
  private MarkSweepLocal ms;
  private TreadmillLocal los;

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Class initializer.  This is executed <i>prior</i> to bootstrap
   * (i.e. at "build" time).  This is where key <i>global</i>
   * instances are allocated.  These instances will be incorporated
   * into the boot image by the build process.
   */
  static {
    msMR = new MemoryResource("ms", POLL_FREQUENCY);
    losMR = new MemoryResource("los", POLL_FREQUENCY);
    msVM = new FreeListVMResource(MS_SPACE, "MS",       MS_START,   MS_SIZE, VMResource.IN_VM, MarkSweepLocal.META_DATA_PAGES_PER_REGION);
    losVM = new FreeListVMResource(LOS_SPACE, "LOS", LOS_START, LOS_SIZE, VMResource.IN_VM);
    msSpace = new MarkSweepSpace(msVM, msMR);
    losSpace = new TreadmillSpace(losVM, losMR);

    addSpace(MS_SPACE, "Mark-sweep Space");
    addSpace(LOS_SPACE, "LOS Space");
  }

  /**
   * Constructor
   */
  public MarkSweep() {
    ms = new MarkSweepLocal(msSpace);
    los = new TreadmillLocal(losSpace);
  }

  /**
   * The boot method is called early in the boot process before any
   * allocation.
   */
  public static final void boot()
    throws InterruptiblePragma {
    StopTheWorldGC.boot();
    msReservedPages = (int) (getTotalPages() * MS_RESERVE_FRACTION);
  }


  /****************************************************************************
   *
   * Allocation
   */

  /**
   * Allocate space (for an object)
   *
   * @param bytes The size of the space to be allocated (in bytes)
   * @param align The requested alignment.
   * @param offset The alignment offset.
   * @param allocator The allocator number to be used for this allocation
   * @return The address of the first byte of the allocated region
   */
  public final Address alloc(int bytes, int align, int offset, int allocator)
    throws InlinePragma {
    switch (allocator) {
    case       MS_SPACE: return ms.alloc(bytes, align, offset, false);
    case      LOS_SPACE: return los.alloc(bytes, align, offset);
    case IMMORTAL_SPACE: return immortal.alloc(bytes, align, offset);
    default:
      if (Assert.VERIFY_ASSERTIONS) Assert.fail("No such allocator"); 
      return Address.zero();
    }
  }
  
  /**
   * Perform post-allocation actions.  For many allocators none are
   * required.
   *
   * @param ref The newly allocated object
   * @param typeRef The type reference for the instance being created
   * @param bytes The size of the space to be allocated (in bytes)
   * @param allocator The allocator number to be used for this allocation
   */
  public final void postAlloc(Address ref, Address typeRef, int bytes,
                              int allocator)
    throws InlinePragma {
    switch (allocator) {
    case  MS_SPACE: msSpace.initializeHeader(ref); return;
    case LOS_SPACE: losSpace.initializeHeader(ref); return;
    case IMMORTAL_SPACE: ImmortalSpace.postAlloc(ref); return;
    default:
      if (Assert.VERIFY_ASSERTIONS) Assert.fail("No such allocator"); 
    }
  }

  /**
   * Allocate space for copying an object (this method <i>does not</i>
   * copy the object, it only allocates space)
   *
   * @param original A reference to the original object
   * @param bytes The size of the space to be allocated (in bytes)
   * @param align The requested alignment.
   * @param offset The alignment offset.
   * @return The address of the first byte of the allocated region
   */
  public final Address allocCopy(Address original, int bytes,
				 int align, int offset) throws InlinePragma {
    if (Assert.VERIFY_ASSERTIONS) Assert._assert(false);
    // return Address.zero();  this trips some Intel assembler bug
    return Address.max();
  }

  /**  
   * Perform any post-copy actions.  In this case nothing is required.
   *
   * @param ref The newly allocated object
   * @param typeRef The type reference for the instance being created
   * @param bytes The size of the space to be allocated (in bytes)
   */
  public final void postCopy(Address ref, Address typeRef, int size) {}

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
  public final AllocAdvice getAllocAdvice(MMType type, int bytes,
                                          CallSite callsite,
                                          AllocAdvice hint) { 
    return null;
  }

  protected final byte getSpaceFromAllocator (Allocator a) {
    if (a == ms) return DEFAULT_SPACE;
    if (a == los) return LOS_SPACE;
    return super.getSpaceFromAllocator(a);
  }

  protected final Allocator getAllocatorFromSpace (byte s) {
    if (s == DEFAULT_SPACE) return ms;
    if (s == LOS_SPACE) return los;
    return super.getAllocatorFromSpace(s);
  }

  /**
   * This method is called periodically by the allocation subsystem
   * (by default, each time a page is consumed), and provides the
   * collector with an opportunity to collect.<p>
   *
   * We trigger a collection whenever an allocation request is made
   * that would take the number of pages in use (committed for use)
   * beyond the number of pages available.  Collections are triggered
   * through the runtime, and ultimately call the
   * <code>collect()</code> method of this class or its superclass.
   *
   * This method is clearly interruptible since it can lead to a GC.
   * However, the caller is typically uninterruptible and this fiat allows 
   * the interruptibility check to work.  The caveat is that the caller 
   * of this method must code as though the method is interruptible. 
   * In practice, this means that, after this call, processor-specific
   * values must be reloaded.
   *
   * XXX No Javadoc params.
   *
   * @return Whether a collection is triggered
   */
  public final boolean poll(boolean mustCollect, MemoryResource mr)
    throws LogicallyUninterruptiblePragma {
    if (collectionsInitiated > 0 || !initialized || mr == metaDataMR)
      return false;
    mustCollect |= stressTestGCRequired() || ms.mustCollect();
    availablePreGC = getTotalPages() - getPagesReserved();
    int reserve = (mr == msMR) ? msReservedPages : 0;
    if (mustCollect || availablePreGC <= reserve) {
      required = mr.reservedPages() - mr.committedPages();
      Collection.triggerCollection(Collection.RESOURCE_GC_TRIGGER);
      return true;
    }
    return false;
  }

  /****************************************************************************
   *
   * Collection
   *
   * Important notes:
   *   . Global actions are executed by only one thread
   *   . Thread-local actions are executed by all threads
   *   . The following order is guaranteed by BasePlan, with each
   *     separated by a synchronization barrier.:
   *      1. globalPrepare()
   *      2. threadLocalPrepare()
   *      3. threadLocalRelease()
   *      4. globalRelease()
   */

  /**
   * Perform operations with <i>global</i> scope in preparation for a
   * collection.  This is called by <code>StopTheWorld</code>, which will
   * ensure that <i>only one thread</i> executes this.<p>
   *
   * In this case, it means preparing each of the collectors.
   */
  protected final void globalPrepare() {
    msSpace.prepare(msVM, msMR);
    ImmortalSpace.prepare(immortalVM, null);
    losSpace.prepare(losVM, losMR);
  }

  /**
   * Perform operations with <i>thread-local</i> scope in preparation
   * for a collection.  This is called by <code>StopTheWorld</code>, which
   * will ensure that <i>all threads</i> execute this.<p>
   *
   * In this case, it means resetting mark sweep allocator.
   * XXX No Javadoc params.
   */
  protected final void threadLocalPrepare(int count) {
    ms.prepare();
    los.prepare();
  }

  /**
   * Perform operations with <i>thread-local</i> scope to clean up at
   * the end of a collection.  This is called by
   * <code>StopTheWorld</code>, which will ensure that <i>all threads</i>
   * execute this.<p>
   *
   * In this case, it means releasing the mark sweep space (which
   * triggers the sweep phase of the mark-sweep collector).
   * XXX No Javadoc params.
   */
  protected final void threadLocalRelease(int count) {
    ms.release();
    los.release();
  }

  /**
   * Perform operations with <i>global</i> scope to clean up at the
   * end of a collection.  This is called by <code>StopTheWorld</code>,
   * which will ensure that <i>only one</i> thread executes this.<p>
   *
   * In this case, it means releasing each of the spaces and checking
   * whether the GC made progress.
   */
  protected final void globalRelease() {
    // release each of the collected regions
    losSpace.release();
    msSpace.release();
    ImmortalSpace.release(immortalVM, null);
    int available = getTotalPages() - getPagesReserved();

    progress = (available > availablePreGC) && (available > exceptionReserve);
    if (progress) {
      msReservedPages = (int) (available * MS_RESERVE_FRACTION);
      int threshold = 2 * exceptionReserve;
      if (threshold < MS_PAGE_RESERVE) threshold = MS_PAGE_RESERVE;
      if (msReservedPages < threshold) 
        msReservedPages = threshold;
    } else {
      msReservedPages = msReservedPages/2;
    }
  }


  /****************************************************************************
   *
   * Object processing and tracing
   */

  /**
   * Trace a reference during GC.  This involves determining which
   * collection policy applies and calling the appropriate
   * <code>trace</code> method.
   *
   * @param obj The object reference to be traced.  This is <i>NOT</i> an
   * interior pointer.
   * @return The possibly moved reference.
   */
  public static final Address traceObject(Address obj) {
    if (obj.isZero()) return obj;
    Address addr = ObjectModel.refToAddress(obj);
    byte space = VMResource.getSpace(addr);
    switch (space) {
    case MS_SPACE:        return msSpace.traceObject(obj);
    case LOS_SPACE:       return losSpace.traceObject(obj);
    case IMMORTAL_SPACE:  return ImmortalSpace.traceObject(obj);
    case BOOT_SPACE:      return ImmortalSpace.traceObject(obj);
    case META_SPACE:      return obj;
    default:
      if (Assert.VERIFY_ASSERTIONS)
	spaceFailure(obj, space, "Plan.traceObject()");
      return obj;
    }
  }

  /**
   * Trace a reference during GC.  This involves determining which
   * collection policy applies and calling the appropriate
   * <code>trace</code> method.
   *
   * @param obj The object reference to be traced.  This is <i>NOT</i>
   * an interior pointer.
   * @param root True if this reference to <code>obj</code> was held
   * in a root.
   * @return The possibly moved reference.
   */
  public static final Address traceObject(Address obj, boolean root) {
    return traceObject(obj);  // root or non-root is of no consequence here
  }


  /**
   * Return true if <code>obj</code> is a live object.
   *
   * @param obj The object in question
   * @return True if <code>obj</code> is a live object.
   */
  public static final boolean isLive(Address obj) {
    if (obj.isZero()) return false;
    Address addr = ObjectModel.refToAddress(obj);
    byte space = VMResource.getSpace(addr);
    switch (space) {
    case MS_SPACE:        return msSpace.isLive(obj);
    case LOS_SPACE:       return losSpace.isLive(obj);
    case IMMORTAL_SPACE:  return true;
    case BOOT_SPACE:      return true;
    case META_SPACE:      return true;
    default:
      if (Assert.VERIFY_ASSERTIONS)
        spaceFailure(obj, space, "Plan.isLive()");
      return false;
    }
  }

  /****************************************************************************
   *
   * Space management
   */

  /**
   * Return the number of pages reserved for use given the pending
   * allocation.
   *
   * @return The number of pages reserved given the pending
   * allocation, including space reserved for copying.
   */
  protected static final int getPagesReserved() {
    return getPagesUsed();
  }

  /**
   * Return the number of pages reserved for use given the pending
   * allocation.
   *
   * @return The number of pages reserved given the pending
   * allocation, excluding space reserved for copying.
   */
  protected static final int getPagesUsed() {
    int pages = msMR.reservedPages();
    pages += losMR.reservedPages();
    pages += immortalMR.reservedPages();
    pages += metaDataMR.reservedPages();
    return pages;
  }

  /**
   * Return the number of pages available for allocation.
   *
   * @return The number of pages available for allocation.
   */
  protected static final int getPagesAvail() {
    return getTotalPages() - msMR.reservedPages() - losMR.reservedPages() - immortalMR.reservedPages();
  }


  /****************************************************************************
   *
   * Miscellaneous
   */

  /**
   * Show the status of each of the allocators.
   */
  public final void show() {
    ms.show();
    los.show();
    immortal.show();
  }

  protected final void planExit(int value) {
    ms.exit();
  }
}
