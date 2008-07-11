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
import org.jikesrvm.compilers.baseline.ia32.BaselineCompilerImpl;
import org.jikesrvm.compilers.opt.runtimesupport.OptCompiledMethod;
import org.jikesrvm.debug.JikesRVMJDWP;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.SizeConstants;
import org.jikesrvm.VM;
import org.vmmagic.unboxed.Offset;

import gnu.classpath.jdwp.exception.JdwpException;
import gnu.classpath.jdwp.exception.NotImplementedException;
import gnu.classpath.jdwp.util.Location;
import gnu.classpath.jdwp.value.BooleanValue;
import gnu.classpath.jdwp.value.ByteValue;
import gnu.classpath.jdwp.value.CharValue;
import gnu.classpath.jdwp.value.DoubleValue;
import gnu.classpath.jdwp.value.FloatValue;
import gnu.classpath.jdwp.value.IntValue;
import gnu.classpath.jdwp.value.LongValue;
import gnu.classpath.jdwp.value.ObjectValue;
import gnu.classpath.jdwp.value.ShortValue;
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
  VMBaseFrame(int frameno, NormalMethod m, int bcindex, RVMThread thread,
      BaselineCompiledMethod bcm, Offset ipOffset, Offset fpOffset) {
    super(frameno, new Location(new VMMethod(m), bcindex), thread);
    this.bcm = bcm;
    this.ipOffset = ipOffset;
    this.fpOffset = fpOffset;
  }

  /** Get the value of a local variable at the given slot index. */
  public Value getValue(int slot, byte tag) throws JdwpException{
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWriteln("getValue: slot = ", slot, " tag", tag);
    }
    switch (tag) {
    case JdwpConstants.Tag.BOOLEAN:
      return new BooleanValue(getIntslotValue(slot) == 0? false:true);
    case JdwpConstants.Tag.BYTE:
      return new ByteValue((byte)(getIntslotValue(slot) & 0xff));
    case JdwpConstants.Tag.CHAR:
      return new CharValue((char)(getIntslotValue(slot) & 0xffff));
    case JdwpConstants.Tag.SHORT:
      return new ShortValue((short)(getIntslotValue(slot) & 0xffff));
    case JdwpConstants.Tag.INT:
      return new IntValue(getIntslotValue(slot));
    case JdwpConstants.Tag.LONG:
      return new LongValue(getLongslotValue(slot));
    case JdwpConstants.Tag.FLOAT:
      return new FloatValue(Magic.intBitsAsFloat(getIntslotValue(slot)));
    case JdwpConstants.Tag.DOUBLE:
      return new DoubleValue(Magic.longBitsAsDouble(getLongslotValue(slot)));
    case JdwpConstants.Tag.OBJECT:
      return new ObjectValue(getObjectSlotValue(slot));
    case JdwpConstants.Tag.STRING:
    case JdwpConstants.Tag.VOID:
    case JdwpConstants.Tag.ARRAY:
    case JdwpConstants.Tag.THREAD:
    case JdwpConstants.Tag.THREAD_GROUP:
    case JdwpConstants.Tag.CLASS_LOADER:
    case JdwpConstants.Tag.CLASS_OBJECT:
      if (VM.VerifyAssertions) {
        VM._assert(false, "Do we use this specific type in the JDWP Frame.GetValues?");
      }
      return null;
    default:
      if (VM.VerifyAssertions) {
        VM._assert(false, "Unsupported JDWP tag type: " + tag);
      }
      return null;
    }
  }

  /** Set the value of a local variable at the given slot index. */
  public void setValue(int slot, Value value) throws JdwpException {
    final int tag = value.getTag();
    switch(tag) {
    case JdwpConstants.Tag.BOOLEAN: {
      BooleanValue v = (BooleanValue) value;
      setIntSlotValue(slot, v.getValue() ? 1 : 0);
      break;
    }
    case JdwpConstants.Tag.BYTE: {
      ByteValue v = (ByteValue) value;
      setIntSlotValue(slot, v.getValue());
      break;      
    }
    case JdwpConstants.Tag.CHAR: {
      CharValue v = (CharValue) value;
      setIntSlotValue(slot, v.getValue());
      break;      
    }
    case JdwpConstants.Tag.SHORT:{
      ShortValue v = (ShortValue) value;
      setIntSlotValue(slot, v.getValue());
      break;      
    }
    case JdwpConstants.Tag.INT: {
      IntValue v = (IntValue) value;
      setIntSlotValue(slot, v.getValue());
      break;      
    }
    case JdwpConstants.Tag.LONG: {
      LongValue v = (LongValue) value;
      setLongSlotValue(slot, v.getValue());
      break;      
    }
    case JdwpConstants.Tag.FLOAT: {
      FloatValue v = (FloatValue) value;
      setIntSlotValue(slot, Magic.floatAsIntBits(v.getValue()));
      break;      
    } 
    case JdwpConstants.Tag.DOUBLE: {
      DoubleValue v = (DoubleValue) value;
      setLongSlotValue(slot, Magic.doubleAsLongBits(v.getValue()));
      break;      
    }
    case JdwpConstants.Tag.OBJECT: {
      ObjectValue v = (ObjectValue) value;
      setObjectSlotValue(slot, v.getValue());
    }
    case JdwpConstants.Tag.STRING:
    case JdwpConstants.Tag.VOID:
    case JdwpConstants.Tag.ARRAY:
    case JdwpConstants.Tag.THREAD:
    case JdwpConstants.Tag.THREAD_GROUP:
    case JdwpConstants.Tag.CLASS_LOADER:
    case JdwpConstants.Tag.CLASS_OBJECT:
      if (VM.VerifyAssertions) {
        VM._assert(false, "Do we use this specific type in the JDWP Frame.GetValues?");
      }
      break;
    default:
      if (VM.VerifyAssertions) {
        VM._assert(false, "Unsupported JDWP tag type: " + tag);
      }
      break;
    }
    
    throw new NotImplementedException("Frame.getValue");
  }

  /**
   * Get the "this" object if the method is non-static method. Otherwise, return
   * null. Do the debugger backend actually need this feature? This feature
   * overlaps with the Frame.GetValues().
   */
  public Object getObject() throws JdwpException {
    RVMMethod meth = bcm.getMethod();
    if (meth.isStatic()) {
      return null;
    } else {
      return getObjectSlotValue(0); //The first local variable is the "this"
    }
  }

  private Object getObjectSlotValue(int slot) {
    byte[] stack = thread.getStack();
    Offset slotOffset = fpOffset.plus(getGeneralLocalOffset(slot));
    return Magic.getObjectAtOffset(stack, slotOffset);
  }

  private void setObjectSlotValue(int slot, Object value) {
    byte[] stack = thread.getStack();
    Offset slotOffset = fpOffset.plus(getGeneralLocalOffset(slot));
    Magic.setObjectAtOffset(stack, slotOffset, value);    
  }

  private int getIntslotValue(int slot) {
    byte[] stack = thread.getStack();
    Offset slotOffset = fpOffset.plus(getGeneralLocalOffset(slot));
    return Magic.getIntAtOffset(stack, slotOffset);
  }
  
  private long getLongslotValue(int slot) {
    int lsb = getIntslotValue(slot);
    int msb = getIntslotValue(slot+1);
    long v = ((long)msb << 32) | (((long)lsb)& 0xFFFFFFFF);   
    return v;
  }

  private void setIntSlotValue(int slot, int value) {
    byte[] stack = thread.getStack();
    Offset slotOffset = fpOffset.plus(getGeneralLocalOffset(slot));
    Magic.setIntAtOffset(stack, slotOffset, value);
  }
  
  private void setLongSlotValue(int slot, long value) {
    int lsb = (int)(value & 0xFFFFFFFF);
    int msb = (int)((value >> 32) &  0xFFFFFFFF);
    setIntSlotValue(slot, lsb);
    setIntSlotValue(slot, msb);
  }

  /** Get a frame pointer relative offset for a local variable slot. */
  private int getGeneralLocalOffset(int slot) {
    int location = bcm.getGeneralLocalLocation(slot);
    int offset = BaselineCompilerImpl.locationToOffset(location)
        - SizeConstants.BYTES_IN_ADDRESS;
    return offset;
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

  /**
   * The JikesRVM currently support opt-compiled method, and this will be future
   * work.
   */
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
    // or the back-end debugger will ignore this byte code index.
  }

  /** JDWP does not expect accessing native local variables. */
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
