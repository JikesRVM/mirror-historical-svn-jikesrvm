/*
 * (C) Copyright IBM Corp. 2002
 */
//$Id$

package com.ibm.JikesRVM.memoryManagers.vmInterface;

import VM_Memory;

public class Conversions implements Constants {

  static int bytesToBlocks(Extent bytes) {
    int roundedBytes = VM_Memory.roundUpPage(bytes);
    return roundedBytes / VM_Memory.getPagesize();
  }

  static int addressToMmapChunks(VM_Address addr) {
    VM_Address addr2 = roundDownPage(addr);
    return (addr2.toInt()) / VM_Memory.getPagesize();
  }

  static VM_Address mmapChunksToAddress(int chunk) {
    return (VM_Address.fromInt(chunk * VM_Memory.getPagesize()));
  }

  static int blocksToMmapChunks(int blocks) {
    return blocks;
  }

}
