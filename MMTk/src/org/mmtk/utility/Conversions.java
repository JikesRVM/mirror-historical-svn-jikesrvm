/*
 * (C) Copyright IBM Corp. 2002
 */
//$Id$

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Memory;

public class Conversions implements Constants {

  // Round up
  //
  public static int bytesToBlocks(EXTENT bytes) {
    return (bytes + (VMResource.BLOCK_SIZE - 1)) & VMResource.BLOCK_MASK;
  }

  // Round up
  //
  public static int bytesToMmapChunk(EXTENT bytes) {
    return (bytes + (LazyMmapper.MMAP_CHUNK_SIZE - 1)) & LazyMmapper.MMAP_CHUNK_MASK;
  }

  // Round up
  //
  public static int blocksToMmapChunks(int blocks) {
    return bytesToMmapChunk(blocks << VMResource.LOG_BLOCK_SIZE);
  }

  // Round down
  //
  public static int addressToMmapChunks(VM_Address addr) {
    return (addr.toInt()) >> LazyMmapper.LOG_MMAP_CHUNK_SIZE;
  }

  // Round down
  //
  public static int addressToBlocks(VM_Address addr) {
    return (addr.toInt() >> VMResource.LOG_BLOCK_SIZE);
  }

  // No rounding needed
  //
  public static int blocksToBytes(int blocks) {
    return blocks << VMResource.LOG_BLOCK_SIZE;
  }

  // No rounding needed
  //
  public static VM_Address blocksToAddress(int blocks) {
    return VM_Address.fromInt(blocks << VMResource.LOG_BLOCK_SIZE);
  }

  // No rounding needed
  //
  public static VM_Address mmapChunksToAddress(int chunk) {
    return (VM_Address.fromInt(chunk << LazyMmapper.LOG_MMAP_CHUNK_SIZE));
  }



}
