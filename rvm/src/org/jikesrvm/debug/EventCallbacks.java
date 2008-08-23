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
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.scheduler.RVMThread;

/**
 * The debug event call back interface.
 * 
 */
public interface EventCallbacks {
  
  public void vmStart();

  public void vmInit(RVMThread t);
  
  public void vmDeath();

  public void threadStart(RVMThread t);
  
  public void threadEnd(RVMThread t);
  
  public void classLoad(RVMThread t, RVMClass c);
  
  public void classPrepare(RVMThread t, RVMClass c);
  
  /**
   * Call back to notify that a Java program hits a break point. This call
   * back happens within the thread that hits the break point. Returning from
   * this call back resumes this thread.
   *
   * @param t The thread.
   * @param method The method.
   * @param bcindex The byte code index.
   */
  public void breakpoint(RVMThread t, NormalMethod method, int bcindex);
  
  public void exception(RVMThread t, NormalMethod method, int bcindex, Throwable e,
      NormalMethod catchMethod, int catchBytecodeIndex);
  
  public void exceptionCatch(RVMThread t, NormalMethod method, int bcindex, Throwable e);
  
  public void singleStep(RVMThread t, NormalMethod method, int bcindex);

}
