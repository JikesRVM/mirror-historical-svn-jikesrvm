/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 * (C) Copyright IBM Corp. 2002
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.JMTk.Conversions;
import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Uninterruptible;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;

/**
 * This class implements lazy mmapping of virtual memory.
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
public final class LazyMmapper implements Constants, VM_Uninterruptible {
  public final static String Id = "$Id$"; 

  ////////////////////////////////////////////////////////////////////////////
  //
  // Public static methods 
  //
  //

  public static boolean verbose = false;

  public static void ensureMapped(VM_Address start, int blocks) {
    int startChunk = Conversions.addressToMmapChunks(start);       // round down
    int chunks = Conversions.blocksToMmapChunks(blocks); // round up
    int endChunk = startChunk + chunks;
    for (int chunk=startChunk; chunk < endChunk; chunk++) {
      if (mapped[chunk] == UNMAPPED) {
	VM_Address mmapStart = Conversions.mmapChunksToAddress(chunk);
	if (!VM_Interface.mmap(mmapStart, MMAP_CHUNK_SIZE)) {
	  VM.sysWriteln("ensureMapped failed");
	  VM._assert(false);
	}
	else {
	  if (verbose) {
	    VM.sysWrite("mmap succeeded at ", mmapStart);
	    VM.sysWriteln(" with len = ", MMAP_CHUNK_SIZE);
	  }
	}
	mapped[chunk] = MAPPED;
      }
      chunk++;
    }
  }

  public static void protect(VM_Address start, int blocks) {
    int startChunk = Conversions.addressToMmapChunks(start);       // round down
    int chunks = Conversions.blocksToMmapChunks(blocks); // round up
    int endChunk = startChunk + chunks;
    for (int chunk=startChunk; chunk < endChunk; chunk++) {
      if (mapped[chunk] == MAPPED) {
	VM_Address mmapStart = Conversions.mmapChunksToAddress(chunk);
	if (!VM_Interface.mprotect(mmapStart, MMAP_CHUNK_SIZE)) {
	  VM.sysWriteln("mprotect failed");
	  VM._assert(false);
	}
	else {
	  if (verbose) {
	    VM.sysWrite("munmap succeeded at ", mmapStart);
	    VM.sysWriteln(" with len = ", MMAP_CHUNK_SIZE);
	  }
	}
	mapped[chunk] = PROTECTED;
      }
      else {
	if (VM.VerifyAssertions) VM._assert(mapped[chunk] == PROTECTED);
      }
      chunk++;
    }
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Private static methods and variables
  //
  final public static byte UNMAPPED = 0;
  final public static byte MAPPED = 1;
  final public static byte PROTECTED = 2;   // mapped but not accessible
  private static byte mapped[];
  final public static int LOG_MMAP_CHUNK_SIZE = 20;            
  final public static int MMAP_CHUNK_SIZE = 1 << LOG_MMAP_CHUNK_SIZE;   // the granularity VMResource operates at
  final private static int MMAP_NUM_CHUNKS = 1 << (Constants.LOG_ADDRESS_SPACE - LOG_MMAP_CHUNK_SIZE);
  final public  static int MMAP_CHUNK_MASK = ~((1 << LOG_MMAP_CHUNK_SIZE) - 1);

  /**
   * Class initializer.  This is executed <i>prior</i> to bootstrap
   * (i.e. at "build" time).
   */
  static {
    mapped = new byte[MMAP_NUM_CHUNKS];
    for (int c = 0; c < MMAP_NUM_CHUNKS; c++) {
      mapped[c] = UNMAPPED;
    }
  }

}

