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
package java.lang;

import java.security.ProtectionDomain;
import java.lang.instrument.Instrumentation;

import org.jikesrvm.classloader.VM_Type;
import org.jikesrvm.VM;

import org.vmmagic.pragma.*;

import org.jikesrvm.VM;              // for VerifyAssertions and _assert()
import org.jikesrvm.scheduler.VM_Thread;

/**
 * Library support interface of Jikes RVM
 */
public class JikesRVMSupport {

  public static void initializeInstrumentation(Instrumentation instrumenter) {
    throw new Error("TODO");
  }

  public static Class<?>[] getAllLoadedClasses() {
    throw new Error("TODO");
  }

  public static Class<?>[] getInitiatedClasses(ClassLoader classLoader) {
    throw new Error("TODO");
  }

  public static Class<?> createClass(VM_Type type) {
    throw new Error("TODO");
  }

  public static Class<?> createClass(VM_Type type, ProtectionDomain pd) {
    throw new Error("TODO");
  }

  public static VM_Type getTypeForClass(Class<?> c) {
    throw new Error("TODO");
  }

  public static void setClassProtectionDomain(Class<?> c, ProtectionDomain pd) {
    throw new Error("TODO");
  }

  /***
   * String stuff
   * */

  @Uninterruptible
  public static char[] getBackingCharArray(String str) {
    VM._assert(false);
    return null;
  }

  @Uninterruptible
  public static int getStringLength(String str) {
    VM._assert(false);
    return 0;
  }

  @Uninterruptible
  public static int getStringOffset(String str) {
    VM._assert(false);
    return 0;
  }

  public static String newStringWithoutCopy(char[] data, int offset, int count) {
    throw new Error("TODO");
  }

  /***
   * Thread stuff
   * */
  public static Thread createThread(VM_Thread vmdata, String myName) {
    throw new Error("TODO");
  }

  public static VM_Thread getThread(Thread thread) {
    throw new Error("TODO");
  }

  public static void threadDied(Thread thread) {
    throw new Error("TODO");
  }
  public static Throwable getStillBorn(Thread thread) {
    throw new Error("TODO");
  }
  public static void setStillBorn(Thread thread, Throwable stillborn) {
    throw new Error("TODO");
  }
  /***
   * Enum stuff
   */
  @Uninterruptible
  public static int getEnumOrdinal(Enum<?> e) {
    VM._assert(false);
    return 0;
  }
  @Uninterruptible
  public static String getEnumName(Enum<?> e) {
    VM._assert(false);
    return null;
  }
}
