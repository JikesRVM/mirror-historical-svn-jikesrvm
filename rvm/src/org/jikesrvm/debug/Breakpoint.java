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

/**
 * Breakpoint
 */
public class Breakpoint {
  public static void setBreakPoint(NormalMethod method, int bcindex) {
    BreakpointManager.setBreakPoint(method, bcindex);
  }
  public static void clearBreakPoint(NormalMethod method, int bcindex) {
    BreakpointManager.clearBreakPoint(method, bcindex);
  }
}
