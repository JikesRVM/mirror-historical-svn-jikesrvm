// $Id$

/**
 * Defines shared support for one-word headers in the 
 * JikesRVM object model. <p>
 * This object model uses a one-word header for most scalar objects, and
 * a two-word header for scalar objects of classes with synchronized
 * methods.<p>
 *
 * An object of an "unsynchronized" class is layed out as:
 *
 * <pre>
 * low memory                                                                        high memory
 * field n |...| field 1 | field 0 | MISC HEADER | GC HEADER | TIB word |             
 *                                                                                  ^
 *                                                                                  |
 *                                                                           object reference
 * </pre>
 *
 * An object of an "synchronized" class is layed out as
 *
 * <pre>
 * low memory                                                                         high memory
 * field n |...| field 1 | field 0 | MISC HEADER | GC HEADER | TIB word | status word |
 *                                                                                    ^
 *                                                                                    |
 *                                                                             object reference
 * </pre>
 *
 * An array is layed out as
 * <pre>
 * low memory                                                                         high memory
 * MISC HEADER | GC HEADER | TIB word | length | element 0 | element 1 | .... | element n |
 *                                             ^
 *                                             |
 *                                       object reference
 *
 * The TIB word holds some identifier of the TIB, which may vary by object
 * model. <p>
 *
 * The status word, if present, holds the thin lock.<p>
 * 
 * <p> Locking occurs through a lock nursery for unsynchronized classes.
 * 
 * @author David Bacon
 * @author Steve Fink
 * @author Dave Grove
 */
public class VM_NurseryObjectModel implements VM_Uninterruptible, 
					      VM_JavaHeaderConstants,
					      VM_Constants
					    //-#if RVM_WITH_OPT_COMPILER
					    ,OPT_Operators
					    //-#endif
{

  private static final int OTHER_HEADER_BYTES = VM_AllocatorHeader.NUM_BYTES_HEADER + VM_MiscHeader.NUM_BYTES_HEADER;
  private static final int ONE_WORD_HEADER_SIZE = 4 + OTHER_HEADER_BYTES;
  private static final int THIN_LOCK_SIZE = 4;
  private static final int ARRAY_HEADER_SIZE = ONE_WORD_HEADER_SIZE + 4;

  private static final int UNSYNCH_PADDING_BYTES = THIN_LOCK_SIZE;

  protected static final int TIB_OFFSET   = -8;

  private static final int STATUS_OFFSET  = -4;

  private static final int AVAILABLE_BITS_OFFSET = VM.LITTLE_ENDIAN ? (TIB_OFFSET) : (TIB_OFFSET + 3);

  /** How many bits are allocated to a thin lock? */
  public static final int NUM_THIN_LOCK_BITS = 32;
  /** How many bits to shift to get the thin lock? */
  public static final int THIN_LOCK_SHIFT    = 0;

  /**
   * How many bits are used to encode the hash code state?
   */
  protected static final int HASH_STATE_BITS = VM_Collector.MOVES_OBJECTS ? 2 : 0;
  protected static final int HASH_STATE_MASK = HASH_STATE_UNHASHED | HASH_STATE_HASHED | HASH_STATE_HASHED_AND_MOVED;
  protected static final int HASHCODE_UNSYNCH_SCALAR_OFFSET = -4; // instead of status word
  protected static final int HASHCODE_SYNCH_SCALAR_OFFSET = 0;    // above status word
  protected static final int HASHCODE_ARRAY_OFFSET = JAVA_HEADER_END - OTHER_HEADER_BYTES - 4; // to left of header
  
  /**
   * How small is the minimum object header size? 
   * Used to pick chunk sizes for mark-sweep based collectors.
   */
  public static final int MINIMUM_HEADER_SIZE = ONE_WORD_HEADER_SIZE;

  /**
   * Given a reference to an object of a given class, what is the offset in 
   * bytes to the bottom word of
   * the header?
   */
  public static int getHeaderEndOffset(VM_Class klass) {
    return TIB_OFFSET;
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
    return VM_Magic.getByteAtOffset(o, AVAILABLE_BITS_OFFSET);
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
    VM_Magic.setByteAtOffset(o, AVAILABLE_BITS_OFFSET, val);
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
   * Given a reference, return an address which is guaranteed to be inside
   * the memory region allocated to the object.
   */
  public static ADDRESS getPointerInMemoryRegion(ADDRESS ref) {
    return ref - 8;
  }

  /**
   * Convert a scalar object reference into the low memory word of the raw
   * storage that holds the object.
   * 
   * @param ref a scalar object reference
   * @param t  the VM_Type of the object
   */
  protected static ADDRESS scalarRefToBaseAddress(Object ref, VM_Class t) {
    int size = t.getInstanceSize();
    boolean isSynchronized = t.isSynchronized;
    return VM_Magic.objectAsAddress(ref) - size - (isSynchronized ? 0 : THIN_LOCK_SIZE);
  }

  /**
   * Convert an array reference into the low memory word of the raw
   * storage that holds the object.
   * 
   * @param ref an array reference
   * @param t  the VM_Type of the array
   */
  protected static ADDRESS arrayRefToBaseAddress(Object ref, VM_Type t) {
    if (HASH_STATE_BITS != 0) {
      if ((VM_Magic.getIntAtOffset(ref, TIB_OFFSET) & HASH_STATE_MASK) != HASH_STATE_UNHASHED) {
	return VM_Magic.objectAsAddress(ref) - ARRAY_HEADER_SIZE - 4;
      }
    }
    return VM_Magic.objectAsAddress(ref) - ARRAY_HEADER_SIZE;
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
  protected static ADDRESS baseAddressToScalarAddress(ADDRESS ptr, Object[] tib, int size) {
    boolean isSynchronized = ((VM_Class)tib[0]).isSynchronized;
    return ptr + size + (isSynchronized ? 0 : THIN_LOCK_SIZE);
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
  protected static ADDRESS baseAddressToArrayAddress(ADDRESS ptr, Object[] tib, int size) {
    return ptr + ARRAY_HEADER_SIZE;
  }

  /**
   * Perform any required initialization of the JAVA portion of the header.
   * @param ptr the raw storage to be initialized
   * @param tib the TIB of the instance being created
   * @param size the number of bytes allocated by the GC system for this object.
   */
  public static Object initializeScalarHeader(int ptr, Object[] tib, int size) {
    // (TIB set by VM_ObjectModel)
    boolean isSynchronized = ((VM_Class)tib[0]).isSynchronized;
    return VM_Magic.addressAsObject(ptr + size + (isSynchronized ? 0 : THIN_LOCK_SIZE));
  }

  /**
   * Perform any required initialization of the JAVA portion of the header.
   * @param bootImage the bootimage being written
   * @param ref the object ref to the storage to be initialized
   * @param tib the TIB of the instance being created
   * @param size the number of bytes allocated by the GC system for this object.
   */
  public static int initializeScalarHeader(BootImageInterface bootImage, int ptr, 
					   Object[] tib, int size) {
    boolean isSynchronized = ((VM_Class)tib[0]).isSynchronized;
    return ptr + size + (isSynchronized ? 0 : THIN_LOCK_SIZE);
  }

  /**
   * Perform any required initialization of the JAVA portion of the header.
   * @param ptr the raw storage to be initialized
   * @param tib the TIB of the instance being created
   * @param size the number of bytes allocated by the GC system for this object.
   */
  public static Object initializeArrayHeader(int ptr, Object[] tib, int size) {
    // (TIB and array length set by VM_ObjectModel)
    Object ref = VM_Magic.addressAsObject(ptr + ARRAY_HEADER_SIZE);
    return ref;
  }

  /**
   * Perform any required initialization of the JAVA portion of the header.
   * @param bootImage the bootimage being written
   * @param ref the object ref to the storage to be initialized
   * @param tib the TIB of the instance being created
   * @param size the number of bytes allocated by the GC system for this object.
   */
  public static int initializeArrayHeader(BootImageInterface bootImage, int ptr, 
					   Object[] tib, int size) {
    int ref = ptr + ARRAY_HEADER_SIZE;
    // (TIB set by BootImageWriter2; array length set by VM_ObjectModel)
    return ref;
  }

  /**
   * Initialize a cloned scalar object from the clone src
   */
  public static void initializeScalarClone(Object cloneDst, Object cloneSrc, int size) {
    int cnt = size - VM_ObjectModel.computeHeaderSize(cloneSrc);
    VM_Class klass = cloneDst.getClass().getVMType().asClass();
    int dst = scalarRefToBaseAddress(cloneDst,klass);
    int src = scalarRefToBaseAddress(cloneSrc,klass);
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
    return regionHighAddr;
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
   * Get the hash code of an object.
   */
  public static int getObjectHashCode(Object o) { 
    if (VM_Collector.MOVES_OBJECTS) {
      int hashState = VM_Magic.getIntAtOffset(o, TIB_OFFSET) & HASH_STATE_MASK;
      if (hashState == HASH_STATE_HASHED) {
	return VM_Magic.objectAsAddress(o) >> 2;
      } else if (hashState == HASH_STATE_HASHED_AND_MOVED) {
	VM_Type t = VM_Magic.getObjectType(o);
	if (t.isArrayType()) {
	  return VM_Magic.getIntAtOffset(o, HASHCODE_ARRAY_OFFSET);
	} else {
	  if (t.isSynchronized) {
	    return VM_Magic.getIntAtOffset(o, HASHCODE_SYNCH_SCALAR_OFFSET);
	  } else {
	    return VM_Magic.getIntAtOffset(o, HASHCODE_UNSYNCH_SCALAR_OFFSET);
	  }
	}
      } else {
	int tmp;
	do {
	  tmp = VM_Magic.prepare(o, TIB_OFFSET);
	} while (!VM_Magic.attempt(o, TIB_OFFSET, tmp, tmp | HASH_STATE_HASHED));
	return getObjectHashCode(o);
      }
    } else {
      return VM_Magic.objectAsAddress(o) >> 2;
    }
  }

  /**
   * how many bytes are needed when the scalar object is copied by GC?
   */
  public static int bytesRequiredWhenCopied(Object fromObj, VM_Class type) {
    int size = type.getInstanceSize();
    int hashState = VM_Magic.getIntAtOffset(fromObj, TIB_OFFSET) & HASH_STATE_MASK;
    if (hashState == HASH_STATE_UNHASHED) {
      return size;
    } else {
      return size + 4;
    }
  }

  /**
   * how many bytes are needed when the array object is copied by GC?
   */
  public static int bytesRequiredWhenCopied(Object fromObj, VM_Array type, int numElements) {
    int size = (type.getInstanceSize(numElements) + 3) & ~3;
    int hashState = VM_Magic.getIntAtOffset(fromObj, TIB_OFFSET) & HASH_STATE_MASK;
    if (hashState == HASH_STATE_UNHASHED) {
      return size;
    } else {
      return size + 4;
    }
  }
  
  /**
   * Copy an object to the given raw storage address
   */
  public static Object moveObject(ADDRESS toAddress, Object fromObj, int numBytes, 
				  VM_Class type, Object[] tib, int availBitsWord) {
    int tibWord = VM_Magic.getIntAtOffset(fromObj, TIB_OFFSET);
    int hashState = tibWord & HASH_STATE_MASK;
    if (type.isSynchronized) {
      if (hashState == HASH_STATE_UNHASHED) {
	VM.sysWrite("S_S_U\n");
	int fromAddress = VM_Magic.objectAsAddress(fromObj) - numBytes;
	VM_Memory.aligned32Copy(toAddress, fromAddress, numBytes);
	Object toObj = VM_Magic.addressAsObject(toAddress + numBytes);
	VM_Magic.setIntAtOffset(toObj, TIB_OFFSET, availBitsWord);
	VM_GCUtil.validRef(VM_Magic.objectAsAddress(toObj));
	return toObj;
      } else if (hashState == HASH_STATE_HASHED) {
	VM.sysWrite("S_S_H\n");
	int ptrAdjust = numBytes - 4;
	numBytes -= 4;
	int fromAddress = VM_Magic.objectAsAddress(fromObj) - ptrAdjust;
	VM_Memory.aligned32Copy(toAddress, fromAddress, numBytes);
	Object toObj = VM_Magic.addressAsObject(toAddress + ptrAdjust);
	VM_Magic.setIntAtOffset(toObj, HASHCODE_SYNCH_SCALAR_OFFSET, VM_Magic.objectAsAddress(fromObj));
	VM_Magic.setIntAtOffset(toObj, TIB_OFFSET, availBitsWord | HASH_STATE_HASHED_AND_MOVED);
	VM_GCUtil.validRef(VM_Magic.objectAsAddress(toObj));
	return toObj;
      } else { // HASHED_AND_MOVED already
	VM.sysWrite("S_S_M\n");
	int ptrAdjust = numBytes - 4;
	int fromAddress = VM_Magic.objectAsAddress(fromObj) - ptrAdjust;
	VM_Memory.aligned32Copy(toAddress, fromAddress, numBytes);
	Object toObj = VM_Magic.addressAsObject(toAddress + ptrAdjust);
	VM_Magic.setIntAtOffset(toObj, TIB_OFFSET, availBitsWord);
	VM_GCUtil.validRef(VM_Magic.objectAsAddress(toObj));
	return toObj;
      }
    } else {
      if (hashState == HASH_STATE_UNHASHED) {
	VM.sysWrite("S_U_U\n");
	int ptrAdjust = numBytes + UNSYNCH_PADDING_BYTES;
	int fromAddress = VM_Magic.objectAsAddress(fromObj) - ptrAdjust;
	VM_Memory.aligned32Copy(toAddress, fromAddress, numBytes);
	Object toObj = VM_Magic.addressAsObject(toAddress + ptrAdjust);
	VM_Magic.setIntAtOffset(toObj, TIB_OFFSET, availBitsWord);
	VM_GCUtil.validRef(VM_Magic.objectAsAddress(toObj));
	return toObj;
      } else if (hashState == HASH_STATE_HASHED) {
	VM.sysWrite("S_U_H\n");
	int ptrAdjust = numBytes - 4 + UNSYNCH_PADDING_BYTES;
	numBytes -= 4;
	int fromAddress = VM_Magic.objectAsAddress(fromObj) - ptrAdjust;
	VM_Memory.aligned32Copy(toAddress, fromAddress, numBytes);
	Object toObj = VM_Magic.addressAsObject(toAddress + ptrAdjust);
	VM_Magic.setIntAtOffset(toObj, HASHCODE_SYNCH_SCALAR_OFFSET, VM_Magic.objectAsAddress(fromObj));
	VM_Magic.setIntAtOffset(toObj, TIB_OFFSET, availBitsWord | HASH_STATE_HASHED_AND_MOVED);
	VM_GCUtil.validRef(VM_Magic.objectAsAddress(toObj));
	return toObj;
      } else { // HASHED_AND_MOVED
	VM.sysWrite("S_U_M\n");
	int ptrAdjust = numBytes;
	int fromAddress = VM_Magic.objectAsAddress(fromObj) - ptrAdjust;
	VM_Memory.aligned32Copy(toAddress, fromAddress, numBytes);
	Object toObj = VM_Magic.addressAsObject(toAddress + ptrAdjust);
	VM_Magic.setIntAtOffset(toObj, TIB_OFFSET, availBitsWord);
	VM_GCUtil.validRef(VM_Magic.objectAsAddress(toObj));
	return toObj;
      }
    }
  }

  /**
   * Copy an object to the given raw storage address
   */
  public static Object moveObject(ADDRESS toAddress, Object fromObj, int numBytes, 
				  VM_Array type, Object[] tib, int availBitsWord) {
    int tibWord = VM_Magic.getIntAtOffset(fromObj, TIB_OFFSET);
    int hashState = tibWord & HASH_STATE_MASK;
    if (hashState == HASH_STATE_UNHASHED) {
      VM.sysWrite("A_U\n");
      int fromAddress = VM_Magic.objectAsAddress(fromObj) - ARRAY_HEADER_SIZE;
      VM_Memory.aligned32Copy(toAddress, fromAddress, numBytes); 
      Object toObj = VM_Magic.addressAsObject(toAddress + ARRAY_HEADER_SIZE);
      VM_Magic.setIntAtOffset(toObj, TIB_OFFSET, availBitsWord);
      VM_GCUtil.validRef(VM_Magic.objectAsAddress(toObj));
      return toObj;
    } else if (hashState == HASH_STATE_HASHED) {
      VM.sysWrite("A_H\n");
      int fromAddress = VM_Magic.objectAsAddress(fromObj) - ARRAY_HEADER_SIZE;
      VM_Memory.aligned32Copy(toAddress+4, fromAddress, numBytes); 
      Object toObj = VM_Magic.addressAsObject(toAddress + ARRAY_HEADER_SIZE + 4);
      VM_Magic.setIntAtOffset(toObj, HASHCODE_ARRAY_OFFSET, VM_Magic.objectAsAddress(fromObj));
      VM_Magic.setIntAtOffset(toObj, TIB_OFFSET, availBitsWord | HASH_STATE_HASHED_AND_MOVED);
      VM_GCUtil.validRef(VM_Magic.objectAsAddress(toObj));
      return toObj;
    } else { // HASHED_AND_MOVED
      VM.sysWrite("A_M\n");
      int fromAddress = VM_Magic.objectAsAddress(fromObj) - ARRAY_HEADER_SIZE -4;
      VM_Memory.aligned32Copy(toAddress, fromAddress, numBytes); 
      Object toObj = VM_Magic.addressAsObject(toAddress + ARRAY_HEADER_SIZE + 4);
      VM_Magic.setIntAtOffset(toObj, TIB_OFFSET, availBitsWord);
      VM_GCUtil.validRef(VM_Magic.objectAsAddress(toObj));
      return toObj;
    }
  }

  /**
   * Get the offset of the thin lock word in this object
   */
  public static int getThinLockOffset(Object o) {
    if (VM.VerifyAssertions) VM.assert(hasThinLock(o));
    return STATUS_OFFSET;
  }

  /**
   * Does an object have a thin lock?
   */
  protected static boolean hasThinLock(Object o) { 
    return VM_Magic.getObjectType(o).isSynchronized; 
  }

  /**
   * fastPathLocking
   */
  public static void fastPathLock(Object o) { 
    VM_Magic.pragmaInline();
    if (VM.VerifyAssertions) VM.assert(hasThinLock(o));
    VM_ThinLock.inlineLock(o, STATUS_OFFSET);
  }

  /**
   * fastPathUnlocking
   */
  public static void fastPathUnlock(Object o) { 
    VM_Magic.pragmaInline();
    if (VM.VerifyAssertions) VM.assert(hasThinLock(o));
    VM_ThinLock.inlineUnlock(o, STATUS_OFFSET);
  }

  /**
   * Generic lock
   */
  public static void genericLock(Object o) { 
    VM_Magic.pragmaInline();
    if (hasThinLock(o)) {
      VM_ThinLock.lock(o, STATUS_OFFSET);
    } else {
      VM_LockNursery.lock(o);
    }
  }

  /**
   * Generic unlock
   */
  public static void genericUnlock(Object o) {
    VM_Magic.pragmaInline();
    if (hasThinLock(o)) {
      VM_ThinLock.unlock(o, STATUS_OFFSET);
    } else {
      VM_LockNursery.unlock(o);
    }
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
    if (hasThinLock(o)) {
      return VM_ThinLock.getHeavyLock(o, STATUS_OFFSET, create);
    } else {
      return VM_LockNursery.findOrCreate(o, create);
    }
  }

  //-#if RVM_WITH_OPT_COMPILER
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
