/*
 * (C) Copyright IBM Corp. 2002
 */
//$Id$

package com.ibm.JikesRVM.memoryManagers.vmInterface;

import com.ibm.JikesRVM.VM_Memory;

public class Conversions implements Constants {

  public static int bytesToBlocks(Extent bytes) {
    int roundedBytes = VM_Memory.roundUpPage(bytes);
    return roundedBytes / VM_Memory.getPagesize();
  }

  public static int addressToMmapChunks(VM_Address addr) {
    VM_Address addr2 = roundDownPage(addr);
    return (addr2.toInt()) / VM_Memory.getPagesize();
  }

  public static VM_Address mmapChunksToAddress(int chunk) {
    return (VM_Address.fromInt(chunk * VM_Memory.getPagesize()));
  }

  public static int blocksToMmapChunks(int blocks) {
    return blocks;
  }

  public static int blocksToBytes() {
    return blocks;
  }

}
