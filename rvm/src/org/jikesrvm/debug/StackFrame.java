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
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.unboxed.Offset;
import org.jikesrvm.debug.StackWalker.CallStackFrameVisitor;

/**
 * A Call stack frame reflector.
 */
public final class StackFrame implements StackframeLayoutConstants{
  
  public static class FrameInfoList {
    private final RVMMethod[] methods;
    private final int[] locations;
    private int count;    
    public FrameInfoList(int maxCount) {
      methods = new RVMMethod[maxCount];
      locations = new int[maxCount];
      count = 0;
    }
    public RVMMethod getMethod(int depth) {
      return methods[depth];
    }
    public int getLocation(int depth) {
      return locations[depth];
    }
    void append(RVMMethod method, int location) {
      if (VM.VerifyAssertions) {
        VM._assert(count < methods.length);
      }
      int pos = count++;
      methods[pos] = method;
      locations[pos] = location;
    }
    public int getMaxCount() {
      return methods.length;
    }
    public int getCount() {
      return count;
    }
  }

  private static class FrameCounter implements CallStackFrameVisitor {
    private int count;
    public int count(RVMThread t) {
      count = 0;
      StackWalker.stackWalk(t, this);
      return count;
    }
    public boolean visit(int frameno, RVMMethod m, int bytecodeIndex,
        CompiledMethod cm, Offset ipOffset, Offset fpOffset, RVMThread t) {
      count++;
      return true;
    }
  };

  private static class FrameArrayExtractor implements CallStackFrameVisitor {
    /** The extracted frames. */
    private FrameInfoList frames;
    private int countDown; 
    void extract(RVMThread t, FrameInfoList frames, int start) {
      if (VM.VerifyAssertions) {
        VM._assert(frames != null && start >= 0);
      }
      this.frames = frames;
      this.countDown = start;
      StackWalker.stackWalk(t, this);
    }
    public boolean visit(int frameno, RVMMethod m, int bytecodeIndex,
        CompiledMethod cm, Offset ipOffset, Offset fpOffset, RVMThread t) {
      if (countDown > 0) {
        countDown--;
        return true;
      } else if (frames.getCount() < frames.getMaxCount()) {
        frames.append(m, bytecodeIndex);
        return true;
      } else {
        return false;
      }
    }
  }

  private static FrameCounter frameCounter = new FrameCounter();
  private static FrameArrayExtractor frameInfoExtractor = new FrameArrayExtractor();

  /** Dump call stack frames in a suspended thread. */
  public static void getFrames(RVMThread thread,
      FrameInfoList frames, final int start) {
    if (VM.VerifyAssertions) {
      VM._assert(frames != null && start >= 0);
    }
    frameInfoExtractor.extract(thread, frames, start);
  }

  /** Count the number of call frames in a suspended thread. */
  public static int getFrameCount(final RVMThread thread) {
    frameCounter.count(thread);
    return frameCounter.count;
  }

  private StackFrame() {} //no instance.
}
