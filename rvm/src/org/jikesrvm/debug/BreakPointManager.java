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

import org.jikesrvm.Callbacks;
import org.jikesrvm.UnimplementedError;
import org.jikesrvm.VM;
import org.jikesrvm.ArchitectureSpecific.CodeArray;
import org.jikesrvm.Callbacks.MethodCompileCompleteMonitor;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.baseline.BaselineCompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.opt.runtimesupport.OptCompiledMethod;
import org.jikesrvm.jni.JNICompiledMethod;
import org.jikesrvm.runtime.RuntimeEntrypoints;
import org.jikesrvm.scheduler.Scheduler;
import org.jikesrvm.util.LinkedListRVM;
import org.vmmagic.unboxed.Offset;

/**
 * A class managing a list of break points.
 * 
 * TODO: We need to handle GC-safety at each break point.
 * 
 */
public class BreakPointManager implements MethodCompileCompleteMonitor {

  private static final byte OPCODE_X86_NOP = (byte)0x90;

  private static final byte OPCODE_X86_BREAK_TRAP = (byte)0xcc;

  private static final byte OPCODE_X86_INT = (byte)0xcd;

  private static final byte OPCODE_X86_INT_CODE = (byte) 
    (RuntimeEntrypoints.TRAP_BREAK_POINT + 0x40);

  private static BreakPointManager manager; 

  /**
   * Initialize break point feature.
   * @param m The break point call back.
   */
  public static void init(BreakPointMonitor m) {
    if (VM.VerifyAssertions) {
      VM._assert(m != null);
    }
    manager = new BreakPointManager(m);
  }

  /** Get singleton break point manager. */
  public static BreakPointManager getBreakPointManager() {
    if (VM.VerifyAssertions) {
      VM._assert(manager != null);
    }
    return manager;
  }

  /**
   * notify break point hit to the call back target. 
   * 
   * @param method The method that hits the break point.
   * @param bcindex The byte code index.
   */
  public static void deliverBreakPointHit(NormalMethod method, int bcindex) {
    if (VM.VerifyAssertions) {
      VM._assert(manager != null);
    }
    
    if (Scheduler.getCurrentThread().isSystemThread()) {
      if (JikesRVMJDWP.getVerbose() >= 3) {
        VM.sysWriteln("skipping a system thread's break point hit: bcindex ",
            bcindex, method.toString());
      }
    }      
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("firing a break point hit: bcindex ", bcindex, method.toString() );
    }
    manager.breakPointMonitor.notifyBreakPointHit(method, bcindex);
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("resuming from the point hit: bcindex ", bcindex, method.toString() );
    }
  }
  
  private final LinkedListRVM<BytecodeBreakPoint> activeByteCodeBreakPoints =
    new LinkedListRVM<BytecodeBreakPoint>();
  
  private final LinkedListRVM<RawBreakPoint> activeRawBreakPoints =
    new LinkedListRVM<RawBreakPoint>();

  /** The call back to the break point hit listener. */
  private final BreakPointMonitor breakPointMonitor;

  /** Constructor.*/
  private BreakPointManager(BreakPointMonitor breakPointMonitor) {
    this.breakPointMonitor = breakPointMonitor;
    Callbacks.addMethodCompileCompleteMonitor(this);
  }

  /**
   * Set a break point in a method.
   * 
   * @param method The method.
   * @param bcindex The byte code index within the method.
   */
  public void setBreakPoint(NormalMethod method, int bcindex) {
    if (VM.VerifyAssertions) {
      VM._assert(manager != null && method != null && bcindex >= 0);
    }
    if (!isDebuggable(method)) {
      VM.sysWriteln("Not debuggable break point: ", bcindex, method
          .toString());
      return;
    }
  
    BytecodeBreakPoint bp = new BytecodeBreakPoint(method, bcindex);
    ensureByteCodeBreakPointIsActive(bp);
    
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
  public void clearBreakPoint(NormalMethod method, int bcindex) {
    if (VM.VerifyAssertions) {
      VM._assert(manager != null);
    }
    BytecodeBreakPoint bp = new BytecodeBreakPoint(method, bcindex);
    ensureByteCodeBreakPointIsDead(bp);
    
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("clear break point: " + bp);
      if (JikesRVMJDWP.getVerbose() >= 3) {
        dumpActiveBreakPoints();
      }
    }
  }

  public void notifyMethodCompileComplete(CompiledMethod cm) {
    if (cm instanceof BaselineCompiledMethod) {
      BaselineCompiledMethod bcm = (BaselineCompiledMethod) cm;
      NormalMethod method = (NormalMethod)bcm.getMethod();
      for(final BytecodeBreakPoint jbp: activeByteCodeBreakPoints) {
        if (jbp.method == method) {
          int mcOffset = bcm.getMachineCodeOffset(jbp.bcindex);
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

  private static boolean isDebuggable(NormalMethod m) {
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

  private static LinkedListRVM<RawBreakPoint> getRawBreakPoints(BytecodeBreakPoint jbp) {
    //collect a set of raw break points.
    LinkedListRVM<RawBreakPoint> rawBPList = new LinkedListRVM<RawBreakPoint>();
    NormalMethod method = jbp.method;
    //handle baseline code.
    CompiledMethod cm = method.getCurrentCompiledMethod();
    if (cm instanceof BaselineCompiledMethod) {
      BaselineCompiledMethod bcm = (BaselineCompiledMethod)cm;
      int mcOffset = bcm.getMachineCodeOffset(jbp.bcindex);
      rawBPList.add(new RawBreakPoint(bcm, mcOffset));
    }
    
    //handle opt code - not implemented
    
    return rawBPList;
  }

  private void ensureRawBreakPointIsActive(RawBreakPoint bp) {
    if (isActive(bp)) { return;}
    CompiledMethod cm = bp.compiledMethod;
    if (cm instanceof BaselineCompiledMethod) {
      BaselineCompiledMethod bcm = (BaselineCompiledMethod)cm;
      int mcOffset = bp.offset;
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
    CompiledMethod cm = bp.compiledMethod;
    if (cm instanceof BaselineCompiledMethod) {
      BaselineCompiledMethod bcm = (BaselineCompiledMethod)cm;
      int mcOffset = bp.offset;
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
  
  
  private void dumpActiveBreakPoints() {
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

  /**
   * The call back interface.
   */
  public interface BreakPointMonitor {
    /**
     * Call back to notify that a Java program hits a break point. This call
     * back happens within the thread that hits the break point. Returning from
     * this call back resumes this thread.
     * 
     * @param method The method.
     * @param bcindex The bytecode index.
     */
    public void notifyBreakPointHit(NormalMethod method, int bcindex);
  }

  /** A break point at the byte code level. */
  private static class BytecodeBreakPoint {
    private final NormalMethod method;
    private final int bcindex;
    /**
     * @param m The method.
     * @param bcindex The byte code Index;
     */
    public BytecodeBreakPoint(NormalMethod m, int bcindex) {
      if (VM.VerifyAssertions) {
        VM._assert(m != null);
        VM._assert(bcindex >= 0);
        VM._assert(bcindex < m.getBytecodeLength());
      }
      this.bcindex = bcindex;
      this.method = m;
    }
    @Override
    public int hashCode() {
      return bcindex + method.hashCode();
    }
    @Override
    public boolean equals(Object obj) {
      if (obj instanceof BytecodeBreakPoint) {
        BytecodeBreakPoint bp = (BytecodeBreakPoint)obj;  
        return (bcindex == bp.bcindex) && (method.equals(bp.method)); 
      } else {
        return false;         
      }
    }
    public String toString() {
      return "JavaBreakPoint: bcindex = " + bcindex + " in "
          + method.getDeclaringClass().toString() + "." + method.getName()
          + method.getDescriptor();
    }
  }
  
  /** A break point at the machine code level. */
  private static final class RawBreakPoint {
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
  }
}
