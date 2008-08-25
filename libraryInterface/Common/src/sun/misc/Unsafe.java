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
package sun.misc;

import java.lang.reflect.Field;

import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.runtime.Memory;
import org.jikesrvm.runtime.SysCall;
import org.jikesrvm.scheduler.Synchronization;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Extent;

public final class Unsafe {
  private static final Unsafe unsafe = new Unsafe();

  private Unsafe() {}

  public static Unsafe getUnsafe() {
    SecurityManager sm = System.getSecurityManager();
    if (sm != null)
      sm.checkPropertiesAccess();
    return unsafe;
  }

  private Offset longToOffset(long offset) {
    return Offset.fromIntSignExtend((int)offset);
  }

  public long objectFieldOffset(Field field) {
    RVMField vmfield = java.lang.reflect.JikesRVMSupport.getFieldOf(field);
    return vmfield.getOffset().toLong();
  }

  public boolean compareAndSwapInt(Object obj,long offset,int expect,int update) {
    Offset off = longToOffset(offset);
    return Synchronization.tryCompareAndSwap(obj, off, expect, update);
  }

  public boolean compareAndSwapLong(Object obj,long offset,long expect,long update) {
    Offset off = Offset.fromIntSignExtend((int)offset);
    return Synchronization.tryCompareAndSwap(obj, off, expect, update);
  }

  public boolean compareAndSwapObject(Object obj,long offset,Object expect,Object update) {
    Offset off = Offset.fromIntSignExtend((int)offset);
    return Synchronization.tryCompareAndSwap(obj, off, expect, update);
  }

  public void putOrderedInt(Object obj,long offset,int value) {
    Offset off = longToOffset(offset);
    Magic.setIntAtOffset(obj,off,value);
  }

  public void putOrderedLong(Object obj,long offset,long value) {
    Offset off = longToOffset(offset);
    Magic.setLongAtOffset(obj,off,value);
  }

  public void putOrderedObject(Object obj,long offset,Object value) {
    Offset off = longToOffset(offset);
    Magic.setObjectAtOffset(obj,off,value);
   }

  public void putIntVolatile(Object obj,long offset,int value) {
    Offset off = longToOffset(offset);
    Magic.setIntAtOffset(obj,off,value);
  }

  public int getIntVolatile(Object obj,long offset) {
    Offset off = longToOffset(offset);
    return Magic.getIntAtOffset(obj,off);
  }
  
  public byte getByte(long offset) {
    Address a = Address.fromLong(offset);
    return a.loadByte();
  }
  
  public void putByte(long address, byte b) {
    throw new Error("sun.misc.Unsafe.putByte: Not implemented");
  }
  
  public char getChar(long offset) {
    Address a = Address.fromLong(offset);
    return a.loadChar();
  }
  
  public void putChar(long address, char b) {
    throw new Error("sun.misc.Unsafe.putChar: Not implemented");
  }
  
  public short getShort(long offset) {
    Address a = Address.fromLong(offset);
    return a.loadShort();
  }
  
  public void putShort(long address, short s) {
    throw new Error("sun.misc.Unsafe.putShort: Not implemented");
  }
  
  public int getInt(long offset) {
    Address a = Address.fromLong(offset);
    return a.loadInt();
  }
  
  public void putInt(long address, int s) {
    throw new Error("sun.misc.Unsafe.putInt: Not implemented");
  }
  
  public long getLong(long offset) {
    Address a = Address.fromLong(offset);
    return a.loadLong();
  }
  
  public void putLong(long address, long l) {
    throw new Error("sun.misc.Unsafe.putLong: Not implemented");
  }
  
  public double getDouble(long offset) {
    Address a = Address.fromLong(offset);
    return a.loadDouble();
  }
  
  public void putDouble(long address, double l) {
    throw new Error("sun.misc.Unsafe.putDouble: Not implemented");
  }
  
  //Simulate getBoolean, hopefully succesfuly
  public boolean getBoolean(Object o, long offset) {
    Offset off = longToOffset(offset);
    return Magic.getUnsignedByteAtOffset(o, off) > 0; 
  }
  
  public byte getByte(Object o, long offset) {
    Offset off = longToOffset(offset);
    return Magic.getByteAtOffset(o, off);
  }
  
  /*
   * Warning: Sun's JDK implementation requires 64-bit address sizes for all
   * memory operations. We use 32-bit sizes for now.
   */
  public long allocateMemory(long length) {
    return SysCall.sysCall.sysMalloc((int)length).toLong();
  }
  
  public void freeMemory(long addr) {
    SysCall.sysCall.sysFree(Address.fromLong(addr));
  }
  
  //call bzero to start with
  // TODO: implement sysMemSet syscall
  public void setMemory(long address, long bytes, byte value) {
    SysCall.sysCall.sysZero(Address.fromLong(address), Extent.fromIntZeroExtend((int) bytes));
  }
  
  public void copyMemory(long src, long dest, long bytes) {
    Address from = Address.fromLong(src);
    Address to = Address.fromLong(dest);
    Extent e = Extent.fromIntZeroExtend((int)bytes); 
    Memory.memcopy(to, from, e);
  }

  public void putLongVolatile(Object obj,long offset,long value) {
    Offset off = longToOffset(offset);
    Magic.setLongAtOffset(obj,off,value);
   }

  public void putLong(Object obj,long offset,long value) {
    Offset off = longToOffset(offset);
    Magic.setLongAtOffset(obj,off,value);
  }

  public long getLongVolatile(Object obj,long offset) {
    Offset off = longToOffset(offset);
    return Magic.getLongAtOffset(obj,off);
  }

  public long getLong(Object obj,long offset) {
    Offset off = longToOffset(offset);
    return Magic.getLongAtOffset(obj,off);
  }

  public void putObjectVolatile(Object obj,long offset,Object value) {
    Offset off = longToOffset(offset);
    Magic.setObjectAtOffset(obj,off,value);
  }

  public void putObject(Object obj,long offset,Object value) {
    Offset off = longToOffset(offset);
    Magic.setObjectAtOffset(obj,off,value);
  }

  public Object getObjectVolatile(Object obj,long offset) {
    Offset off = longToOffset(offset);
    return Magic.getObjectAtOffset(obj,off);
  }

  public int arrayBaseOffset(Class<?> arrayClass) {
    return 0;
  }

  public int arrayIndexScale(Class<?> arrayClass) {
    RVMType arrayType = java.lang.JikesRVMSupport.getTypeForClass(arrayClass);
    if (!arrayType.isArrayType()) {
      return 0;
    } else {
      return 1 << arrayType.asArray().getLogElementSize();
    }
  }

  public void unpark(Object thread) {
    RVMThread vmthread = java.lang.JikesRVMSupport.getThread((Thread)thread);
    if (vmthread != null) {
      vmthread.unpark();
    }
  }

  public void park(boolean isAbsolute,long time) throws Throwable  {
    RVMThread vmthread = java.lang.JikesRVMSupport.getThread(Thread.currentThread());
    vmthread.park(isAbsolute, time);
  }
}
