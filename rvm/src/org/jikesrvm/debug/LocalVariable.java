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
import org.jikesrvm.SizeConstants;
import org.jikesrvm.VM;
import org.jikesrvm.ArchitectureSpecific.StackframeLayoutConstants;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.baseline.BaselineCompiledMethod;
import org.jikesrvm.compilers.baseline.ia32.BaselineCompilerImpl;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.opt.runtimesupport.OptCompiledMethod;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.unboxed.Offset;
import org.jikesrvm.debug.StackWalker.CallStackFrameVisitor;

/**
 * A bytecode level Single step implementation.
 */
public final class LocalVariable implements StackframeLayoutConstants{

  private static abstract class LocalVariableVisitor implements CallStackFrameVisitor {
    private int targetFrameDepth;
    protected int targetSlot;
    void start(RVMThread t, int depth, int slot) {
      if (VM.VerifyAssertions) {
        VM._assert(depth >= 0);
      }
      targetFrameDepth = depth;
      targetSlot = slot;
      StackWalker.stackWalk(t, this);
    }
    public boolean visit(int depth, RVMMethod m, int bytecodeIndex,
        CompiledMethod cm, Offset ipOffset, Offset fpOffset, RVMThread t) {
      if (depth == targetFrameDepth) {
        if (cm instanceof BaselineCompiledMethod) {
          visitLocalVariable((BaselineCompiledMethod)cm, fpOffset, t.getStack());
        } else if (cm instanceof OptCompiledMethod) {
          visitLocalVariable((OptCompiledMethod)cm, ipOffset, fpOffset, t.getStack());
        }
        return false;
      } else {
        return true;
      }
    }
    abstract void visitLocalVariable(BaselineCompiledMethod bcm,
        Offset fpOffset, byte[] stack);
    void visitLocalVariable(OptCompiledMethod ocm, Offset ipOffect,
        Offset fpOffet, byte[] stack) {
      if (VM.VerifyAssertions) {
        VM._assert(false, "not implemented");
      }
    }
  }
  
  private static class ObjectReader extends LocalVariableVisitor {
    private Object result;
    Object read(RVMThread t, int depth, int slot) {
      start(t, depth, slot);
      Object o = result;
      result = null;
      return o;
    }
    void visitLocalVariable(BaselineCompiledMethod bcm, Offset fpOffset,
        byte[] stack) {
      result = Magic.getObjectAtOffset(stack, fpOffset.plus(localOffset(bcm,
          targetSlot)));
    }

    Object getResult() {
      return result;
    }
  }

  private static class IntReader extends LocalVariableVisitor {
    private int result;
    int read(RVMThread t, int depth, int slot) {
      start(t, depth, slot);
      return result;
    }
    void visitLocalVariable(BaselineCompiledMethod bcm, Offset fpOffset,
        byte[] stack) {
      result = Magic.getIntAtOffset(stack, fpOffset.plus(localOffset(bcm, targetSlot)));
    }
  }

  private static class LongReader extends LocalVariableVisitor {
    private long result;
    long read(RVMThread t, int depth, int slot) {
      start(t, depth, slot);
      return result;
    }
    public void visitLocalVariable(BaselineCompiledMethod bcm, Offset fpOffset, byte[] stack) {
      result = Magic.getIntAtOffset(stack, fpOffset.plus(localOffset(bcm, targetSlot)));
    }
  }
  
  private static class ObjectWriter extends LocalVariableVisitor {
    Object value;
    void write(RVMThread t, int depth, int slot, Object value) {
      this.value = value;
      start(t, depth, slot);
    }
    public void visitLocalVariable(BaselineCompiledMethod bcm, Offset fpOffset, byte[] stack) {
      Magic.setObjectAtOffset(stack, fpOffset.plus(localOffset(bcm, targetSlot)), value);
    }
  }

  private static class IntWriter extends LocalVariableVisitor {
    int value;
    void write(RVMThread t, int depth, int slot, int value) {
      this.value = value;
      start(t, depth, slot);
    }
    public void visitLocalVariable(BaselineCompiledMethod bcm, Offset fpOffset, byte[] stack) {
      Magic.setIntAtOffset(stack, fpOffset.plus(localOffset(bcm, targetSlot)), value);      
    }
  }

  private static class LongWriter extends LocalVariableVisitor {
    long value;
    void write(RVMThread t, int depth, int slot, long value) {
      this.value = value;
      start(t, depth, slot);
    }
    public void visitLocalVariable(BaselineCompiledMethod bcm, Offset fpOffset, byte[] stack) {
      Magic.setLongAtOffset(stack, fpOffset.plus(localOffset(bcm, targetSlot)), value);
    }
  }
  
  private static ObjectReader objReader = new ObjectReader();
  private static IntReader intReader = new IntReader();
  private static LongReader longReader = new LongReader();
  
  private static ObjectWriter objWriter = new ObjectWriter();
  private static IntWriter intWriter = new IntWriter();
  private static LongWriter longWriter = new LongWriter();
 
  
  private LocalVariable() {}
  
  public static Object getObject(RVMThread thread, int depth, int slot) {
    return objReader.read(thread, depth, slot);
  }
  
  public static int getInt(RVMThread thread, int depth, int slot) {
    return intReader.read(thread, depth, slot);
  }
  
  public static long getLong(RVMThread thread, int depth, int slot) {
    return longReader.read(thread, depth, slot);
  }
  
  public static float getFloat(RVMThread thread, int depth, int slot) {
    return Magic.intBitsAsFloat(intReader.read(thread, depth, slot));
  }
  
  public static double getDouble(RVMThread thread, int depth, int slot) {
    return Magic.longBitsAsDouble(longReader.read(thread, depth, slot));
  }

  public static void setObject(RVMThread thread, int depth, int slot, Object v) {
    objWriter.write(thread, depth, slot, v);
  }
  
  public static void setInt(RVMThread thread, int depth, int slot, int v) {
    intWriter.write(thread, depth, slot, v);
  }

  public static void setLong(RVMThread thread, int depth, int slot, long v) {
    longWriter.write(thread, depth, slot, v);
  }
    
  public static void setFloat(RVMThread thread, int depth, int slot, float v) {
    intWriter.write(thread, depth, slot, Magic.floatAsIntBits(v));
  }

  public static void setDouble(RVMThread thread, int depth, int slot, double v) {
    longWriter.write(thread, depth, slot, Magic.doubleAsLongBits(v));
  }

  /** Get a frame pointer relative offset for a local variable slot. */
  private static int localOffset(BaselineCompiledMethod bcm, int slot) {
    int location = bcm.getGeneralLocalLocation(slot);
    int offset = BaselineCompilerImpl.locationToOffset(location)
        - SizeConstants.BYTES_IN_ADDRESS;
    return offset;
  }
}
