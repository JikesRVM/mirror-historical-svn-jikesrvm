/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Constants;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_PragmaInline;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;
import com.ibm.JikesRVM.VM_Uninterruptible;
import com.ibm.JikesRVM.VM_PragmaInterruptible;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_Memory;


/*
 * @author Perry Cheng  
 */  

public class Memory implements VM_Uninterruptible {

  static boolean isZeroed(VM_Address start, EXTENT size) {
    if (VM.VerifyAssertions) VM._assert(size == (size & (~3)));
    for (int i=0; i<size; i+=4) 
      if (!VM_Magic.getMemoryAddress(start.add(i)).isZero())
	return false;
    return true;
  }

  static void zero(VM_Address start, VM_Address end) {
    VM_Memory.zero(start, end);
  }

  static void zero(VM_Address start, int len) {
    VM_Memory.zero(start, len);
  }

}
