/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

//-#if RVM_WITH_OPT_COMPILER
import instructionFormats.*;
//-#endif

/**
 * Defines the JavaHeader portion of the object header for the 
 * default JikesRVM object model.
 * The default object model uses a two word header. <p>
 * 
 * One word holds a TIB pointer. <p>
 * 
 * The other word ("status word") contains an inline thin lock,
 * the object hash code (for copying collectors), and a few 
 * unallocated bits that can be used for other purposes. 
 * The layout of the status word in copying collectors is:
 * <pre>
 *      TTTT TTTT TTTT TTTT TTTT HHHH HHHH HHAA
 * T = thin lock bits
 * H = hash code 
 * A = available for use by GCHeader and/or MiscHeader.
 * </pre>
 * 
 * The layout of the status word in noncopying collectors  is:
 * <pre>
 *      TTTT TTTT TTTT TTTT TTTT TTTT AAAA AAAA
 * T = thin lock bits
 * A = available for use by GCHeader and/or MiscHeader.
 * </pre>
 * 
 * @author Bowen Alpern
 * @author David Bacon
 * @author Steve Fink
 * @author Dave Grove
 * @author Derek Lieber
 */
public final class VM_JavaHeader implements VM_Uninterruptible, 
					    //-#if RVM_WITH_OPT_COMPILER
					    OPT_Operators,
					    //-#endif
					    VM_ObjectModelConstants {

  private static final int OTHER_HEADER_BYTES = 
    VM_MiscHeader.NUM_BYTES_HEADER + VM_AllocatorHeader.NUM_BYTES_HEADER;
  private static final int SCALAR_HEADER_SIZE = OTHER_HEADER_BYTES + 8;
  private static final int ARRAY_HEADER_SIZE = SCALAR_HEADER_SIZE + 4;

  // note that the pointer to a scalar actually points 4 bytes above the
  // scalar object.
  private static final int SCALAR_PADDING_BYTES = 4;

  private static final int STATUS_OFFSET  = -8  - OTHER_HEADER_BYTES;
  private static final int TIB_OFFSET     = -12 - OTHER_HEADER_BYTES;

  private static final int AVAILABLE_BITS_OFFSET = VM.LITTLE_ENDIAN ? (STATUS_OFFSET) : (STATUS_OFFSET + 3);
  
  private static final int HASH_CODE_MASK  = 0x00000ffc;
  private static final int HASH_CODE_SHIFT = 2;
  private static int hashCodeGenerator; // seed for generating hash codes with copying collectors.
  
  /** How many bits are allocated to a thin lock? */
  public static final int NUM_THIN_LOCK_BITS = VM_Collector.MOVES_OBJECTS ? 20 : 24;
  /** How many bits to shift to get the thin lock? */
  public static final int THIN_LOCK_SHIFT    = VM_Collector.MOVES_OBJECTS ? 12 : 8;

  /** How many bits in the header are available for the GC and MISC headers? */
  public static final int NUM_AVAILABLE_BITS = VM_Collector.MOVES_OBJECTS ? 2 : 8;

  /**
   * How small is the minimum object header size? 
   * Used to pick chunk sizes for mark-sweep based collectors.
   */
  public static final int MINIMUM_HEADER_SIZE = SCALAR_HEADER_SIZE;

  static {
    if (VM.VerifyAssertions) {
      VM.assert(VM_MiscHeader.REQUESTED_BITS + VM_AllocatorHeader.REQUESTED_BITS <= NUM_AVAILABLE_BITS);
    }
  }

  /**
   * Given a reference to an object of a given class, what is the offset in 
   * bytes to the bottom word of
   * the header?
   */
  public static int getHeaderEndOffset(VM_Class klass) {
    return TIB_OFFSET;
  }

  /**
   * Given a reference, what is the offset in bytes to the bottom word of
   * the MISC header?
   */
  public static int getMiscHeaderEndOffset() {
    return TIB_OFFSET + 8 + VM_AllocatorHeader.NUM_BYTES_HEADER;
  }

  /**
   * Given a reference, return an address which is guaranteed to be inside
   * the memory region allocated to the object.
   *
   * TODO: try to deprecate this?  Seems ugly.
   */
  public static ADDRESS getPointerInMemoryRegion(ADDRESS ref) {
    return ref - 8;
  }

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
    return ptr + size + SCALAR_PADDING_BYTES;
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
    return VM_Magic.objectAsAddress(ref) - size - SCALAR_PADDING_BYTES;
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
    return VM_Magic.getObjectArrayAtOffset(o, TIB_OFFSET);
  }
  
  /**
   * Set the TIB for an object.
   */
  public static void setTIB(Object ref, Object[] tib) {
    VM_Magic.setObjectAtOffset(ref, TIB_OFFSET, tib);
  }

  /**
   * Set the TIB for an object.
   */
  public static void setTIB(BootImageInterface bootImage, int refOffset, int tibAddr, VM_Type type) {
    bootImage.setAddressWord(refOffset + TIB_OFFSET, tibAddr);
  }

  /**
   * Process the TIB field during copyingGC
   */
  public static void gcProcessTIB(int ref) {
    VM_Allocator.processPtrField(ref + TIB_OFFSET);
  }

  /**
   * Get a reference to the TIB for an object.
   *
   * @param jdpService
   * @param address address of the object
   */
  public static ADDRESS getTIB(JDPServiceInterface jdpService, ADDRESS ptr) {
    return jdpService.readMemory(ptr + TIB_OFFSET);
  }

  /**
   * Get the hash code of an object.
   */
  public static int getObjectHashCode(Object o) { 
    VM_Magic.pragmaInline();
    if (VM_Collector.MOVES_OBJECTS) {
      int hashCode = (VM_Magic.getIntAtOffset(o, STATUS_OFFSET) & HASH_CODE_MASK) >> HASH_CODE_SHIFT;
      if (hashCode != 0) 
	return hashCode; 
      return installHashCode(o);
    } else {
      return VM_Magic.objectAsAddress(o) >> 2;
    }
  }
  
  /** Install a new hashcode (only used if Collector.MOVES_OBJECTS) */
  private static int installHashCode(Object o) {
    VM_Magic.pragmaNoInline();
    if (VM_Collector.MOVES_OBJECTS) {
      int hashCode;
      do {
	hashCodeGenerator += (1 << HASH_CODE_SHIFT);
	hashCode = hashCodeGenerator & HASH_CODE_MASK;
      } while (hashCode == 0);
      while (true) {
	int statusWord = VM_Magic.prepare(o, STATUS_OFFSET);
	if ((statusWord & HASH_CODE_MASK) != 0) // some other thread installed a hashcode
	  return (statusWord & HASH_CODE_MASK) >> HASH_CODE_SHIFT;
	if (VM_Magic.attempt(o, STATUS_OFFSET, statusWord, statusWord | hashCode))
	  return hashCode >> HASH_CODE_SHIFT;  // we installed the hash code
      }
    } else {
      return VM_Magic.objectAsAddress(o) >> 2;
    }
  }

  /**
   * Does an object have a thin lock?
   */
  public static boolean hasThinLock(Object o) { 
    return true;
  }

  /**
   * Non-atomic read of the word containing o's thin lock
   */
  public static int getThinLock(Object o) {
    return VM_Magic.getIntAtOffset(o, STATUS_OFFSET);
  }

  /**
   * Prepare of the word containing o's thin lock
   */
  public static int prepareThinLock(Object o) {
    return VM_Magic.prepare(o, STATUS_OFFSET);
  }

  /**
   * Attempt of the word containing o's thin lock
   */
  public static boolean attemptThinLock(Object o, int oldValue, int newValue) {
    return VM_Magic.attempt(o, STATUS_OFFSET, oldValue, newValue);
  }

  /**
   * fastPathLocking
   */
  public static void fastPathLock(Object o) { 
    VM_ThinLock.inlineLock(o);
  }

  /**
   * fastPathUnlocking
   */
  public static void fastPathUnlock(Object o) { 
    VM_ThinLock.inlineUnlock(o);
  }

  /**
   * Generic lock
   */
  public static void genericLock(Object o) { 
    VM_ThinLock.lock(o);
  }

  /**
   * Generic unlock
   */
  public static void genericUnlock(Object o) {
    VM_ThinLock.unlock(o);
  }

  /**
   * Obtains the heavy-weight lock, if there is one, associated with the
   * indicated object.  Returns <code>null</code>, if there is no
   * heavy-weight lock associated with the object.
   *
   * @param o the object from which a lock is desired
   * @param create if true, create heavy lock if none found
   * @return the heavy-weight lock on the object (if any)
   */
  public static VM_Lock getHeavyLock(Object o, boolean create) {
    return VM_ThinLock.getHeavyLock(o, create);
  }

  /**
   * Non-atomic read of word containing available bits
   */
  public static int readAvailableBitsWord(Object o) {
    return VM_Magic.getIntAtOffset(o, STATUS_OFFSET);
  }

  /**
   * Non-atomic read of byte containing available bits
   */
  public static byte readAvailableBitsByte(Object o) {
    return VM_Magic.getByteAtOffset(o, AVAILABLE_BITS_OFFSET);
  }

  /**
   * Non-atomic write of word containing available bits
   */
  public static void writeAvailableBitsWord(Object o, int val) {
    VM_Magic.setIntAtOffset(o, STATUS_OFFSET, val);
  }

  /**
   * Non-atomic write of byte containing available bits
   */
  public static void writeAvailableBitsByte(Object o, byte val) {
    VM_Magic.setByteAtOffset(o, AVAILABLE_BITS_OFFSET, val);
  }

  /**
   * Return true if argument bit is 1, false if it is 0
   */
  public static boolean testAvailableBit(Object o, int idx) {
    return ((1 << idx) & VM_Magic.getIntAtOffset(o, STATUS_OFFSET)) != 0;
  }

  /**
   * Set argument bit to 1 if value is true, 0 if value is false
   */
  public static void setAvailableBit(Object o, int idx, boolean flag) {
    int status = VM_Magic.getIntAtOffset(o, STATUS_OFFSET);
    if (flag) {
      VM_Magic.setIntAtOffset(o, STATUS_OFFSET, status | (1 << idx));
    } else {
      VM_Magic.setIntAtOffset(o, STATUS_OFFSET, status & ~(1 << idx));
    }
  }

  /**
   * Freeze the other bits in the byte containing the available bits
   * so that it is safe to update them using setAvailableBits.
   */
  public static void initializeAvailableByte(Object o) {
    if (VM_Collector.MOVES_OBJECTS) getObjectHashCode(o);
  }

  /**
   * A prepare on the word containing the available bits
   */
  public static int prepareAvailableBits(Object o) {
    return VM_Magic.prepare(o, STATUS_OFFSET);
  }
  
  /**
   * An attempt on the word containing the available bits
   */
  public static boolean attemptAvailableBits(Object o, int oldVal, int newVal) {
    return VM_Magic.attempt(o, STATUS_OFFSET, oldVal, newVal);
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
    return regionHighAddr + SCALAR_PADDING_BYTES;
  }

  /**
   * Compute the header size of an instance of the given type.
   */
  public static int computeScalarHeaderSize(VM_Class type) {
    return SCALAR_HEADER_SIZE;
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
      int hashCode;
      do {
	hashCodeGenerator += (1 << HASH_CODE_SHIFT);
	hashCode = hashCodeGenerator & HASH_CODE_MASK;
      } while (hashCode == 0);
      bootImage.setFullWord(ref + STATUS_OFFSET, hashCode | VM_AllocatorHeader.GC_BARRIER_BIT_MASK);
    }
  }


  /**
   * Initialize a cloned scalar object from the clone src
   */
  public static void initializeScalarClone(Object cloneDst, Object cloneSrc, int size) {
    int cnt = size - SCALAR_HEADER_SIZE;
    int dst = VM_Magic.objectAsAddress(cloneDst) - (size + SCALAR_PADDING_BYTES);
    int src = VM_Magic.objectAsAddress(cloneSrc) - (size + SCALAR_PADDING_BYTES);
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
    VM.sysWrite(" STATUS=");
    VM.sysWriteHex(VM_Magic.getIntAtOffset(ref, STATUS_OFFSET));
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
    asm.emitL(dest, TIB_OFFSET, object);
  }
  //-#elif RVM_FOR_IA32
  public static void baselineEmitLoadTIB(VM_Assembler asm, byte dest, 
                                         byte object) {
    asm.emitMOV_Reg_RegDisp(dest, object, TIB_OFFSET);
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
    asm.emitPUSH_RegDisp(object, TIB_OFFSET);
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
    // TODO: valid location operand.
    OPT_Operand address = GuardedUnary.getClearVal(s);
    Load.mutate(s, INT_LOAD, GuardedUnary.getClearResult(s), 
                address, new OPT_IntConstantOperand(TIB_OFFSET), 
                null, GuardedUnary.getClearGuard(s));
  }

  /**
   * Mutate a IG_CLASS_TEST instruction to the LIR
   * instructions required to implement it.
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
