/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

package com.ibm.JikesRVM.memoryManagers.vmInterface;

import VM;
import VM_Address;
import VM_ClassLoader;
import VM_SystemClassLoader;
import VM_EventLogger;
import VM_BootRecord;
import VM_PragmaUninterruptible;
import VM_Uninterruptible;

/*
 * @author Perry Cheng  
 */  

public class AddressPairSet implements VM_Uninterruptible {

  // Deficiency in compiler prevents use of VM_Address []
  int [] address;
  int cursor;

  public AddressPairSet(int size) { 
    address = new int[2 * size];
  }

  public void clear() { 
    cursor = 0; 
  }

  public boolean isEmpty() {
    return cursor == 0;
  }

  public void push(VM_Address addr1, VM_Address addr2) { 
    if (VM.VerifyAssertions) VM.assert(!addr1.isZero());
    if (VM.VerifyAssertions) VM.assert(!addr2.isZero());
    // Backwards so that pop1/pop2 will return in the right order
    address[cursor++] = addr2.toInt(); 
    address[cursor++] = addr1.toInt(); 
  }

  public VM_Address pop1() {
    if (cursor == 0)
      return VM_Address.zero();
    if (VM.VerifyAssertions) VM.assert((cursor & 1) == 0);
    return VM_Address.fromInt(address[--cursor]);
  }

  public VM_Address pop2() {
    if (cursor == 0)
      return VM_Address.zero();
    if (VM.VerifyAssertions) VM.assert((cursor & 1) == 1);
    return VM_Address.fromInt(address[--cursor]);
  }

}
