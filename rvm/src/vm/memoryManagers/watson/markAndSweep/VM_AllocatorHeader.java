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
  implements VM_Uninterruptible 
{
  private static final VM_SideMarkVector markVector = new VM_SideMarkVector();	// Used when no GC bits are available in the object


  /**
   * How many bytes are used by all GC header fields?
   */
  static final int NUM_BYTES_HEADER = 0;

  /**
   * How many available bits does the GC header want to use?  If enough bits are 
   * available for the common case, use that.  Otherwise, use 0 bits and the
   * markVector defined above will be used for mark bits in the boot image.
   */
  static final int REQUESTED_BITS = VM_JavaHeaderConstants.NUM_AVAILABLE_BITS >= COMMON_REQUESTED_BITS ? COMMON_REQUESTED_BITS : 0;

  /*
   * Perform boot-time initialization: allocate the mark vector
   */
  static void boot(int bootBaseAddress, int bootHighAddress) {
    if (VM_JavaHeader.NUM_AVAILABLE_BITS == 0)
      markVector.boot(bootBaseAddress, bootHighAddress);
  }

  /**
   * Perform any required initialization of the GC portion of the header.
   * 
   * @param ref the object ref to the storage to be initialized
   * @param tib the TIB of the instance being created
   * @param size the number of bytes allocated by the GC system for this object.
   * @param isScalar are we initializing a scalar (true) or array (false) object?
   */
  public static void initializeHeader(Object ref, Object[] tib, int size, boolean isScalar) {
    // nothing to do (no bytes of GC header)
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
    // nothing to do (no bytes of GC header)
  }

  /**
   * For low level debugging of GC subsystem. 
   * Dump the header word(s) of the given object reference.
   * @param ref the object reference whose header should be dumped 
   */
  public static void dumpHeader(Object ref) {
    // nothing to do (no bytes of GC header)
  }


  /*
   * Mark Bit
   */

  /**
   * test to see if the mark bit has the given value
   */
  static boolean testMarkBit(Object ref, int value) {
    if (VM_JavaHeader.NUM_AVAILABLE_BITS != 0)
	return VM_CommonAllocatorHeader.testMarkBit(ref, value);
    else
	return markVector.testMarkBit(ref, value);
  }

  /**
   * write the given value in the mark bit.
   */
  static void writeMarkBit(Object ref, int value) {
    if (VM_JavaHeader.NUM_AVAILABLE_BITS != 0)
	VM_CommonAllocatorHeader.writeMarkBit(ref, value);
    else
	markVector.writeMarkBit(ref, value);
  }

  /**
   * atomically write the given value in the mark bit.
   */
  static void atomicWriteMarkBit(Object ref, int value) {
    if (VM_JavaHeader.NUM_AVAILABLE_BITS != 0)
	VM_CommonAllocatorHeader.atomicWriteMarkBit(ref, value);
    else
	markVector.atomicWriteMarkBit(ref, value);
  }

  /**
   * used to mark boot image objects during a parallel scan of objects during GC
   */
  static boolean testAndMark(Object ref, int value) {
    if (VM_JavaHeader.NUM_AVAILABLE_BITS != 0)
	return VM_CommonAllocatorHeader.testAndMark(ref, value);
    else
	return markVector.testAndMark(ref, value);
  }

}
