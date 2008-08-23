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

/**
 * A class managing a list of break points.
 * 
 * TODO: We need to handle GC-safety at each break point.
 * 
 */
public class BreakpointsImpl implements Constants,
  Callbacks.MethodCompileCompleteMonitor, 
  ArchitectureSpecific.StackframeLayoutConstants {

  private static final byte OPCODE_X86_NOP = (byte)0x90;

  private static final byte OPCODE_X86_BREAK_TRAP = (byte)0xcc;

  private static final byte OPCODE_X86_INT = (byte)0xcd;

  private static final byte OPCODE_X86_INT_CODE = (byte) 
    (RuntimeEntrypoints.TRAP_BREAK_POINT + 0x40);

  private static BreakpointsImpl breakPointImpl;
  
  static void boot() {
    if (VM.VerifyAssertions) {
      VM._assert(breakPointImpl == null);
    }
    breakPointImpl = new BreakpointsImpl();
    Callbacks.addMethodCompileCompleteMonitor(breakPointImpl);
  }

  static BreakpointsImpl getBreakpointsImpl() {
    if (VM.VerifyAssertions) {
      VM._assert(breakPointImpl != null);
    }
    return breakPointImpl;
  }

  /** Deliver a break point hit trap event. */
  public static void deliverBreakpointHit(Registers registers) {

    // Locate the break point from the register set.
    // Here, ip points a following instruction right after INT xx.
    final NormalMethod method;
    final int bcIndex;
    VM.disableGC(); //since we are dealing with Address.
    Address ip = registers.getInnermostInstructionAddress();
    Address fp = registers.getInnermostFramePointer();
    int cmid = Magic.getCompiledMethodID(fp);
    CompiledMethod cm = CompiledMethods.getCompiledMethod(cmid);
    if (cmid == INVISIBLE_METHOD_ID) {
      bcIndex = -1;
      method =null;
    } else {
      if (cm instanceof BaselineCompiledMethod) {
        BaselineCompiledMethod bcm = (BaselineCompiledMethod) cm;
        Offset ip_offset = cm.getInstructionOffset(ip);
        bcIndex = bcm.findBytecodeIndexForInstruction(ip_offset);
        method = (NormalMethod) bcm.getMethod();
      } else {
        bcIndex = -1;
        method =null;
      }
    }
    VM.enableGC();

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
          boolean hasSteppingEvent = s.hasSteppingLocation(thread, method, bcIndex);
          s.clearSteppingBreakpoints();
          if (hasSteppingEvent) {
            notifier.notifySingleStep(thread, method, bcIndex);
          }
          BreakpointList bplist = d.breakPoints;
          if (bplist.contains(method, bcIndex)) {
            notifier.notifyBreakpoint(thread, method, bcIndex);
          }
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

    // now resume execution from the break point.
    if (VM.VerifyAssertions) { VM._assert(registers.inuse);}
    registers.inuse = false;
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("break point resuming at ip = ",  VM.addressAsHexString(registers.ip));
    }
    Magic.restoreHardwareExceptionState(registers);
    if (VM.VerifyAssertions) {VM._assert(NOT_REACHED);}
  }

  private final LinkedListRVM<BytecodeBreakPoint> activeByteCodeBreakPoints =
    new LinkedListRVM<BytecodeBreakPoint>();

  private final LinkedListRVM<RawBreakPoint> activeRawBreakPoints =
    new LinkedListRVM<RawBreakPoint>();

  /** Constructor. */
  BreakpointsImpl() {}
  
  /**
   * Set a break point in a method.
   * 
   * @param method The method.
   * @param bcindex The byte code index within the method.
   */
  public synchronized void requestBreakPoint(NormalMethod method, int bcindex) {
    if (VM.VerifyAssertions) {
      VM._assert(method != null && bcindex >= 0);
    }
    if (!isDebuggable(method)) {
      VM.sysWriteln("Not debuggable break point: ", bcindex, method
          .toString());
      return;
    }
    
    BytecodeBreakPoint bp = findByteCodeBreakpoint(method, bcindex);
    if (bp == null) {
      MethodLocation loc = new MethodLocation(method, bcindex);
      bp = new BytecodeBreakPoint(loc, 1);
      ensureByteCodeBreakPointIsActive(bp);
    } else {
      bp.increaseCount();
    }

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("set break point: " + bp);
      if (JikesRVMJDWP.getVerbose() >= 3) {
        dumpActiveBreakPoints();
      }
    }
  }

  /**
   * Clear a break in a method if the break points was not set before.
   * 
   * @param method The method.
   * @param bcindex The byte code index within the method.
   */
  public synchronized void releaseBreakPoint(NormalMethod method, int bcindex) {
    BytecodeBreakPoint bp = findByteCodeBreakpoint(method, bcindex);
    if (VM.VerifyAssertions) {VM._assert(bp != null);}
    bp.decreaseCount();
    if (bp.getCount() == 0 ) {
      ensureByteCodeBreakPointIsDead(bp);
      activeByteCodeBreakPoints.remove(bp);
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("clear break point: " + bp);
      if (JikesRVMJDWP.getVerbose() >= 3) {
        dumpActiveBreakPoints();
      }
    }
  }

  public synchronized void checkBreakpoint(CompiledMethod cm) {
    if (cm instanceof BaselineCompiledMethod) {
      BaselineCompiledMethod bcm = (BaselineCompiledMethod) cm;
      NormalMethod method = (NormalMethod)bcm.getMethod();
      for(final BytecodeBreakPoint jbp: activeByteCodeBreakPoints) {
        if (jbp.getMethod() == method) {
          int mcOffset = bcm.getMachineCodeOffset(jbp.getByteCodeIndex());
          RawBreakPoint rbp = new RawBreakPoint(bcm, mcOffset);
          ensureRawBreakPointIsActive(rbp);
        }
      }
    } else if (cm instanceof OptCompiledMethod) {
      //not implemented.
      if (JikesRVMJDWP.getVerbose() >= 1) {
        VM.sysWriteln("skipping opt compiled method: ", cm
            .getCompilerName(), " in ", cm.getMethod().toString());
      }
    } else if (cm instanceof JNICompiledMethod) {
      //ignore - no break point at the native method.
    } else {
      if (VM.VerifyAssertions) {
        VM._assert(false, "Unknown compiler: "
            + cm.getCompilerName() + " in "
            + cm.getMethod().toString());
      }
    }
  }

  public void notifyMethodCompileComplete(CompiledMethod cm) {
    checkBreakpoint(cm);
  }

  private synchronized BytecodeBreakPoint findByteCodeBreakpoint(
      NormalMethod method, int bcindex) {
    for(BytecodeBreakPoint bp : activeByteCodeBreakPoints) {
      if (bp.getMethod() == method && bp.getByteCodeIndex() == bcindex) {
        return bp;
      }
    }
    return null;
  }

  private boolean isDebuggable(NormalMethod m) {
    RVMClass cls = m.getDeclaringClass();
    if (cls.getDescriptor().isRVMDescriptor()) {return false;}
    return true;
  }

  private void ensureByteCodeBreakPointIsActive(BytecodeBreakPoint jbp) {
    //collect a set of raw break points.
    for(final RawBreakPoint rbp: getRawBreakPoints(jbp)) {
      ensureRawBreakPointIsActive(rbp);
    }
    if (!isActive(jbp)) {
      activeByteCodeBreakPoints.add(jbp);
    }
  }

  private void ensureByteCodeBreakPointIsDead(BytecodeBreakPoint jbp) {
    if (!isActive(jbp)) {return;}

    // remove the set of raw break point for the jbp.
    for(final RawBreakPoint bp: getRawBreakPoints(jbp)) {
      ensureRawBreakPointIsDead(bp);
    }

    // remove the jbp. 
    activeByteCodeBreakPoints.remove(jbp);
  }

  private boolean isActive(BytecodeBreakPoint bp) {
    for(final BytecodeBreakPoint abp : activeByteCodeBreakPoints) {
      if (abp.equals(bp)) {return true;}
    } 
    return false;
  }
  private boolean isActive(RawBreakPoint bp) {
    for(final RawBreakPoint abp : activeRawBreakPoints) {
      if (abp.equals(bp)) {return true;}
    } 
    return false;
  }

  private LinkedListRVM<RawBreakPoint> getRawBreakPoints(BytecodeBreakPoint jbp) {
    //collect a set of raw break points.
    LinkedListRVM<RawBreakPoint> rawBPList = new LinkedListRVM<RawBreakPoint>();
    NormalMethod method = jbp.getMethod();
    //handle baseline code.
    CompiledMethod cm = method.getCurrentCompiledMethod();
    if (cm instanceof BaselineCompiledMethod) {
      BaselineCompiledMethod bcm = (BaselineCompiledMethod)cm;
      int mcOffset = bcm.getMachineCodeOffset(jbp.getByteCodeIndex());
      rawBPList.add(new RawBreakPoint(bcm, mcOffset));
    }
    //handle opt code - not implemented

    return rawBPList;
  }

  private void ensureRawBreakPointIsActive(RawBreakPoint bp) {
    if (isActive(bp)) { return;}
    CompiledMethod cm = bp.getCompiledMethod();
    if (cm instanceof BaselineCompiledMethod) {
      BaselineCompiledMethod bcm = (BaselineCompiledMethod)cm;
      int mcOffset = bp.getOffset();
      int bcindex = bcm.findBytecodeIndexForInstruction(Offset.fromIntZeroExtend(mcOffset));
      CodeArray codes = bcm.getEntryCodeArray();
      byte inst = codes.get(mcOffset);
      if (inst == OPCODE_X86_NOP) {
        codes.set(mcOffset, OPCODE_X86_INT);
        codes.set(mcOffset+1, OPCODE_X86_INT_CODE);
        activeRawBreakPoints.add(bp);
        if (JikesRVMJDWP.getVerbose() >= 3) {
          VM.sysWriteln("inserted baseline raw break point: " + bp);
        }
      } else if (inst == OPCODE_X86_INT) {
        // the break point is set already.
        VM.sysFail("break point was set before: " +  bp);
      } else {
        VM.sysFail("can not set break point" + bcindex + ") in "
            + bcm.getMethod().toString());
      }
    } else if (cm instanceof OptCompiledMethod) {
      if (JikesRVMJDWP.getVerbose() >= 3) {
        VM.sysWriteln("skipping opt compiled raw break point in "
            + cm.getMethod());
      }
    } else {
      if (VM.VerifyAssertions) {
        VM._assert(false,
            "raw break point request should be either basline or opt: "
                + cm.getCompilerName());
      }
    }
  }

  private void ensureRawBreakPointIsDead(RawBreakPoint bp) {
    if (!isActive(bp)) {return;}
    CompiledMethod cm = bp.getCompiledMethod();
    if (cm instanceof BaselineCompiledMethod) {
      BaselineCompiledMethod bcm = (BaselineCompiledMethod)cm;
      int mcOffset = bp.getOffset();
      int bcindex = bcm.findBytecodeIndexForInstruction(Offset.fromIntZeroExtend(mcOffset));
      CodeArray codes = bcm.getEntryCodeArray();
      byte inst = codes.get(mcOffset);
      if (inst == OPCODE_X86_BREAK_TRAP) {
        codes.set(mcOffset, OPCODE_X86_NOP);
        codes.set(mcOffset+1, OPCODE_X86_NOP);
        activeRawBreakPoints.remove(bp);
        if (JikesRVMJDWP.getVerbose() >= 3) {
          VM.sysWriteln("removed baseline raw break point at " + mcOffset + 
              " in " + bcm.getMethod());
        }
      } else if (inst == OPCODE_X86_NOP) {
        // the break point is set already.
        VM.sysFail("break point was not set before " + bcindex
            + ") in the base line method of " + bcm.getMethod().toString());
      } else {
        VM.sysFail("can not reset break point" + bcindex + ") in "
            + bcm.getMethod().toString());
      }
    } else if (cm instanceof OptCompiledMethod) {
      throw new UnimplementedError("OptCompiledMethod break point is not implemented.");
    } else {
      if (VM.VerifyAssertions) {
        VM._assert(false,
            "raw break point request should be either basline or opt: "
                + cm.getCompilerName());
      }
    }
  }

  /** For debugging, dump the currently active break points. */
  private void  dumpActiveBreakPoints() {
    VM.sysWriteln("Breakpoint dump");
    VM.sysWriteln("  Byte code break points: ");
    for(final BytecodeBreakPoint jbp: activeByteCodeBreakPoints) {
      VM.sysWriteln(jbp.toString());
    }
    VM.sysWriteln("  Raw machine code break points: ");
    for(final RawBreakPoint rbp: activeRawBreakPoints) {
      VM.sysWriteln(rbp.toString());
    }
  }
}

/** A break point at the byte code level. */
class BytecodeBreakPoint {
  private final MethodLocation location;
  private int count;

  /**
   * @param m The method.
   * @param bcindex The byte code Index;
   */
  public BytecodeBreakPoint(MethodLocation location, int count) {
    if (VM.VerifyAssertions) {
      VM._assert(location != null);
    }
    this.location = location;
    this.count = count;
  }
  MethodLocation getLocation() {return location;}
  NormalMethod getMethod() { return location.getMethod();}
  int getByteCodeIndex() {return location.getByteCodeIndex();}
  int getCount() {return count;}
  void setCount(int count) {this.count = count;}
  void increaseCount() {this.count++;}
  void decreaseCount() {
    this.count--;
    if (VM.VerifyAssertions) {
      VM._assert(count >= 0);
    }
  }
  public String toString() {
    return location.toString() + " count = " + count;
  }
}

/** A break point at the machine code level. */
final class RawBreakPoint {
  private final CompiledMethod compiledMethod;
  private final int offset;
  /**
   * @param cm The compiled method.
   * @param bpOffset The machine code offset;
   */
  public RawBreakPoint(CompiledMethod cm, int bpOffset) {
    if (VM.VerifyAssertions) {
      VM._assert(cm != null);
      VM._assert(bpOffset >= 0);
      VM._assert(bpOffset < cm.numberOfInstructions());
    }
    this.offset = bpOffset;
    this.compiledMethod = cm;
  }
  @Override
  public int hashCode() {
    return offset + compiledMethod.hashCode();
  }
  @Override
  public boolean equals(Object obj) {
    if (obj instanceof RawBreakPoint) {
      RawBreakPoint bp = (RawBreakPoint)obj;  
      return (offset == bp.offset)
          && (compiledMethod.equals(bp.compiledMethod)); 
    } else {
      return false;         
    }
  }
  public String toString() {
    RVMMethod method = compiledMethod.getMethod();
    return "RawBreakPoint: mcoffset = " + offset + " compiler = "
        + compiledMethod.getCompilerName()  + " in "
        + method.getDeclaringClass().toString() + "." + method.getName()
        + method.getDescriptor();
  }
  CompiledMethod getCompiledMethod() {
    return compiledMethod;
  }
  int getOffset() {
    return offset;
  }
}
