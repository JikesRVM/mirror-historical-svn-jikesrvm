/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

/**
 * The interface to the object model definition accessible to the 
 * virtual machine. <p>
 * 
 * Conceptually each Java object is composed of the following pieces:
 * <ul>
 * <li> The JavaHeader defined by @link{VM_JavaHeader}. This portion of the
 *      object supports language-level functions such as locking, hashcodes,
 *      dynamic type checking, virtual function invocation, and array length.
 * <li> The GCHeader defined by @link{VM_AllocatorHeader}. This portion
 *      of the object supports allocator-specific requirements such as 
 *      mark/barrier bits, reference counts, etc.
 * <li> The MiscHeader defined by @link{VM_MiscHeader}. This portion supports 
 *      various other clients that want to add bits/words to all objects. 
 *      Typical uses are profiling and instrumentation (basically this is a 
 *      way to add an instance field to java.lang.Object).
 * <li> The instance fields.  Currently defined by various classloader classes.
 *      Factoring this code out and making it possible to lay out the instance
 *      fields in different ways is a todo item.
 * </ul>
 * 
 * For scalar objects, we lay out the fields right (hi mem) to left (lo mem).
 * For array objects, we lay out the elements left (lo mem) to right (hi mem).
 * Every object's header contains the three portions outlined above.
 *
 * <pre>
 * |<- lo memory                                        hi memory ->|
 *
 * scalar-object layout:
 * +------+------+------+------+------------+----------+------------+- - - +
 * |fldN-1| fldx | fld1 | fld0 | JavaHeader | GCHeader | MiscHeader |      |
 * +------+------+------+------+------------+----------+------------+- - - +
 *                             .                                    .      ^objref
 *                             .  <----------- header ----------->  .      
 *  array-object layout:       .                                    .
 *                             +------------+----------+------------+------+------+------+------+------+
 *                             | JavaHeader | GCHeader | MiscHeader | len  | elt0 | elt1 | ...  |eltN-1|
 *                             +------------+----------+------------+------+------+------+------+------+
 *                                                                         ^objref
 * </pre>
 *
 * Assumptions: 
 * <ul>
 * <li> Each portion of the header (JavaHeader, GCHeader, MiscHeader) 
 *      is some multiple of 4 bytes (possibly 0).  This simplifies access, since we
 *      can access each portion independently without having to worry about word tearing.
 * <li> The JavaHeader exports k (>=0) unused contiguous bits that can be used
 *      by the GCHeader and MiscHeader.  The GCHeader gets first dibs on these bits.
 *      The GCHeader should use buts 0..i, MiscHeader should use bits i..k.
 * <li> In a given configuration, the GCHeader and MiscHeader are a fixed number of words for 
 *      all objects.
 * </ul>
 * 
 * This model allows efficient array access: the array pointer can be
 * used directly in the base+offset subscript calculation, with no
 * additional constant required.<p>
 *
 * This model allows free null pointer checking: since the 
 * first access to any object is via a negative offset 
 * (length field for an array, regular/header field for an object), 
 * that reference will wrap around to hi memory (address 0xffffxxxx)
 * in the case of a null pointer. As long as the high segment of memory is not 
 * mapped to the current process, loads/stores through such a pointer will cause a 
 * trap that we can catch with a unix signal handler.<p>
 * 
 * Note: On Linux we can actually protect low memory as well as high memory, 
 * so a future todo item would be to switch the Linux/IA32 object model to look 
 * like the following to simplify some GC implementations without losing any of
 * the other advantages of the original JikesRVM object layout:
 * <pre>
 *         +------------+----------+------------+------+------+------+------+
 *         | JavaHeader | GCHeader | MiscHeader | fld0 | fld1 + .... | fldN |
 *         +------------+----------+------------+------+------+------+------+
 *                                              ^objref
 * 
 *  +------+------------+----------+------------+------+------+------+------+
 *  | len  | JavaHeader | GCHeader | MiscHeader | elt0 | elt1 | ...  |eltN-1|
 *  +------+------------+----------+------------+------+------+------+------+
 *                                              ^objref
 * </pre>
 * 
 * Note the key invariant that all elements of the header are 
 * available at the same offset from an objref for both arrays and 
 * scalar objects.
 * 
 * @author Bowen Alpern
 * @author David Bacon
 * @author Stephen Fink
 * @author Dave Grove
 * @author Derek Lieber
 */
public final class VM_ObjectModel implements VM_Uninterruptible, 
					     VM_ObjectModelConstants {
  /**
   * Get the TIB for an object.
   */
  public static Object[] getTIB(ADDRESS ptr) { 
    return getTIB(VM_Magic.addressAsObject(ptr)); 
  }

  /**
   * Get the TIB for an object.
   */
  public static Object[] getTIB(Object o) { 
    return VM_JavaHeader.getTIB(o);
  }
  
  /**
   * Set the TIB for an object.
   */
  public static void setTIB(ADDRESS ptr, Object[] tib) {
    setTIB(VM_Magic.addressAsObject(ptr),tib);
  }

  /**
   * Set the TIB for an object.
   */
  public static void setTIB(Object ref, Object[] tib) {
    VM_JavaHeader.setTIB(ref, tib);
  }

  /**
   * Set the TIB for an object.
   */
  public static void setTIB(BootImageInterface bootImage, int refOffset, int tibAddr, VM_Type type) {
    VM_JavaHeader.setTIB(bootImage, refOffset, tibAddr, type);
  }

  /**
   * Get the type of an object.  
   */
  public static VM_Type getObjectType(Object o) { 
    return VM_Magic.getObjectType(o);
  }

  /** 
   * Get the length of an array
   */
  public static int getArrayLength(Object o) {
    return VM_Magic.getIntAtOffset(o, ARRAY_LENGTH_OFFSET);
  }

  /**
   * Set the length of an array
   */
  public static void setArrayLength(Object o, int len) {
    VM_Magic.setIntAtOffset(o, ARRAY_LENGTH_OFFSET, len);
  }

  /**
   * Get the hash code of an object.
   */
  public static int getObjectHashCode(Object o) { 
    return VM_JavaHeader.getObjectHashCode(o);
  }

  /**
   * Does an object have a thin lock?
   */
  public static boolean hasThinLock(Object o) { 
    return VM_JavaHeader.hasThinLock(o);
  }

  /**
   * Non-atomic read of the word containing o's thin lock
   */
  public static int getThinLock(Object o) {
    if (VM.VerifyAssertions) VM.assert(hasThinLock(o));
    return VM_JavaHeader.getThinLock(o);
  }

  /**
   * Prepare of the word containing o's thin lock
   */
  public static int prepareThinLock(Object o) {
    if (VM.VerifyAssertions) VM.assert(hasThinLock(o));
    return VM_JavaHeader.prepareThinLock(o);
  }

  /**
   * Attempt of the word containing o's thin lock
   */
  public static boolean attemptThinLock(Object o, int oldValue, int newValue) {
    if (VM.VerifyAssertions) VM.assert(hasThinLock(o));
    return VM_JavaHeader.attemptThinLock(o, oldValue, newValue);
  }

  /**
   * fastPathLocking
   */
  public static void fastPathLock(Object o) { 
    if (VM.VerifyAssertions) VM.assert(hasThinLock(o));
    VM_JavaHeader.fastPathLock(o);
  }

  /**
   * fastPathUnlocking
   */
  public static void fastPathUnlock(Object o) { 
    if (VM.VerifyAssertions) VM.assert(hasThinLock(o));
    VM_JavaHeader.fastPathUnlock(o);
  }

  /**
   * Generic lock
   */
  public static void genericLock(Object o) { 
    VM_JavaHeader.genericLock(o);
  }

  /**
   * Generic unlock
   */
  public static void genericUnlock(Object o) {
    VM_JavaHeader.genericUnlock(o);
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
    return VM_JavaHeader.getHeavyLock(o, create);
  }

  /**
   * Non-atomic read of word containing available bits
   */
  public static int readAvailableBitsWord(Object o) {
    return VM_JavaHeader.readAvailableBitsWord(o);
  }

  /**
   * Non-atomic read of byte containing available bits
   */
  public static int readAvailableBitsByte(Object o) {
    return VM_JavaHeader.readAvailableBitsByte(o);
  }

  /**
   * Non-atomic write of word containing available bits
   */
  public static void writeAvailableBitsWord(Object o, int val) {
    VM_JavaHeader.writeAvailableBitsWord(o, val);
  }

  /**
   * Non-atomic write of byte containing available bits
   */
  public static void writeAvailableBitsByte(Object o, byte val) {
    VM_JavaHeader.writeAvailableBitsByte(o, val);
  }

  /**
   * Return true if argument bit is 1, false if it is 0
   */
  public static boolean testAvailableBit(Object o, int idx) {
    return VM_JavaHeader.testAvailableBit(o, idx);
  }

  /**
   * Set argument bit to 1 if flag is true, 0 if flag is false
   */
  public static void setAvailableBit(Object o, int idx, boolean flag) {
    VM_JavaHeader.setAvailableBit(o, idx, flag);
  }

  /**
   * Freeze the other bits in the byte containing the available bits
   * so that it is safe to update them using setAvailableBits.
   */
  public static void initializeAvailableByte(Object o) {
    VM_JavaHeader.initializeAvailableByte(o);
  }

  /**
   * A prepare on the word containing the available bits
   */
  public static int prepareAvailableBits(Object o) {
    return VM_JavaHeader.prepareAvailableBits(o);
  }
  
  /**
   * An attempt on the word containing the available bits
   */
  public static boolean attemptAvailableBits(Object o, int oldVal, int newVal) {
    return VM_JavaHeader.attemptAvailableBits(o, oldVal, newVal);
  }
  
  /**
   * Given the smallest base address in a region, return the smallest
   * object reference that could refer to an object in the region.
   */
  public static int minimumObjectRef (int regionBaseAddr) {
    return VM_JavaHeader.minimumObjectRef(regionBaseAddr);
  }

  /**
   * Given the largest base address in a region, return the largest
   * object reference that could refer to an object in the region.
   */
  public static int maximumObjectRef (int regionHighAddr) {
    return VM_JavaHeader.maximumObjectRef(regionHighAddr);
  }

  /**
   * Compute the header size of an instance of the given type.
   */
  public static int computeHeaderSize(VM_Type type) {
    return (type.dimension>0)?computeArrayHeaderSize(type):computeScalarHeaderSize(type);
  }

  /**
   * Compute the header size of an instance of the given type.
   */
  public static int computeScalarHeaderSize(VM_Type type) {
    return VM_JavaHeader.computeScalarHeaderSize(type);
  }

  /**
   * Compute the header size of an instance of the given type.
   */
  public static int computeArrayHeaderSize(VM_Type type) {
    return VM_JavaHeader.computeArrayHeaderSize(type);
  }

  /**
   * Given a TIB, compute the header size of an instance of the TIB's class
   */
  public static int computeHeaderSize(Object[] tib) {
    return computeHeaderSize(VM_Magic.objectAsType(tib[0]));
  }

  /**
   * Convert an object reference into the low memory word of the raw
   * storage that holds the object.
   * @param ref an object reference
   */
  public static ADDRESS objectRefToBaseAddress(Object ref) {
    VM_Magic.pragmaInline();
    VM_Type t = getObjectType(ref);
    if (t.dimension > 0) {
      return arrayRefToBaseAddress(ref, t);
    } else {
      return scalarRefToBaseAddress(ref, t.asClass());
    }
  }      

  /**
   * Convert an array reference into the low memory word of the raw
   * storage that holds the object.
   * 
   * @param ref an array reference
   * @param t  the VM_Type of the array
   */
  public static ADDRESS arrayRefToBaseAddress(Object ref, VM_Type t) {
    return VM_JavaHeader.arrayRefToBaseAddress(ref, t);
  }

  /**
   * Convert a scalar object reference into the low memory word of the raw
   * storage that holds the object.
   * 
   * @param ref a scalar object reference
   * @param t  the VM_Type of the object
   */
  public static ADDRESS scalarRefToBaseAddress(Object ref, VM_Class t) {
    return VM_JavaHeader.scalarRefToBaseAddress(ref, t);
  }

  /**
   * Initialize raw storage with low memory word ptr of size bytes
   * to be an uninitialized instance of the (scalar) type specified by tib.
   * 
   * @param ptr address of raw storage
   * @param tib the type information block
   * @param size number of bytes of raw storage allocated.
   */
  public static Object initializeScalar(ADDRESS ptr, Object[] tib, int size) {
    VM_Magic.pragmaInline();
    Object ref = VM_Magic.addressAsObject(VM_JavaHeader.baseAddressToScalarAddress(ptr, tib, size));
    setTIB(ref, tib);
    VM_JavaHeader.initializeHeader(ref, tib, size, true);
    VM_AllocatorHeader.initializeHeader(ref, tib, size, true);
    VM_MiscHeader.initializeHeader(ref, tib, size, true);
    return ref;
  }


  /**
   * Allocate and initialize space in the bootimage (at bootimage writing time)
   * to be an uninitialized instance of the (scalar) type specified by klass.
   * NOTE: TIB is set by BootImageWriter2
   * 
   * @param bootImage the bootimage to put the object in
   * @param klass the VM_Class object of the instance to create.
   * @return the offset of object in bootimage (in bytes)
   */
  public static int allocateScalar(BootImageInterface bootImage, VM_Class klass) {
    Object[] tib = klass.getTypeInformationBlock();
    int size = klass.getInstanceSize();
    int ptr = bootImage.allocateStorage(size);
    int ref = VM_JavaHeader.baseAddressToScalarAddress(ptr, tib, size);
    VM_JavaHeader.initializeHeader(bootImage, ref, tib, size, true);
    VM_AllocatorHeader.initializeHeader(bootImage, ref, tib, size, true);
    VM_MiscHeader.initializeHeader(bootImage, ref, tib, size, true);
    return ref;
  }


  /**
   * Initialize raw storage with low memory word ptr of size bytes
   * to be an uninitialized instance of the array type specific by tib
   * with numElems elements.
   * 
   * @param ptr address of raw storage
   * @param tib the type information block
   * @param numElems number of elements in the array
   * @param size number of bytes of raw storage allocated.
   */
  public static Object initializeArray(ADDRESS ptr, Object[] tib, int numElems, int size) {
    VM_Magic.pragmaInline();
    Object ref = VM_Magic.addressAsObject(VM_JavaHeader.baseAddressToArrayAddress(ptr, tib, size));
    setTIB(ref, tib);
    setArrayLength(ref, numElems);
    VM_JavaHeader.initializeHeader(ref, tib, size, false);
    VM_AllocatorHeader.initializeHeader(ref, tib, size, false);
    VM_MiscHeader.initializeHeader(ref, tib, size, false);
    return ref;
  }

  /**
   * Allocate and initialize space in the bootimage (at bootimage writing time)
   * to be an uninitialized instance of the (array) type specified by array.
   * NOTE: TIB is set by BootimageWriter2
   * 
   * @param bootImage the bootimage to put the object in
   * @param array VM_Array object of array being allocated.
   * @param numElements number of elements
   * @return the offset of object in bootimage (in bytes)
   */
  public static int allocateArray(BootImageInterface bootImage, 
				  VM_Array array,
				  int numElements) {
    Object[] tib = array.getTypeInformationBlock();
    int size = array.getInstanceSize(numElements);
    int ptr = bootImage.allocateStorage(size);
    int ref = VM_JavaHeader.baseAddressToArrayAddress(ptr, tib, size);
    bootImage.setFullWord(ref + ARRAY_LENGTH_OFFSET, numElements);
    VM_JavaHeader.initializeHeader(bootImage, ref, tib, size, false);
    VM_AllocatorHeader.initializeHeader(bootImage, ref, tib, size, false);
    VM_MiscHeader.initializeHeader(bootImage, ref, tib, size, false);
    return ref;
  }

  /**
   * Initialize a cloned scalar object from the clone src
   */
  public static void initializeScalarClone(Object cloneDst, Object cloneSrc, int size) {
    VM_JavaHeader.initializeScalarClone(cloneDst, cloneSrc, size);
  }

  /**
   * Initialize a cloned array object from the clone src
   */
  public static void initializeArrayClone(Object cloneDst, Object cloneSrc, int size) {
    VM_JavaHeader.initializeArrayClone(cloneDst, cloneSrc, size);
  }


  /**
   * For low level debugging of GC subsystem. 
   * Dump the header word(s) of the given object reference.
   * @param ptr the object reference whose header should be dumped 
   */
  public static void dumpHeader(ADDRESS ptr) {
    dumpHeader(VM_Magic.addressAsObject(ptr));
  }

  /**
   * For low level debugging of GC subsystem. 
   * Dump the header word(s) of the given object reference.
   * @param ref the object reference whose header should be dumped 
   */
  public static void dumpHeader(Object ref) {
    VM.sysWrite(" TIB=");
    VM.sysWriteHex(VM_Magic.objectAsAddress(getTIB(ref)));
    VM_JavaHeader.dumpHeader(ref);
    VM_AllocatorHeader.dumpHeader(ref);
    VM_MiscHeader.dumpHeader(ref);
  }

  /**
   * The following method will emit code that moves a reference to an
   * object's TIB into a destination register.
   *
   * @param asm the assembler object to emit code with
   * @param dest the number of the destination register
   * @param object the number of the register holding the object reference
   */
  public static void baselineEmitLoadTIB(VM_Assembler asm, int dest, 
                                         int object) {
    VM_JavaHeader.baselineEmitLoadTIB(asm, dest, object);
  }

  //-#if RVM_WITH_OPT_COMPILER
  /**
   * Mutate a GET_OBJ_TIB instruction to the LIR
   * instructions required to implement it.
   * 
   * @param s the GET_OBJ_TIB instruction to lower
   * @param ir the enclosing OPT_IR
   */
  public static void lowerGET_OBJ_TIB(OPT_Instruction s, OPT_IR ir) {
    VM_JavaHeader.lowerGET_OBJ_TIB(s, ir);
  }

  /**
   * Mutate a IG_CLASS_TEST instruction to the LIR
   * instructions required to implement it.
   *
   * @param s the IG_CLASS_TEST instruction to lower
   * @param ir the enclosing OPT_IR
   */
  public static void lowerIG_CLASS_TEST(OPT_Instruction s, OPT_IR ir) {
    VM_JavaHeader.lowerIG_CLASS_TEST(s, ir);
  }
  //-#endif
}
