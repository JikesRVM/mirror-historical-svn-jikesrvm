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

import org.jikesrvm.debug.LocalVariable;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.VM;

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
public final class VMFrame {

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

  private final int depth;
  /** Constructor. */
  public VMFrame(int depth, Location loc, RVMThread thread) {
    this.thread = thread;
    this.frameID = ((long)thread.getIndex()) << 32 | depth;
    this.loc = loc;
    this.depth = depth;
  }

  /** Getters */
  public Location getLocation() { return loc;}
  public long getId() { return frameID;}

  /** Read a local variable in a frame. */
  public Value getValue(int slot, byte tag) throws JdwpException {
  switch (tag) {
    case JdwpConstants.Tag.BOOLEAN:
      return new BooleanValue(
          LocalVariable.getInt(thread, depth, slot) == 0 ? false : true);
    case JdwpConstants.Tag.BYTE:
      return new ByteValue(
          (byte) (LocalVariable.getInt(thread, depth, slot) & 0xff));
    case JdwpConstants.Tag.CHAR:
      return new CharValue(
          (char) (LocalVariable.getInt(thread, depth, slot) & 0xffff));
    case JdwpConstants.Tag.SHORT:
      return new ShortValue(
          (short) (LocalVariable.getInt(thread, depth, slot) & 0xffff));
    case JdwpConstants.Tag.INT:
      return new IntValue(LocalVariable.getInt(thread, depth, slot));
    case JdwpConstants.Tag.LONG:
      return new LongValue(LocalVariable.getLong(thread, depth, slot));
    case JdwpConstants.Tag.FLOAT:
      return new FloatValue(LocalVariable.getFloat(thread, depth, slot));
    case JdwpConstants.Tag.DOUBLE:
      return new DoubleValue(LocalVariable.getDouble(thread, depth, slot));
    case JdwpConstants.Tag.OBJECT:
      return new ObjectValue(LocalVariable.getObject(thread, depth, slot));
    case JdwpConstants.Tag.STRING:
    case JdwpConstants.Tag.VOID:
    case JdwpConstants.Tag.ARRAY:
    case JdwpConstants.Tag.THREAD:
    case JdwpConstants.Tag.THREAD_GROUP:
    case JdwpConstants.Tag.CLASS_LOADER:
    case JdwpConstants.Tag.CLASS_OBJECT:
      if (VM.VerifyAssertions) {
        VM._assert(false,
            "Do we use this specific type in the JDWP Frame.GetValues?");
      }
      return null;
    default:
      if (VM.VerifyAssertions) {
        VM._assert(false, "Unsupported JDWP tag type: " + tag);
      }
      return null;
    }    
  }

  /** Write a local variable in a frame. */
  public void setValue(int slot, Value value)throws JdwpException {
    final int tag = value.getTag();
    switch(tag) {
    case JdwpConstants.Tag.BOOLEAN: {
      BooleanValue v = (BooleanValue) value;
      LocalVariable.setInt(thread, depth, slot, v.getValue() ? 1 : 0);
      break;
    }
    case JdwpConstants.Tag.BYTE: {
      ByteValue v = (ByteValue) value;
      LocalVariable.setInt(thread, depth, slot, v.getValue());
      break;      
    }
    case JdwpConstants.Tag.CHAR: {
      CharValue v = (CharValue) value;
      LocalVariable.setInt(thread, depth, slot, v.getValue());
      break;      
    }
    case JdwpConstants.Tag.SHORT:{
      ShortValue v = (ShortValue) value;
      LocalVariable.setInt(thread, depth, slot, v.getValue());
      break;      
    }
    case JdwpConstants.Tag.INT: {
      IntValue v = (IntValue) value;
      LocalVariable.setInt(thread, depth, slot, v.getValue());
      break;      
    }
    case JdwpConstants.Tag.LONG: {
      LongValue v = (LongValue) value;
      LocalVariable.setLong(thread, depth, slot, v.getValue());
      break;      
    }
    case JdwpConstants.Tag.FLOAT: {
      FloatValue v = (FloatValue) value;
      LocalVariable.setFloat(thread, depth, slot, v.getValue());
      break;      
    } 
    case JdwpConstants.Tag.DOUBLE: {
      DoubleValue v = (DoubleValue) value;
      LocalVariable.setDouble(thread, depth, slot, v.getValue());
      break;      
    }
    case JdwpConstants.Tag.OBJECT: {
      ObjectValue v = (ObjectValue) value;
      LocalVariable.setObject(thread, depth, slot, v.getValue());
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
  }

  /** Read a this variable in a frame. */
  public Object getObject() throws JdwpException {
    throw new NotImplementedException("Frame.getValue");
  }
}
