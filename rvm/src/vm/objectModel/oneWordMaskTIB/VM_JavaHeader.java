/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

//-#if RVM_WITH_OPT_COMPILER
import instructionFormats.*;
//-#endif

/**
 * Defines the JavaHeader portion of the object header for the 
 * JikesRVM object model. <p>
 * This object model uses a one-word header for most scalar objects, and
 * a two-word header for scalar objects of classes with synchronized
 * methods<p>
 *
 * The one word holds a TIB pointer, and bottom N (N<=2) bits
 * can hold GC state.<p>
 *
 * The second word, if present, holds the thin lock.<p>
 * 
 * <p> Currently, in this object model, the hash code is the object's
 * address.  So this object model is only to be used for the markSweep
 * allocator.
 * 
 * <p> Locking occurs through a lock nursery.
 * 
 * @author David Bacon
 * @author Steve Fink
 * @author Dave Grove
 */
public final class VM_JavaHeader extends VM_NurseryObjectModel 
                                            implements VM_Uninterruptible, 
					    //-#if RVM_WITH_OPT_COMPILER
					    OPT_Operators,
					    //-#endif
					    VM_ObjectModelConstants {

  private static final int OTHER_HEADER_BYTES = 
    VM_MiscHeader.NUM_BYTES_HEADER + VM_AllocatorHeader.NUM_BYTES_HEADER;

  private static final int ONE_WORD_HEADER_SIZE = 4;
  private static final int THIN_LOCK_SIZE = 4;
  private static final int ARRAY_HEADER_SIZE = ONE_WORD_HEADER_SIZE + 4;

  /**
   * The number of low-order-bits of TIB that must be zero.
   */
  private static final int LOG_TIB_ALIGNMENT = 2;
  /** 
   * How many bits in the header are available for the GC and MISC headers? 
   * */
  public static final int NUM_AVAILABLE_BITS = LOG_TIB_ALIGNMENT;

  /**
   * The mask that defines the TIB value in the one-word header.
   */
  private static final int TIB_MASK = 0xffffffff << LOG_TIB_ALIGNMENT;

  /**
   * The mask that defines the available bits the one-word header.
   */
  private static final int BITS_MASK = ~TIB_MASK;

  private static final int TIB_OFFSET     = -8 - OTHER_HEADER_BYTES;

  private static final int STATUS_OFFSET  = -4 - OTHER_HEADER_BYTES;

  /** How many bits are allocated to a thin lock? */
  public static final int NUM_THIN_LOCK_BITS = 32;
  /** How many bits to shift to get the thin lock? */
  public static final int THIN_LOCK_SHIFT    = 0;


  /**
   * How small is the minimum object header size? 
   * Used to pick chunk sizes for mark-sweep based collectors.
   */
  public static final int MINIMUM_HEADER_SIZE = OTHER_HEADER_BYTES + 4;

  static {
    if (VM.VerifyAssertions) {
      VM.assert(VM_MiscHeader.REQUESTED_BITS + VM_AllocatorHeader.REQUESTED_BITS <= NUM_AVAILABLE_BITS);
      if (VM_Collector.MOVES_OBJECTS) VM.assert(NOT_REACHED);
    }
  }

  // SJF: TODO: factor the following four methods into a common class?
  // At least: move to VM_AllocatorHeader?

  /**
   * Convert the raw storage address ptr into a ptr to an object
   * under the assumption that the object to be placed here is 
   * a scalar object of size bytes which is an instance of the given tib.
   * 
   * @param ptr the low memory word of the raw storage to be converted.
   * @param tib the TIB of the type that the storage will/does belong to.
   * @param size the size in bytes of the object
   * @return an ptr to said object.
   */
  public static ADDRESS baseAddressToScalarAddress(ADDRESS ptr, Object[] tib, int size) {
    return ptr + (size - OBJECT_HEADER_END);
  }

  /**
   * Convert the raw storage address ptr into a ptr to an object
   * under the assumption that the object to be placed here is 
   * a array object of size bytes which is an instance of the given tib.
   * 
   * @param ptr the low memory word of the raw storage to be converted.
   * @param tib the TIB of the type that the storage will/does belong to.
   * @param size the size in bytes of the object
   * @return an object reference to said storage.
   */
  public static ADDRESS baseAddressToArrayAddress(ADDRESS ptr, Object[] tib, int size) {
    return ptr + ARRAY_HEADER_SIZE;
  }

  /**
   * Convert a scalar object reference into the low memory word of the raw
   * storage that holds the object.
   * 
   * @param ref a scalar object reference
   * @param t  the VM_Type of the object
   */
  public static ADDRESS scalarRefToBaseAddress(Object ref, VM_Class t) {
    int size = t.getInstanceSize();
    return VM_Magic.objectAsAddress(ref) - (size - OBJECT_HEADER_END);
  }

  /**
   * Convert an array reference into the low memory word of the raw
   * storage that holds the object.
   * 
   * @param ref an array reference
   * @param t  the VM_Type of the array
   */
  public static ADDRESS arrayRefToBaseAddress(Object ref, VM_Type t) {
    return VM_Magic.objectAsAddress(ref) - ARRAY_HEADER_SIZE;
  }


  /**
   * Get the TIB for an object.
   */
  public static Object[] getTIB(Object o) { 
    VM_Magic.pragmaInline();
    int tibWord = VM_Magic.getIntAtOffset(o,TIB_OFFSET) & TIB_MASK;
    return VM_Magic.addressAsObjectArray(tibWord);
  }
  
  /**
   * Set the TIB for an object.
   */
  public static void setTIB(Object ref, Object[] tib) {
    VM_Magic.pragmaInline();
    int tibPtr = VM_Magic.objectAsAddress(tib);
    if (VM.VerifyAssertions) {
      VM.assert((tibPtr & BITS_MASK) == 0);
    }
    int tibWord = (VM_Magic.getIntAtOffset(ref, TIB_OFFSET) & BITS_MASK) | tibPtr;
    VM_Magic.setIntAtOffset(ref, TIB_OFFSET, tibWord);
  }

  /**
   * Set the TIB for an object.
   * Note: Beware; this function clears the additional bits.
   */
  public static void setTIB(BootImageInterface bootImage, int refOffset, int tibAddr, VM_Type type) {
    bootImage.setAddressWord(refOffset + TIB_OFFSET, tibAddr);
  }

  /**
   * Process the TIB field during copyingGC.  NOT IMPLEMENTED, since
   * copyingGC not currently supported.
   */
  public static void gcProcessTIB(int ref) {
    VM.assert(NOT_REACHED);
  }

  /**
   * Get a reference to the TIB for an object.
   *
   * @param jdpService
   * @param address address of the object
   */
  public static ADDRESS getTIB(JDPServiceInterface jdpService, ADDRESS ptr) {
    return jdpService.readMemory(ptr + TIB_OFFSET) & TIB_MASK;
  }

  /**
   * Get the hash code of an object.
   */
  public static int getObjectHashCode(Object o) { 
    VM_Magic.pragmaInline();
    if (VM_Collector.MOVES_OBJECTS) {
      VM.assert(NOT_REACHED);
      return -1;
    } else {
      return VM_Magic.objectAsAddress(o) >> 2;
    }
  }
  
  /** Install a new hashcode (only used if Collector.MOVES_OBJECTS) */
  private static int installHashCode(Object o) {
    VM_Magic.pragmaNoInline();
    if (VM_Collector.MOVES_OBJECTS) {
      VM.assert(NOT_REACHED);
      return -1;
    } else {
      return VM_Magic.objectAsAddress(o) >> 2;
    }
  }

  /**
   * Non-atomic read of the word containing o's thin lock.
   */
  public static int getThinLock(Object o) {
    VM_Magic.pragmaInline();
    if (VM.VerifyAssertions) {
      VM.assert(hasThinLock(o));
    }
    return VM_Magic.getIntAtOffset(o, STATUS_OFFSET);
  }

  /**
   * Prepare of the word containing o's thin lock
   */
  public static int prepareThinLock(Object o) {
    VM_Magic.pragmaInline();
    if (VM.VerifyAssertions) {
      VM.assert(hasThinLock(o));
    }
    return VM_Magic.prepare(o, STATUS_OFFSET);
  }

  /**
   * Attempt of the word containing o's thin lock
   */
  public static boolean attemptThinLock(Object o, int oldValue, int newValue) {
    VM_Magic.pragmaInline();
    if (VM.VerifyAssertions) {
      VM.assert(hasThinLock(o));
    }
    return VM_Magic.attempt(o, STATUS_OFFSET, oldValue, newValue);
  }
  /**
   * Non-atomic read of word containing available bits
   */
  public static int readAvailableBitsWord(Object o) {
    return VM_Magic.getIntAtOffset(o, TIB_OFFSET);
  }

  /**
   * Non-atomic read of byte containing available bits
   */
  public static byte readAvailableBitsByte(Object o) {
    return VM_Magic.getByteAtOffset(o, TIB_OFFSET);
  }

  /**
   * Non-atomic write of word containing available bits
   */
  public static void writeAvailableBitsWord(Object o, int val) {
    VM_Magic.setIntAtOffset(o, TIB_OFFSET, val);
  }

  /**
   * Non-atomic write of byte containing available bits
   */
  public static void writeAvailableBitsByte(Object o, byte val) {
    VM_Magic.setByteAtOffset(o, TIB_OFFSET, val);
  }

  /**
   * Return true if argument bit is 1, false if it is 0
   */
  public static boolean testAvailableBit(Object o, int idx) {
    return ((1 << idx) & VM_Magic.getIntAtOffset(o, TIB_OFFSET)) != 0;
  }

  /**
   * Set argument bit to 1 if value is true, 0 if value is false
   */
  public static void setAvailableBit(Object o, int idx, boolean flag) {
    int tibWord = VM_Magic.getIntAtOffset(o, TIB_OFFSET);
    if (flag) {
      VM_Magic.setIntAtOffset(o, TIB_OFFSET, tibWord| (1 << idx));
    } else {
      VM_Magic.setIntAtOffset(o, TIB_OFFSET, tibWord & ~(1 << idx));
    }
  }

  /**
   * Freeze the other bits in the byte containing the available bits
   * so that it is safe to update them using setAvailableBits.
   *
   * Should be a no-op, since the TIB is frozen.
   */
  public static void initializeAvailableByte(Object o) {
  }

  /**
   * A prepare on the word containing the available bits
   */
  public static int prepareAvailableBits(Object o) {
    return VM_Magic.prepare(o, TIB_OFFSET);
  }
  
  /**
   * An attempt on the word containing the available bits
   */
  public static boolean attemptAvailableBits(Object o, int oldVal, int newVal) {
    return VM_Magic.attempt(o, TIB_OFFSET, oldVal, newVal);
  }
  
  /**
   * Given the smallest base address in a region, return the smallest
   * object reference that could refer to an object in the region.
   */
  public static int minimumObjectRef (int regionBaseAddr) {
    return regionBaseAddr + ARRAY_HEADER_SIZE;
  }

  /**
   * Given the largest base address in a region, return the largest
   * object reference that could refer to an object in the region.
   */
  public static int maximumObjectRef (int regionHighAddr) {
    return regionHighAddr - OBJECT_HEADER_END;
  }

  /**
   * Compute the header size of an instance of the given type.
   */
  public static int computeScalarHeaderSize(VM_Class type) {
    VM_Magic.pragmaInline();
    return ONE_WORD_HEADER_SIZE + (type.isSynchronized ? THIN_LOCK_SIZE : 0);
  }

  /**
   * Compute the header size of an instance of the given type.
   */
  public static int computeArrayHeaderSize(VM_Array type) {
    return ARRAY_HEADER_SIZE;
  }

  /**
   * Perform any required initialization of the JAVA portion of the header.
   * @param ref the object ref to the storage to be initialized
   * @param tib the TIB of the instance being created
   * @param size the number of bytes allocated by the GC system for this object.
   * @param isScalar are we initializing a scalar (true) or array (false) object?
   */
  public static void initializeHeader(Object ref, Object[] tib, int size, boolean isScalar) {
    // nothing to do (TIB set by VM_ObjectModel)
  }

  /**
   * Perform any required initialization of the JAVA portion of the header.
   * @param bootImage the bootimage being written
   * @param ref the object ref to the storage to be initialized
   * @param tib the TIB of the instance being created
   * @param size the number of bytes allocated by the GC system for this object.
   * @param isScalar are we initializing a scalar (true) or array (false) object?
   */
  public static void initializeHeader(BootImageInterface bootImage, int ref, 
				      Object[] tib, int size, boolean isScalar) {
    // (TIB set by BootImageWriter2)

    // If Collector.MOVES_OBJECTS then we must preallocate hash code
    // and set the barrier bit to avoid problems with non-atomic access
    // to available bits byte corrupting hash code by write barrier sequence.
    if (VM_Collector.MOVES_OBJECTS && VM_Collector.NEEDS_WRITE_BARRIER) {
      VM.assert(NOT_REACHED);
    }
  }


  /**
   * Initialize a cloned scalar object from the clone src
   */
  public static void initializeScalarClone(Object cloneDst, Object cloneSrc, int size) {
    int cnt = size - VM_ObjectModel.computeHeaderSize(cloneSrc);
    int dst = VM_Magic.objectAsAddress(cloneDst) + OBJECT_HEADER_END - size;
    int src = VM_Magic.objectAsAddress(cloneSrc) + OBJECT_HEADER_END - size;
    VM_Memory.aligned32Copy(dst, src, cnt); 
  }

  /**
   * Initialize a cloned array object from the clone src
   */
  public static void initializeArrayClone(Object cloneDst, Object cloneSrc, int size) {
    int cnt = size - ARRAY_HEADER_SIZE;
    int dst = VM_Magic.objectAsAddress(cloneDst);
    int src = VM_Magic.objectAsAddress(cloneSrc);
    VM_Memory.aligned32Copy(dst, src, cnt);
  }
  
  /**
   * For low level debugging of GC subsystem. 
   * Dump the header word(s) of the given object reference.
   * @param ref the object reference whose header should be dumped 
   */
  public static void dumpHeader(Object ref) {
    // TIB dumped in VM_ObjectModel
    if (hasThinLock(ref)) {
      VM.sysWrite(" THIN LOCK=");
      VM.sysWriteHex(VM_Magic.getIntAtOffset(ref, STATUS_OFFSET));
    }
  }


  /**
   * The following method will emit code that moves a reference to an
   * object's TIB into a destination register.
   *
   * @param asm the assembler object to emit code with
   * @param dest the number of the destination register
   * @param object the number of the register holding the object reference
   */
  //-#if RVM_FOR_POWERPC
  public static void baselineEmitLoadTIB(VM_Assembler asm, int dest, 
                                         int object) {
    int ME = 31 - LOG_TIB_ALIGNMENT;
    asm.emitL(dest, TIB_OFFSET, object);
    // The following clears the low-order bits. See p.119 of PowerPC book
    asm.emitRLWINM(dest, dest, 0, 0, ME);
  }
  //-#elif RVM_FOR_IA32
  public static void baselineEmitLoadTIB(VM_Assembler asm, byte dest, 
                                         byte object) {
    // TODO
    VM.assert(NOT_REACHED);
//    asm.emitMOV_Reg_RegDisp(dest, object, TIB_OFFSET);
  }
  /**
   * The following method will emit code that pushes a reference to an
   * object's TIB onto the stack.
   *
   * TODO: consider deprecating this method; rewriting the appropriate
   * sequences in the baseline compiler to use a scratch register.
   *
   * @param asm the assembler object to emit code with
   * @param object the number of the register holding the object reference
   */
  public static void baselineEmitPushTIB(VM_Assembler asm, byte object) {
    VM.assert(NOT_REACHED);
    // asm.emitPUSH_RegDisp(object, TIB_OFFSET);
  }
  //-#endif

  //-#if RVM_WITH_OPT_COMPILER
  /**
   * Mutate a GET_OBJ_TIB instruction to the LIR
   * instructions required to implement it.
   * 
   * @param s the GET_OBJ_TIB instruction to lower
   * @param ir the enclosing OPT_IR
   */
  public static void lowerGET_OBJ_TIB(OPT_Instruction s, OPT_IR ir) {
    OPT_Operand address = GuardedUnary.getClearVal(s);
    OPT_RegisterOperand result = GuardedUnary.getClearResult(s);
    s.insertBefore(Load.create(INT_LOAD, result.copyRO(),
		address, new OPT_IntConstantOperand(TIB_OFFSET), 
		null, GuardedUnary.getClearGuard(s)));
    Binary.mutate(s, INT_AND, result.copyRO(), result.copyRO(), 
                  new OPT_IntConstantOperand(TIB_MASK));
  }

  /**
   * Mutate a IG_CLASS_TEST instruction to the LIR
   * instructions required to implement it.
   *
   * SJF: TODO: factor this out?  As written, is this object-model
   * independent?
   *
   * @param s the IG_CLASS_TEST instruction to lower
   * @param ir the enclosing OPT_IR
   */
  public static void lowerIG_CLASS_TEST(OPT_Instruction s, OPT_IR ir) {
    IfCmp.mutate(s, INT_IFCMP, null, 
		 OPT_ConvertToLowLevelIR.getTIB(s, ir, 
						InlineGuard.getClearValue(s), 
						InlineGuard.getClearGuard(s)), 
		 OPT_ConvertToLowLevelIR.getTIB(s, ir, InlineGuard.getGoal(s).asType()), 
		 OPT_ConditionOperand.NOT_EQUAL(), 
		 InlineGuard.getClearTarget(s),
		 InlineGuard.getClearBranchProfile(s));
  }
  //-#endif
}
