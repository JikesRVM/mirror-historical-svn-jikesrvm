/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 * All rights reserved.
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Conversions;
import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;

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
    resources = new VMResource[MAX_VMRESOURCE];
    lookup = new int[NUM_CHUNKS];
    mapped = new boolean[NUM_CHUNKS];
    for (int c = 0; c < NUM_CHUNKS; c++) {
      lookup[c] = NONE_INDEX;
      mapped[c] = false;
    }
    // make the bootImage VMResource
    VM_Resource bootImage = new VMResource(VM_Address.fromInt(VM_Interface.BOOT_START), 
					   VM_Interface.BOOT_SIZE);  
    VM._assert(bootImage.index == BOOT_INDEX);
  }

  public static void showAll () {
    VM.sysWriteln("showAll not implemented");
    VM._assert(false);
  }

  public static boolean refInVM(VM_Address ref) throws VM_PragmaUninterruptible {
    return lookupChunk(VM_Interface.refToAddress(ref)) != NONE_INDEX;
  }

  public static boolean addrInVM(VM_Address addr) throws VM_PragmaUninterruptible {
    return lookupChunk(addr) != NONE_INDEX;
  }

  public static boolean refInBootImage(VM_Address ref) throws VM_PragmaUninterruptible {
    return lookupChunk(VM_Interface.refToAddress(ref)) == BOOT_INDEX;
  }

  public static boolean addrInBootImage(VM_Address addr) throws VM_PragmaUninterruptible {
    return lookupChunk(addr) == BOOT_INDEX;
  }

  public static int getMaxVMResource() {
    return MAX_VMRESOURCE;
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Private static methods and variables
  //
  private static boolean mapped[];
  private static int lookup[];      // Maps an address to corresponding VM resource.  -1 if no corresponding VM resource.
  private static int count;         // How many VMResources exist now?
  private static VMResource resources[];     // List of all VMResources.
  private static int MAX_VMRESOURCE = 100;
  private static int NONE_INDEX = -1;
  private static int BOOT_INDEX = 0;
  private static int LOG_CHUNK_SIZE = 20;            
  private static int LOG_ADDRESS_SPACE = 32;
  private static int CHUNK_SIZE = 1 << LOG_CHUNK_SIZE;   // the granularity VMResource operates at
  private static int NUM_CHUNKS = 1 << (LOG_ADDRESS_SPACE - LOG_CHUNK_SIZE);

  private static int lookupChunk(VM_Address addr) {
    return lookup[VM_Address.toInt(addr) >> LOG_CHUNK_SIZE];
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
    index = count++;
    resources[index] = this;
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
  // Private fields and methods
  //

  private int index;
  private VM_Address start;
  private int blocks;

  private static void ensureMapped(VM_Address start, int request) {
    int chunk = Conversions.addressToMmapChunks(start);
    int sentinal = chunk + Conversions.blocksToMmapChunks(request);
    while (chunk < sentinal) {
      if (!mapped[chunk]) {
	if (!VM_Interface.mmap(Conversions.mmapChunksToAddress(chunk), VM_Interface.MMAP_CHUNK_BYTES)) {
	  VM.sysWriteln("ensureMapped failed");
	  VM._assert(false);
	}
	mapped[chunk] = true;
      }
      chunk++;
    }
  }


}
