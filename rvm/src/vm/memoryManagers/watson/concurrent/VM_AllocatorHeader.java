/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

/**
 * Defines header words used by memory manager.not used for 
 *
 * @see VM_ObjectModel
 * 
 * @author David Bacon
 * @author Steve Fink
 * @author Dave Grove
 */
public final class VM_AllocatorHeader extends VM_CommonAllocatorHeader
  implements VM_Uninterruptible {

  /**
   * How many bytes are used by all GC header fields?
   */
  static final int NUM_BYTES_HEADER = 4;

  /**
   * How many available bits does the GC header want to use?
   */
  static final int REQUESTED_BITS = COMMON_REQUESTED_BITS + 0;

  /**
   * Offset of the reference count in the object header
   */
  static final int REFCOUNT_OFFSET  = OBJECT_HEADER_END - VM_MiscHeader.NUM_BYTES_HEADER - 4;
  
  /**
   * Perform any required initialization of the GC portion of the header.
   * 
   * @param ref the object ref to the storage to be initialized
   * @param tib the TIB of the instance being created
   * @param size the number of bytes allocated by the GC system for this object.
   * @param isScalar are we initializing a scalar (true) or array (false) object?
   */
  public static void initializeHeader(Object ref, Object[] tib, int size, boolean isScalar) {
    // set mark bit in status word, if initial (unmarked) value is not 0      
    if (VM_Allocator.MARK_VALUE==0) writeMarkBit(ref, GC_MARK_BIT_MASK);
    

  }

  /**
   * Perform any required initialization of the GC portion of the header.
   * 
   * @param bootImage the bootimage being written
   * @param ref the object ref to the storage to be initialized
   * @param tib the TIB of the instance being created
   * @param size the number of bytes allocated by the GC system for this object.
   * @param isScalar are we initializing a scalar (true) or array (false) object?
   */
  public static void initializeHeader(BootImageInterface bootImage, int ref, 
				      Object[] tib, int size, boolean isScalar) {
    bootImage.setFullWord(ref + REFCOUNT_OFFSET, VM_RCGC.BOOTIMAGE_REFCOUNT);
  }

  /**
   * For low level debugging of GC subsystem. 
   * Dump the header word(s) of the given object reference.
   * @param ref the object reference whose header should be dumped 
   */
  public static void dumpHeader(Object ref) {
    VM.sysWrite(" REFCOUNT=");
    VM.sysWriteHex(VM_Magic.getIntAtOffset(ref, REFCOUNT_OFFSET));
  }
}
