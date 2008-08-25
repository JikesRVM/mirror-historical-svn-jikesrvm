/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.debug;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.NormalMethod;

class MethodLocation {
  private final NormalMethod method;
  private final int byteCodeIndex;
  MethodLocation(NormalMethod method, int byteCodeIndex) {
    if (VM.VerifyAssertions) {
      VM._assert(method != null);
      VM._assert(byteCodeIndex >= 0);
      VM._assert(byteCodeIndex < method.getBytecodeLength());
    }
    this.byteCodeIndex = byteCodeIndex;
    this.method = method;
  }
  /* Getters. */
  NormalMethod getMethod() {return method;}
  int getByteCodeIndex() {return byteCodeIndex;}
  @Override
  public boolean equals(Object obj) {
    if (obj instanceof MethodLocation) {
      MethodLocation bp = (MethodLocation)obj;  
      return (byteCodeIndex == bp.byteCodeIndex) && (method.equals(bp.method)); 
    } else {
      return false;         
    }
  }
  public String toString() {
    return "Location: bcindex = " + byteCodeIndex + " in "
        + method.getDeclaringClass().toString() + "." + method.getName()
        + method.getDescriptor();
  }
}
