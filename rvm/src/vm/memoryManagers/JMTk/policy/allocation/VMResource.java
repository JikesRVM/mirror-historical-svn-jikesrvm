/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 * All rights reserved.
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Uninterruptible;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;

/**
 * This class implements a virtual memory resource.  The unit of
 * managment for virtual memory resources is the <code>BLOCK</code><p>
 *
 * Instances of this class each manage a contigious region of virtual
 * memory.  The class's static methods and fields coordinate to ensure
 * coherencey among VM resource requests (i.e. that they do not
 * overlap).  Initially this class will only support monotonic
 * allocation, but subsequently it may support free-list style
 * allocation/freeing<p>
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
public final class VMResource implements Constants, VM_Uninterruptible {
  public final static String Id = "$Id$"; 

  ////////////////////////////////////////////////////////////////////////////
  //
  // Public static methods (aka "class methods")
  //
  // Static methods and fields of MM are those with global scope, such
  // as virtual memory and memory resources.  This stands in contrast
  // to instance methods which are for fast, unsychronized access to
  // thread-local structures such as bump pointers and remsets.
  //

  /**
   * Class initializer.  This is executed <i>prior</i> to bootstrap
   * (i.e. at "build" time).
   */
  {
    mapped = new boolean[MMAP_CHUNKS];
    for (int c = 0; c < MMAP_CHUNKS; c++)
      mapped[c] = false;
  }

  public static void showAll () {
    VM._assert(false);
  }

  public static boolean refInVM(VM_Address ref) throws VM_PragmaUninterruptible {
      return refInAnyHeap(ref);
  }

  public static boolean addrInVM(VM_Address address) throws VM_PragmaUninterruptible {
      return addrInAnyHeap(address);
  }

  public static boolean refInBootImage(VM_Address ref) throws VM_PragmaUninterruptible {
      return bootHeap.refInHeap(ref);
  }

  public static boolean refInHeap(VM_Address ref) throws VM_PragmaUninterruptible {
      return (refInVM(ref) && (!refInBootImage(ref)));
  }

  public static boolean addrInBootImage(VM_Address address) throws VM_PragmaUninterruptible {
    return bootHeap.addrInHeap(address);
  }

  public static int getMaxVMResource() {
    VM._assert(false);
    return 0;
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Public instance methods
  //
  /**
   * Constructor
   */
  VMResource(VM_Address vmStart, Extent bytes) {
    start = vmStart;
    blocks = Conversions.bytesToBlocks(bytes);

    // now check: block-aligned, non-conflicting
  }

  /**
   * Acquire a number of contigious blocks from the virtual memory resource.
   *
   * @param request The number of blocks requested
   * @return The address of the start of the virtual memory region, or
   * zero on failure.
   */
  public final synchronized VM_Address acquire(int request) {
    if (blocks < request) {
      // FIXME Is this really how we want to deal with failure?
      return VM_Address.zero();
    } else {
      VM_Address oldStart = start;
      start = start.add(Conversions.blocksToBytes(blocks));
      ensureMapped(oldStart, request);
      return oldStart;
    }
  }
  
  
  ////////////////////////////////////////////////////////////////////////////
  //
  // Private methods
  //
  private static void ensureMapped(VM_Address start, int request) {
    int chunk = Conversions.addressToMmapChunks(start);
    int sentinal = chunk + Conversions.blocksToMmapChunks(request);
    while (chunk < sentinal) {
      if (!mapped[chunk]) {
	Runtime.mmap(Conversions.mmapChunksToAddress(chunk), MMAP_CHUNK_BYTES);
	mapped[chunk] = true;
      }
      chunk++;
    }
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Class variables
  //
  private static boolean mapped[];
  
}
