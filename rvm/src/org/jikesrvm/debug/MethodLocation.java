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

import org.jikesrvm.ArchitectureSpecific;
import org.jikesrvm.Callbacks;
import org.jikesrvm.Constants;
import org.jikesrvm.UnimplementedError;
import org.jikesrvm.VM;
import org.jikesrvm.ArchitectureSpecific.CodeArray;
import org.jikesrvm.ArchitectureSpecific.Registers;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.baseline.BaselineCompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.compilers.opt.runtimesupport.OptCompiledMethod;
import org.jikesrvm.jni.JNICompiledMethod;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.runtime.RuntimeEntrypoints;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.Scheduler;
import org.jikesrvm.util.LinkedListRVM;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;

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
