/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

package com.ibm.JikesRVM.memoryManagers.vmInterface;

import com.ibm.JikesRVM.memoryManagers.JMTk.VMResource;
import com.ibm.JikesRVM.memoryManagers.JMTk.Plan;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Processor;
import com.ibm.JikesRVM.VM_Constants;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_ClassLoader;
import com.ibm.JikesRVM.VM_SystemClassLoader;
import com.ibm.JikesRVM.VM_EventLogger;
import com.ibm.JikesRVM.VM_BootRecord;
import com.ibm.JikesRVM.VM_PragmaInline;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;
import com.ibm.JikesRVM.VM_Uninterruptible;
import com.ibm.JikesRVM.VM_PragmaInterruptible;
import com.ibm.JikesRVM.VM_Array;
import com.ibm.JikesRVM.VM_Type;
import com.ibm.JikesRVM.VM_Class;
import com.ibm.JikesRVM.VM_Atom;
import com.ibm.JikesRVM.VM_ObjectModel;
import com.ibm.JikesRVM.VM_JavaHeader;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_Memory;
import com.ibm.JikesRVM.VM_CompiledMethod;
import com.ibm.JikesRVM.VM_DynamicLibrary;

/*
 * @author Perry Cheng  
 */  

public class VM_Interface implements VM_Constants, VM_Uninterruptible {

  public static void logGarbageCollection() throws VM_PragmaUninterruptible {
    if (VM.BuildForEventLogging && VM.EventLoggingEnabled)
      VM_EventLogger.logGarbageCollectionEvent();
  }

  public static VM_Class createScalarType(String descriptor) {
    VM_Atom atom = VM_Atom.findOrCreateAsciiAtom(descriptor);
    return VM_ClassLoader.findOrCreateType(atom, VM_SystemClassLoader.getVMClassLoader()).asClass();
  }

  public static VM_Array createArrayType(String descriptor) {
    VM_Atom atom = VM_Atom.findOrCreateAsciiAtom(descriptor);
    return VM_ClassLoader.findOrCreateType(atom, VM_SystemClassLoader.getVMClassLoader()).asArray();
  }

  public static VM_Address malloc(int size) throws VM_PragmaUninterruptible {
    return VM_Address.fromInt(VM.sysCall1(VM_BootRecord.the_boot_record.sysMallocIP, size));
  }

  public static void free(VM_Address addr) throws VM_PragmaUninterruptible {
    VM.sysCall1(VM_BootRecord.the_boot_record.sysFreeIP, addr.toInt());
  }



  /**
   * Initialization that occurs at <i>build</i> time.  The value of
   * statics as at the completion of this routine will be reflected in
   * the boot image.  Any objects referenced by those statics will be
   * transitively included in the boot image.
   *
   * This is the entry point for all build-time activity in the collector.
   */
  public static final void init () throws VM_PragmaInterruptible {
    VM_CollectorThread.init();
  }


  public static Object[] tibForArrayType;
  public static Object[] tibForClassType;
  public static Object[] tibForPrimitiveType;

  /**
   * Initialization that occurs at <i>boot</i> time (runtime
   * initialization).  This is only executed by one processor (the
   * primordial thread).
   */
  public static final void boot (VM_BootRecord theBootRecord) throws VM_PragmaInterruptible {
    int pageSize = VM_Memory.getPagesize();  // Cannot be determined at init-time
    // get addresses of TIBs for VM_Array & VM_Class used for testing Type ptrs
    VM_Type t = VM_Array.getPrimitiveArrayType(10);
    tibForArrayType = VM_ObjectModel.getTIB(t);
    tibForPrimitiveType = VM_ObjectModel.getTIB(VM_Type.IntType);
    t = VM_Magic.getObjectType(VM_BootRecord.the_boot_record);
    tibForClassType = VM_ObjectModel.getTIB(t);
    Plan.boot();
    Statistics.boot();
  }

  /**
   * Perform postBoot operations such as dealing with command line
   * options (this is called as soon as options have been parsed,
   * which is necessarily after the basic allocator boot).
   */
  public static void postBoot() throws VM_PragmaInterruptible {
  }

  /** 
   *  Process GC parameters.
   */
  public static void processCommandLineArg(String arg) {
      VM.sysWriteln("Unrecognized collection option: ", arg);
      VM.sysExit(1);
  }

  public static int numProcessors() throws VM_PragmaUninterruptible {
    return VM.sysCall0(VM_BootRecord.the_boot_record.sysNumProcessorsIP);
  }

  public static int verbose() throws VM_PragmaUninterruptible {
    return VM_BootRecord.the_boot_record.verboseGC;
  }

  public static void lowYield() {
    VM.sysCall0(VM_BootRecord.the_boot_record.sysVirtualProcessorYieldIP);
  }

  public static int smallHeapSize() throws VM_PragmaUninterruptible {
    return VM_BootRecord.the_boot_record.smallSpaceSize;
  }

  public static int largeHeapSize() throws VM_PragmaUninterruptible {
    return VM_BootRecord.the_boot_record.largeSpaceSize;
  }

  public static int nurseryHeapSize() throws VM_PragmaUninterruptible {
    return VM_BootRecord.the_boot_record.nurserySize;
  }

  public static VM_Address bootImageStart() throws VM_PragmaUninterruptible {
    return  VM_BootRecord.the_boot_record.bootImageStart;
  }

  public static VM_Address bootImageEnd() throws VM_PragmaUninterruptible {
    return  VM_BootRecord.the_boot_record.bootImageEnd;
  }

  public static void setHeapRange(int id, VM_Address start, VM_Address end) throws VM_PragmaUninterruptible {
    VM_BootRecord.the_boot_record.setHeapRange(id, start, end);
  }

  public static Plan getPlan() {
    return VM_Processor.getCurrentProcessor().mmPlan;
  }

  public static void resolvedPutfieldWriteBarrier(Object ref, int offset, Object value) {
    getPlan().putFieldWriteBarrier(VM_Magic.objectAsAddress(ref), offset, VM_Magic.objectAsAddress(value));
  }
  
  public static void resolvedPutStaticWriteBarrier(int offset, Object value) { 
    getPlan().putStaticWriteBarrier(offset, VM_Magic.objectAsAddress(value));
  }

  public static void arrayCopyWriteBarrier(Object ref, int start, int end) {
    VM._assert(false); // need to implement this
  }


  /**
   * Returns true if GC is in progress.
   *
   * @return True if GC is in progress.
   */
  public static final boolean gcInProgress() throws VM_PragmaUninterruptible {
    return Plan.gcInProgress();
  }

  /**
   * Returns the number of collections that have occured.
   *
   * @return The number of collections that have occured.
   */
  public static final int collectionCount() throws VM_PragmaUninterruptible {
    return VM_CollectorThread.collectionCount;
  }
  
  /**
   * Returns the amount of free memory.
   *
   * @return The amount of free memory.
   */
  public static final long freeMemory() throws VM_PragmaInterruptible {
    return Plan.freeMemory();
  }

  /**
   * Returns the amount of total memory.
   *
   * @return The amount of total memory.
   */
  public static final long totalMemory() throws VM_PragmaInterruptible {
    return Plan.totalMemory();
  }

  /**
   * External call to force a garbage collection.
   */
  public static final void gc() throws VM_PragmaInterruptible {
    triggerCollection();
  }

  public static final void triggerCollection() throws VM_PragmaInterruptible {
    VM._assert(false);
    // do some handshake business first
    // Plan.collect()
  }

  /**
   * Sets up the fields of a <code>VM_Processor</code> object to
   * accommodate allocation and garbage collection running on that processor.
   * This may involve creating a remset array or a buffer for GC tracing.
   * 
   * This method is called from the constructor of VM_Processor. For the
   * PRIMORDIAL processor, which is allocated while the bootimage is being
   * built, this method is called a second time, from VM.boot, when the 
   * VM is starting.
   *
   * @param p The <code>VM_Processor</code> object.
   */
  public static final void setupProcessor(VM_Processor proc) throws VM_PragmaInterruptible {
    if (proc.mmPlan == null)
      proc.mmPlan = new Plan();
  }

  public static final boolean NEEDS_WRITE_BARRIER = Plan.needsWriteBarrier;
  public static final boolean MOVES_OBJECTS = Plan.movesObjects;
  public static boolean useMemoryController = false;


  public static void setWorkBufferSize (int size) {
    WorkQueue.WORK_BUFFER_SIZE = 4 * size;
  }

  public static void dumpRef(VM_Address ref) throws VM_PragmaUninterruptible {
    Util.dumpRef(ref);
  }

  public static boolean validRef(VM_Address ref) {
    return Util.validRef(ref);
  }

  public static boolean addrInVM(VM_Address address) throws VM_PragmaUninterruptible {
    return VMResource.addrInVM(address);
  }

  public static boolean refInVM(VM_Address ref) throws VM_PragmaUninterruptible {
    return VMResource.refInVM(ref);
  }

  public static int getMaxHeaps() {
    return VMResource.getMaxVMResource();
  }

  public static Object allocateScalar(int size, Object [] tib) {
    AllocAdvice advice = getPlan().getAllocAdvice(null, size, null, null);
    VM_Address region = getPlan().alloc(0, size, true, advice);
    Object result = VM_ObjectModel.initializeScalar(region, tib, size);
    getPlan().postAlloc(0, size, result);
    return result;
  }

  public static Object allocateArray(int numElements, int size, Object [] tib) {
    size = (size + 3) & (~3);
    AllocAdvice advice = getPlan().getAllocAdvice(null, size, null, null);
    VM_Address region = getPlan().alloc(0, size, false, advice);
    Object result = VM_ObjectModel.initializeArray(region, tib, numElements, size);
    getPlan().postAlloc(0, size, result);
    return result;
  }

  public static VM_Address allocateCopy(VM_Address object) {
    VM.sysWriteln("allocateCopy unimplmented");
    VM._assert(false); // unimplemented
    return VM_Address.zero();     // getPlan().allocCopy()...  FIXME
  }

  public static void addFinalizer(Object obj) {
    VM_Finalizer.addCandidate(obj);
  }

  public static VM_Address processPtrValue (VM_Address obj) throws VM_PragmaUninterruptible {
    return getPlan().traceObject(obj);
  }

  public static void processPtrField (VM_Address location) throws VM_PragmaUninterruptible {
    getPlan().traceObjectLocation(location);
  }

  public static boolean isLive(VM_Address obj) {
    return Plan.isLive(obj);
  }

  public static void collect() {
    getPlan().collect();
  }


  public static boolean mmap(VM_Address start, int size) {
    VM_Address result = VM_Memory.mmap(start, size,
				       VM_Memory.PROT_READ | VM_Memory.PROT_WRITE | VM_Memory.PROT_EXEC, 
				       VM_Memory.MAP_PRIVATE | VM_Memory.MAP_FIXED | VM_Memory.MAP_ANONYMOUS);
    return (result.EQ(start));
  }

  public static VM_Address refToAddress(VM_Address obj) {
    return VM_ObjectModel.getPointerInMemoryRegion(obj);
  }





  /**
   * Allocate an array of instructions
   * @param n The number of instructions to allocate
   * @return The instruction array
   */ 
  public static INSTRUCTION[] newInstructions(int n) throws VM_PragmaInline {

    if (VM.BuildForRealtimeGC) {
      //-#if RVM_WITH_REALTIME_GC
      return VM_SegmentedArray.newInstructions(n);
      //-#endif
    }

    return new INSTRUCTION[n];
  }


  /**
   * Allocate a stack array
   * @param n The number of stack slots to allocate
   * @return The stack array
   */ 
  public static int[] newStack(int n) throws VM_PragmaInline {

    if (VM.BuildForRealtimeGC) {
      //-#if RVM_WITH_REALTIME_GC
      return VM_SegmentedArray.newStack(n);
      //-#endif
    }

    return new int[n];
  }


  /**
   * Allocate an immortal short array that will live forever and does not move
   * @param n The number of elements
   * @return The short array
   */ 
  public static short[] newImmortalShortArray (int n) {

    if (VM.runningVM) {
      VM_Array shortArrayType = VM_Array.arrayOfShortType;
      Object [] shortArrayTib = shortArrayType.getTypeInformationBlock();
      int offset = VM_JavaHeader.computeArrayHeaderSize(shortArrayType);
      int arraySize = shortArrayType.getInstanceSize(n);
      AllocAdvice advice = getPlan().getAllocAdvice(null, arraySize, null, null);
      VM_Address region = getPlan().alloc(Plan.IMMORTAL_ALLOCATOR, arraySize, false, advice);
      Object result = VM_ObjectModel.initializeArray(region, shortArrayTib, n, arraySize);
      getPlan().postAlloc(Plan.IMMORTAL_ALLOCATOR, arraySize, result);
      return (short []) result;
    }

    return new short[n];
  }


  /**
   * Allocate an aligned stack array that will live forever and does not move
   * @param n The number of stack slots to allocate
   * @return The stack array
   */ 
  public static int[] newImmortalStack (int n) {

    if (VM.runningVM) {
      int logAlignment = 12;
      int alignment = 1 << logAlignment; // 4096
      VM_Array stackType = VM_Array.arrayOfIntType;
      Object [] stackTib = stackType.getTypeInformationBlock();
      int offset = VM_JavaHeader.computeArrayHeaderSize(stackType);
      int arraySize = stackType.getInstanceSize(n);
      int fullSize = arraySize + alignment;  // somewhat wasteful
      if (VM.VerifyAssertions) VM._assert(alignment > offset);
      AllocAdvice advice = getPlan().getAllocAdvice(null, fullSize, null, null);
      VM_Address fullRegion = getPlan().alloc(Plan.IMMORTAL_ALLOCATOR, fullSize, false, advice);
      VM_Address tmp = fullRegion.add(alignment);
      int mask = ~((1 << logAlignment) - 1);
      VM_Address region = VM_Address.fromInt(tmp.toInt() & mask).sub(offset);
      Object result = VM_ObjectModel.initializeArray(region, stackTib, n, arraySize);
      getPlan().postAlloc(0, arraySize, result);
      return (int []) result;
    }

    return new int[n];
  }

  /**
   * Allocate a contiguous int array
   * @param n The number of ints
   * @return The contiguous int array
   */ 
  public static int[] newContiguousIntArray(int n) throws VM_PragmaInline {

    if (VM.BuildForRealtimeGC) {
      //-#if RVM_WITH_REALTIME_GC
      return VM_SegmentedArray.newIntArray(n);
      //-#endif
    }

    return new int[n];
  }

  /**
   * Allocate a contiguous VM_CompiledMethod array
   * @param n The number of objects
   * @return The contiguous object array
   */ 
  public static VM_CompiledMethod[] newContiguousCompiledMethodArray(int n) throws VM_PragmaInline {

      if (VM.BuildForRealtimeGC) {
        //-#if RVM_WITH_REALTIME_GC
        return VM_SegmentedArray.newContiguousCompiledMethodArray(n);
        //-#endif
      }

      return new VM_CompiledMethod[n];
    }

  /**
   * Allocate a contiguous VM_DynamicLibrary array
   * @param n The number of objects
   * @return The contiguous object array
   */ 
  public static VM_DynamicLibrary[] newContiguousDynamicLibraryArray(int n) throws VM_PragmaInline {

    if (VM.BuildForRealtimeGC) {
      //-#if RVM_WITH_REALTIME_GC
      return VM_SegmentedArray.newContiguousDynamicLibraryArray(n);
      //-#endif
    }

    return new VM_DynamicLibrary[n];
  }


  public static Object[] newTIB (int n) throws VM_PragmaInline {

    if (true) {
      //-#if RVM_WITH_COPYING_GC
      //-#if RVM_WITH_ONE_WORD_MASK_OBJECT_MODEL
      return VM_Allocator.newTIB(n);
      //-#endif
      //-#endif
    }

    return new Object[n];
  }

  //-#if RVM_WITH_AIX
  final static int BOOT_START = 0x30000000;
  final static int BOOT_SIZE  = 0x05000000;   // just an upper bound
  //-#endif


  public static boolean isScalar(VM_Address obj) {
    VM_Type type = VM_Magic.objectAsType(VM_ObjectModel.getTIB(obj)[TIB_TYPE_INDEX]);
    return type.isClassType();
  }

  public static int getSizeWhenCopied(VM_Address obj) {
    VM_Type type = VM_Magic.objectAsType(VM_ObjectModel.getTIB(obj)[TIB_TYPE_INDEX]);
    if (type.isClassType())
      return VM_ObjectModel.bytesRequiredWhenCopied(obj, type.asClass());
    else
      return VM_ObjectModel.bytesRequiredWhenCopied(obj, type.asArray(), VM_Magic.getArrayLength(obj));
  }

  // Copy an object using a plan's allocCopy to get space and install the forwarding pointer.
  // On entry, "obj" must have been reserved for copying by the caller.
  //
  public static VM_Address copy(VM_Address fromObj, int forwardingPtr) {

    Object[] tib = VM_ObjectModel.getTIB(fromObj);
    VM_Type type = VM_Magic.objectAsType(tib[TIB_TYPE_INDEX]);

    if (NEEDS_WRITE_BARRIER)
      forwardingPtr |= VM_AllocatorHeader.GC_BARRIER_BIT_MASK;

    if (type.isClassType()) {
      VM_Class classType = type.asClass();
      int numBytes = VM_ObjectModel.bytesRequiredWhenCopied(fromObj, classType);
      VM_Address region = getPlan().allocCopy(fromObj, numBytes, true);
      Object toObj = VM_ObjectModel.moveObject(region, fromObj, numBytes, classType, forwardingPtr);
      VM_Address toRef = VM_Magic.objectAsAddress(toObj);
      return toRef;
    } else {
      VM_Array arrayType = type.asArray();
      int numElements = VM_Magic.getArrayLength(fromObj);
      int numBytes = VM_ObjectModel.bytesRequiredWhenCopied(fromObj, arrayType, numElements);
      VM_Address region = getPlan().allocCopy(fromObj, numBytes, false);
      Object toObj = VM_ObjectModel.moveObject(region, fromObj, numBytes, arrayType, forwardingPtr);
      VM_Address toRef = VM_Magic.objectAsAddress(toObj);
      if (arrayType == VM_Type.CodeType) {
	// sync all moved code arrays to get icache and dcache in sync immediately.
	int dataSize = numBytes - VM_ObjectModel.computeHeaderSize(VM_Magic.getObjectType(toObj));
	VM_Memory.sync(toRef, dataSize);
      }
      return toRef;
    }
  }



}
