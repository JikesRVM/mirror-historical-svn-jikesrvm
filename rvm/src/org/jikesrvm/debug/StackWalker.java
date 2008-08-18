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
import org.jikesrvm.ArchitectureSpecific.StackframeLayoutConstants;
import org.jikesrvm.classloader.MemberReference;
import org.jikesrvm.classloader.MethodReference;
import org.jikesrvm.classloader.NativeMethod;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.baseline.BaselineCompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.compilers.opt.runtimesupport.OptCompiledMethod;
import org.jikesrvm.compilers.opt.runtimesupport.OptEncodedCallSiteTree;
import org.jikesrvm.compilers.opt.runtimesupport.OptMachineCodeMap;
import org.jikesrvm.jni.JNICompiledMethod;
import org.jikesrvm.memorymanagers.mminterface.MM_Interface;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.Scheduler;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;

/**
 * A call stack frame walker.
 */
public class StackWalker implements StackframeLayoutConstants{
  private static boolean DEBUG = false;
  
  /** Stack frame walk call back interface. */
  static interface CallStackFrameVisitor {
    abstract boolean visit(int depth, RVMMethod m, int bytecodeIndex,
        CompiledMethod cm, Offset ipOffset, Offset fpOffset, RVMThread t);
  }

  /**
   * Walk the call frames in the suspended thread. If the thread is suspended,
   * visit call frames.
   */
  static void stackWalk(RVMThread t, CallStackFrameVisitor v) {
    if (VM.VerifyAssertions) {
      VM._assert(t.isAlive() && t.getState() == Thread.State.WAITING);
      VM._assert(Scheduler.getCurrentThread() != t);
    }
    int depth = 0;
    Address fp = t.contextRegisters.getInnermostFramePointer();
    Address ip = t.contextRegisters.getInnermostInstructionAddress();
    while (Magic.getCallerFramePointer(fp).NE(STACKFRAME_SENTINEL_FP)) {
      if (!MM_Interface.addressInVM(ip)) {
        // skip the nativeframes until java frame or the end.
        while (!MM_Interface.addressInVM(ip) && !fp.NE(STACKFRAME_SENTINEL_FP)) {
          ip = Magic.getReturnAddress(fp);
          fp = Magic.getCallerFramePointer(fp);
        }
        if (VM.BuildForPowerPC) {
          // skip over main frame to mini-frame
          fp = Magic.getCallerFramePointer(fp);
        }
      } else {
        int cmid = Magic.getCompiledMethodID(fp);
        if (cmid == INVISIBLE_METHOD_ID) {
          // skip
        } else {
          CompiledMethod cm = CompiledMethods.getCompiledMethod(cmid);
          if (VM.VerifyAssertions) {
            VM._assert(cm != null, "no compiled method for cmid =" + cmid
                + " in thread " + t.getName());
          }
          int compilerType = cm.getCompilerType();
          switch (compilerType) {
            case CompiledMethod.TRAP: {
              // skip since this is an artificial frame, and there is no
              // corresponding Java code.
              if (DEBUG) {showHardwareTrapMethod(fp);}
              break;
            }
            case CompiledMethod.BASELINE: {
              BaselineCompiledMethod bcm = (BaselineCompiledMethod) cm;
              NormalMethod meth = (NormalMethod) cm.getMethod();
              Offset ipOffset = bcm.getInstructionOffset(ip, false);
              int bci = bcm.findBytecodeIndexForInstruction(ipOffset);
              Address stackbeg = Magic.objectAsAddress(t.getStack());
              Offset fpOffset = fp.diff(stackbeg);
              if (DEBUG) {showMethod(meth,bci,ip,fp);}
              if (isVisiableMethod(meth)) {
                if (!v.visit(depth++, meth, bci, bcm, ipOffset, fpOffset, t)) {
                  return; // finish frame walk.
                }
              }
              break;
            }
            case CompiledMethod.OPT: {
              final Address stackbeg = Magic.objectAsAddress(t.getStack());
              final Offset fpOffset = fp.diff(stackbeg);
              OptCompiledMethod ocm = (OptCompiledMethod) cm;
              Offset ipOffset = ocm.getInstructionOffset(ip, false);
              OptMachineCodeMap m = ocm.getMCMap();
              int bci = m.getBytecodeIndexForMCOffset(ipOffset);
              NormalMethod meth = m.getMethodForMCOffset(ipOffset);
              int iei = m.getInlineEncodingForMCOffset(ipOffset);
              if (JikesRVMJDWP.getVerbose() >= 4) {showMethod(meth,bci,ip,fp);}
              if (isVisiableMethod(meth)) {
                if (!v.visit(depth++, meth, bci, ocm, ipOffset, fpOffset, t)) {
                  return;
                }
              }
              // visit more inlined call sites.
              int[] e = m.inlineEncoding;
              for (iei = OptEncodedCallSiteTree.getParent(iei, e); 
                   iei >= 0;
                   iei = OptEncodedCallSiteTree.getParent(iei, e)) {
                int mid = OptEncodedCallSiteTree.getMethodID(iei, e);
                MethodReference mref = MemberReference.getMemberRef(mid)
                    .asMethodReference();
                meth = (NormalMethod) mref.getResolvedMember();
                bci = OptEncodedCallSiteTree.getByteCodeOffset(iei, e);
                if (DEBUG) {
                  showMethod(meth, bci, ip, fp);
                }
                if (isVisiableMethod(meth)) {
                  if (!v.visit(depth++, meth, bci, ocm, ipOffset, fpOffset, t)) {
                    return;
                  }
                }
              }
              break;
            }
            case CompiledMethod.JNI: {
              JNICompiledMethod jcm = (JNICompiledMethod) cm;
              NativeMethod meth = (NativeMethod) jcm.getMethod();
              Offset ipOffset = jcm.getInstructionOffset(ip, false);
              Address stackbeg = Magic.objectAsAddress(t.getStack());
              Offset fpOffset = fp.diff(stackbeg);
              if (isVisiableMethod(meth)) {
                if (!v.visit(depth++, meth, -1, jcm, ipOffset, fpOffset, t)) {
                  return;
                }
              }
              break;
            }
            default: {
              if (VM.VerifyAssertions) {
                VM._assert(false, "can not recognize compiler type "
                    + compilerType);
              }
              break;
            }
          }
        }
        ip = Magic.getReturnAddress(fp);
        fp = Magic.getCallerFramePointer(fp);
      }
    }
  }

  /** Wheather or not visible method to the user. */
  private static boolean isVisiableMethod(RVMMethod m) {
    RVMClass cls = m.getDeclaringClass();
    return !cls.getDescriptor().isBootstrapClassDescriptor();
  }

  /** Print the hardware trap frame. */
  @Uninterruptible
  private static void showHardwareTrapMethod(Address fp) {
    VM.sysWrite("   at [fp ");
    VM.sysWrite(fp);
    VM.sysWriteln("] Hardware trap");
  }

  /** Print a stack frame for the native method. */
  @Uninterruptible
  private static void showMethod(NativeMethod method, Address ip, Address fp) {
    showPrologue(ip, fp);
    if (method == null) {
      VM.sysWrite("<unknown method>");
    } else {
      VM.sysWrite(method.getDeclaringClass().getDescriptor());
      VM.sysWrite(" ");
      VM.sysWrite(method.getName());
      VM.sysWrite(method.getDescriptor());
    }
    VM.sysWrite("\n");
  }
  

  /** Print the beginning of call frame. */
  @Uninterruptible
  private static void showPrologue(Address ip, Address fp) {
    VM.sysWrite("   at [ip = ");
    VM.sysWrite(ip);
    VM.sysWrite(", fp = ");
    VM.sysWrite(fp);
    VM.sysWrite("] ");
  }

  /** Print a stack frame for the Java method. */
  @Uninterruptible
  private static void showMethod(RVMMethod method, int bcindex, Address ip,
      Address fp) {
    showPrologue(ip, fp);
    if (method == null) {
      VM.sysWrite("<unknown method>");
    } else {
      VM.sysWrite(method.getDeclaringClass().getDescriptor());
      VM.sysWrite(" ");
      VM.sysWrite(method.getName());
      VM.sysWrite(method.getDescriptor());
    }
    if (bcindex >= 0) {
      VM.sysWrite(" at bcindex ");
      VM.sysWriteInt(bcindex);
    }
    VM.sysWrite("\n");
  }
}
