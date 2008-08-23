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

import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.util.LinkedListRVM;

/**
 * Breakpoint
 */
public class BreakpointList {

  private final LinkedListRVM<MethodLocation> breakPointList =  
    new LinkedListRVM<MethodLocation>();

  BreakpointList() {}

  public synchronized void setBreakPoint(NormalMethod method, int bcindex) {
    if (!contains(method,bcindex)) {
      BreakpointsImpl.getBreakpointsImpl().requestBreakPoint(method, bcindex);
      MethodLocation location = new MethodLocation(method, bcindex);
      breakPointList.add(location);
    } 
  }

  public synchronized void clearBreakPoint(NormalMethod method, int bcindex) {
    if (contains(method,bcindex)) {
      BreakpointsImpl.getBreakpointsImpl().releaseBreakPoint(method, bcindex);
      MethodLocation location = new MethodLocation(method, bcindex);
      breakPointList.remove(location);
    }
  }

  public synchronized boolean contains(NormalMethod method, int bcindex) {
    for(MethodLocation l : breakPointList) {
      if (l.getMethod() == method && l.getByteCodeIndex() == bcindex) {
        return true;
      }
    }
    return false;
  }
}
