/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 * All rights reserved.
 */
/**
 * This class implements a simple bump pointer allocator.  The
 * allocator operates in <code>BLOCK</code> sized units.  Intra-block
 * allocation is fast, requiring only a load, addition comparison and
 * store.  If a block boundary is encountered the allocator will
 * request more memory (virtual and actual).
 *
 * FIXME This code takes no account of the fact that Jikes RVM can
 * have an object pointer *beyond* the memory allocated for that
 * object---the significance of this is that if the object pointer
 * (rather than the allocated space) is used to test whether an object
 * is within a particular region, it could lie.
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
final class AllocatorBumpPointer implements Constants, Uninterruptible extends BasePlan {
  public final static String Id = "$Id$"; 

  /**
   * Constructor
   *
   * @param vmr The virtual memory resource from which this bump
   * pointer will acquire virtual memory.
   * @param mr The memory resource from which this bump pointer will
   * acquire memory.
   */
  AllocatorBumpPointer(VMResource vmr, MemoryResource mr) {
    bp = INITIAL_BP_VALUE;
    vmResource = vmr;
    memoryResource = mr;
  }

  /**
   * Re-associate this bump pointer with a different virtual memory
   * resource.  Reset the bump pointer so that it will use this virtual
   * memory resource on the next call to <code>alloc</code>.
   *
   * @param vmr The virtual memory resouce with which this bump
   * pointer is to be associated.
   */
  public void rebind(VMResource vmr) {
    bp = INITIAL_BP_VALUE;
    vmResource = vmr;
  }

  /**
   * Re-associate this bump pointer with a different memory
   * resource.  Reset the bump pointer so that it will use this 
   * memory resource on the next call to <code>alloc</code>.
   *
   * @param mr The memory resource with which this bump pointer is to
   * be associated.
   */
  public void rebind(MemoryResource mr) {
    bp = INITIAL_BP_VALUE;
    memoryResource = mr;
  }

  /**
   * Allocate space for a new object
   *
   * @param isScalar Is the object to be allocated a scalar (or array)?
   * @param bytes The number of bytes allocated
   * @return The address of the first byte of the allocated region
   */
  public Address alloc(boolean isScalar, Extent bytes) throws VM_PragmaInline {
    Address oldbp = bp;
    bp += bytes;
    if ((oldbp ^ bp) >= TRIGGER) 
      return allocOverflow();
    return oldbp;
  }

  public allocOverflow(Extent bytes) throws VM_PragmaNoInline {
    int blocks = Conversions.bytesToBlocks(bytes);
    memoryResource.acquire(blocks);
    VM_Address start = vmResource.acquire(blocks);
    bp = start.add(bytes);
    return start;
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Instance variables
  //
  private Address bp;
  private VMResource vmResource;
  private MemoryResource memoryResource;

  ////////////////////////////////////////////////////////////////////////////
  //
  // Final class variables (aka constants)
  //
  private static final Extent TRIGGER = BLOCK_SIZE;
  // this ensures the bump pointer will go slow path on first alloc
  private static final Address INITIAL_BP_VALUE = (TRIGGER - 1);
}
