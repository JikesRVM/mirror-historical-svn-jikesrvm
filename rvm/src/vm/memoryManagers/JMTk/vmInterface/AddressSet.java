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

/*
 * @author Perry Cheng  
 */  

public class AddressSet {

  // Deficiency in compiler prevents use of VM_Address []
  private int [] address;
  private int cursor;

  public AddressSet(int size) { 
    address = new int[size];
  }

  public void clear() { 
    cursor = 0; 
  }

  public boolean isEmpty() {
    return cursor == 0;
  }

  public void push(VM_Address addr) { 
    if (VM.VerifyAssertions) VM.assert(!addr.isZero());
    address[cursor++] = addr.toInt(); 
  }

  public VM_Address pop() {
    if (cursor == 0)
      return VM_Address.zero();
    return VM_Address.fromInt(address[--cursor]);
  }

}
