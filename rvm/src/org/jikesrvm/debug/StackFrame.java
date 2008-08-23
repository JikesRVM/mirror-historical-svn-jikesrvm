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
    
    /** Pre-allocated frame locations. */
    private final FrameLocation[] framLocations;
    
    /** Number of frame locations. */
    private int count;
    
    public FrameInfoList(int maxCount) {
      framLocations = new FrameLocation[maxCount];
      for(int i=0; i < maxCount;i++) {
        framLocations[i] = new FrameLocation();
      }
      count = 0;
    }

    /* Public getter methods. */
    public RVMMethod getMethod(int depth) {
      return framLocations[depth].getMethod();
    }
    public int getLocation(int depth) {
      return framLocations[depth].getLocation();
    }
    public int getCount() {return count;}

    /* Private internal methods. */
    private void append(RVMMethod method, int location) {
      if (VM.VerifyAssertions) {
        VM._assert(count < framLocations.length);
      }
      int pos = count++;
      framLocations[pos].setLocation(location);
      framLocations[pos].setMethod(method);
    }
    private int getMaxCount() {return framLocations.length;}
  }

  public static class FrameLocation {
    private RVMMethod method;
    private int location;
    private FrameLocation() {}
    
    /* private setters. */
    private void setMethod(RVMMethod method) {
      this.method = method;
    }
    private void setLocation(int location) {
      this.location = location;
    }

    /* public getters. */
    public RVMMethod getMethod() {return method;}
    public int getLocation() {return location;}
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

  /** Dump call stack frames in a suspended thread. */
  public static void getFrames(RVMThread thread,
      FrameInfoList frames, final int start) {
    if (VM.VerifyAssertions) {
      VM._assert(frames != null && start >= 0);
    }
    FrameArrayExtractor frameInfoExtractor = new FrameArrayExtractor();
    frameInfoExtractor.extract(thread, frames, start);
  }

  /** Count the number of call frames in a suspended thread. */
  public static int getFrameCount(final RVMThread thread) {
    FrameCounter frameCounter = new FrameCounter();
    frameCounter.count(thread);
    return frameCounter.count;
  }
  
  /** Get the location information for a frame. */
  public static void getFrameLocation(RVMThread thread, int depth) {
    if (VM.VerifyAssertions) {
      VM._assert(false, "Not implemented");
      VM._assert(depth >= 0 && thread != null);
    }
  }

  private StackFrame() {} //no instance.
}
