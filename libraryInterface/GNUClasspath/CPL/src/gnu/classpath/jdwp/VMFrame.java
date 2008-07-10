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
package gnu.classpath.jdwp;

import org.jikesrvm.classloader.NativeMethod;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.baseline.BaselineCompiledMethod;
import org.jikesrvm.compilers.opt.runtimesupport.OptCompiledMethod;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.unboxed.Offset;

import gnu.classpath.jdwp.exception.JdwpException;
import gnu.classpath.jdwp.exception.NotImplementedException;
import gnu.classpath.jdwp.util.Location;
import gnu.classpath.jdwp.value.Value;

/** JikesRVM Specific implementation of VMFrame. */
public abstract class VMFrame {

  /** The size of frameID is 8 (long). */
  public static final int SIZE = 8;

  /** The current location of this frame.*/
  private final Location loc;

  /**
   * The unique identifier within the VM.
   * 
   * "Uniquely identifies a frame in the target VM. The frameID must uniquely
   * identify the frame within the entire VM (not only within a given thread).
   * The frameID need only be valid during the time its thread is suspended."
   * [http://java.sun.com/javase/6/docs/technotes/guides/jpda/jdwp-spec.html]
   * 
   * The upper 32 bit is the thread identifier, and the lower 32 bit is the
   * frame number from 0.
   */
  private final long frameID;

  /** The owner thread. */
  protected final RVMThread thread;

  /** Constructor. */
  public VMFrame(int fno, Location loc, RVMThread thread) {
    this.thread = thread;
    this.frameID = ((long)thread.getIndex()) << 32 | fno;
    this.loc = loc;
  }

  /** Getters */
  public Location getLocation() { return loc;}
  public long getId() { return frameID;}

  /** Read a local variable in a frame. */
  public abstract Value getValue(int slot, byte sig) throws JdwpException;

  /** Write a local variable in a frame. */
  public abstract void setValue(int slot, Value value)throws JdwpException;

  /** Read a this variable in a frame. */
  public abstract Object getObject() throws JdwpException;
}

/** The internal stack frame from the base line compiler. */
final class VMBaseFrame extends VMFrame {

  /** The baseline compiled method.*/
  private final BaselineCompiledMethod bcm;

  /** The machine instruction offset in the base line compiled method code. */
  private final Offset ipOffset;

  /** The frame point offset in the current thread's call stack.*/
  private final Offset fpOffset;

  /** The constructor. */
  VMBaseFrame(int frameno, NormalMethod m, int bcinex, RVMThread thread,
      BaselineCompiledMethod bcm, Offset ipOffset, Offset fpOffset) {
    super(frameno, new Location(new VMMethod(m), bcinex), thread);
    this.bcm = bcm;
    this.ipOffset = ipOffset;
    this.fpOffset = fpOffset;
  }

  /** TODO: to-be-implemented. */
  public Value getValue(int slot, byte sig) throws JdwpException{
    throw new NotImplementedException("Frame.getValue");
  }
  public void setValue(int slot, Value value) throws JdwpException {
    throw new NotImplementedException("Frame.getValue");
  }
  public Object getObject() throws JdwpException {
    throw new NotImplementedException("Frame.getValue");
  }
}

/**
 * The internal stack frame from the optimizing compiler. Note that
 * ocm.getMethod() would return different from the loc.getMethod().meth, depend
 * on the inlining decision in the root method ( =ocm.getMethod() ).
 */
final class VMOptFrame extends VMFrame {

  /** The root opt-compiled method.*/
  private final OptCompiledMethod ocm;

  /** The machine instruction offset in the opt-compiled code. */
  private final Offset ipOffset;
  private final Offset fpOffset;
  private final int iei;

  /** The constructor. */
  VMOptFrame(int frameno, RVMMethod m, int bcinex, RVMThread thread,
      OptCompiledMethod ocm, Offset ipOffset, Offset fpOffset, int iei) {
    super(frameno, new Location(new VMMethod(m), bcinex), thread);
    this.ocm = ocm;
    this.ipOffset = ipOffset;
    this.fpOffset = fpOffset;
    this.iei = iei;
  }

  /** TODO: to-be-implemented. */
  public Value getValue(int slot, byte sig)  throws JdwpException {
    throw new NotImplementedException("Frame.getValue");
  }
  public void setValue(int slot, Value value)  throws JdwpException {
    throw new NotImplementedException("Frame.getValue");
  }
  public Object getObject()  throws JdwpException {
    throw new NotImplementedException("Frame.getValue");
  }
}

/** The internal stack frame from the JNI compiler. */
final class VMNativeFrame extends VMFrame {

  /** The constructor. */
  VMNativeFrame(int frameno, NativeMethod m, RVMThread thread ){
    super(frameno, new Location(new VMMethod(m), -1), thread);
    // perhaps, the byte code index would be -1 [JDWP Method.LineTable], 
    // or the back-debugger will ignore this byte code index.
  }

  /** TODO: to-be-implemented. */
  public Value getValue(int slot, byte sig)  throws JdwpException {
    throw new NotImplementedException("Frame.getValue");
  }
  public void setValue(int slot, Value value)  throws JdwpException {
    throw new NotImplementedException("Frame.getValue");
  }
  public Object getObject() throws JdwpException {
    throw new NotImplementedException("Frame.getValue");
  }
}
