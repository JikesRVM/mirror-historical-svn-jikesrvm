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
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.util.LinkedListRVM;


/**
 * A bytecode level Single step implementation.
 */
class SingleStepPoint {
  private final RVMThread thread;
  private final NormalMethod method;
  private final int byteCodeIndex;
  RVMThread getThread() {
    return thread;
  }
  NormalMethod getMethod() {
    return method;
  }
  int getByteCodeIndex() {
    return byteCodeIndex;
  }
  public SingleStepPoint(int byteCodeIndex, NormalMethod method,
      RVMThread thread) {
    super();
    this.byteCodeIndex = byteCodeIndex;
    this.method = method;
    this.thread = thread;
  }
  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + byteCodeIndex;
    result = prime * result + ((method == null) ? 0 : method.hashCode());
    result = prime * result + ((thread == null) ? 0 : thread.hashCode());
    return result;
  }
  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    SingleStepPoint other = (SingleStepPoint) obj;
    if (byteCodeIndex != other.byteCodeIndex)
      return false;
    if (method == null) {
      if (other.method != null)
        return false;
    } else if (!method.equals(other.method))
      return false;
    if (thread == null) {
      if (other.thread != null)
        return false;
    } else if (!thread.equals(other.thread))
      return false;
    return true;
  }
  
  /**
   * Decode the current bytecode the execute in a thread, find the next dynamic
   * bytecode, and set a Breakpoint.
   * 
   * @param t The thread.
   */
  static SingleStepPoint findSingleStepPoint(RVMThread t) {
    if (VM.VerifyAssertions) {
      VM._assert(false, "Not implemented");
    }
    return null;
  }

}
