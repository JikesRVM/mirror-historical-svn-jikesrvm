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
import org.jikesrvm.debug.RVMDebug.EventType;
import org.jikesrvm.jni.JNICompiledMethod;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.runtime.RuntimeEntrypoints;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.Scheduler;
import org.jikesrvm.util.LinkedListRVM;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.BaselineSaveLSRegisters;
import org.vmmagic.pragma.NoOptCompile;

/** 
 * Raw break point bit map.
 */
public final class Breakpoints {

  private static final boolean DEBUG = false;

  /**
   * Array of break point flags.
   */
  @Entrypoint
  private static int[][] flags;

  public static synchronized void ensureFlags(NormalMethod m) {
    if (!available(m)) {
      allocateFlags(m);
    } 

  }
  private static synchronized void allocateFlags(NormalMethod m) {
    allocateFlags(m.getId(), m.getBytecodeLength());
  }

  private static synchronized void allocateFlags(int id, int numEntries) {
    if (flags == null) {
      flags = new int[id + 500][];
    }
    if (id >= flags.length) {
      int newSize = flags.length * 2;
      if (newSize <= id) newSize = id + 500;
      int[][] tmp = new int[newSize][];
      System.arraycopy(flags, 0, tmp, 0, flags.length);
      Magic.sync();
      flags = tmp;
    }
    flags[id] = new int[numEntries];
  }

  public static synchronized void setBreakpoint(NormalMethod m, int bcindex) {
    ensureFlags(m);
    flags[m.getId()][bcindex] = 1;
  }
  public static synchronized void clearBreakpoint(NormalMethod m, int bcindex) {
    if (available(m)) {
      flags[m.getId()][bcindex] = 0;
    }
  }

  public static synchronized boolean isBreakpointSet(NormalMethod m, int bcindex) {
    return available(m) && flags[m.getId()][bcindex] == 1;
  }

  private static synchronized boolean available(NormalMethod m) {
    int id = m.getId();
    return flags != null && id < flags.length && flags[id] != null;
  }

  /** Note that the call to this method is asynchronous. */
  @BaselineSaveLSRegisters
  @NoOptCompile
  @Entrypoint
  public static void breakPointHit() {
    // Locate the break point from the register set.
    final NormalMethod method;
    final int bcIndex;

    if (DEBUG) {VM.sysWriteln("breakPointHit: finding location");}
    VM.disableGC(); //since we are dealing with Address.
    Address ip = Magic.getReturnAddress(Magic.getFramePointer());
    Address fp = Magic.getCallerFramePointer(Magic.getFramePointer());
    int cmid = Magic.getCompiledMethodID(fp);
    CompiledMethod cm = CompiledMethods.getCompiledMethod(cmid);
    if (cm instanceof BaselineCompiledMethod) {
      BaselineCompiledMethod bcm = (BaselineCompiledMethod) cm;
      Offset ip_offset = cm.getInstructionOffset(ip);
      bcIndex = bcm.findBytecodeIndexForInstruction(ip_offset);
      method = (NormalMethod) bcm.getMethod();
    } else {
      bcIndex = -1;
      method =null;
      if (VM.VerifyAssertions) {
        VM._assert(false, "Unexpected caller to the breakpoint hit handler");
      }
    }
    VM.enableGC();

    if (DEBUG) {VM.sysWriteln("breakPointHit:  location found");}

    // Notify the break point hit through a call back.
    if (method != null && bcIndex >= 0) {
      if (Scheduler.getCurrentThread().isSystemThread()) {
        if (JikesRVMJDWP.getVerbose() >= 3) {
          VM.sysWriteln("skipping a system thread's break point hit: bcindex ",
              bcIndex, method.toString());
        }
      } else {
        try {
          RVMDebug d = RVMDebug.getRVMDebug();
          RVMDebugState s = d.eventRequest;
          EventNotifier notifier = d.eventNotifier;
          RVMThread thread = Scheduler.getCurrentThread();
          if (DEBUG) {VM.sysWriteln("breakPointHit:  notifying the event.");}
          notifier.notifyBreakpoint(thread, method, bcIndex);
        } catch(Exception t) {
          VM._assert(false,
              "The break point call back should handle all the exception");
        }
      }
    } else {
      if (VM.VerifyAssertions) {
        VM._assert(false, "a break point trap from unknown source");
      }
    }
  }
}
