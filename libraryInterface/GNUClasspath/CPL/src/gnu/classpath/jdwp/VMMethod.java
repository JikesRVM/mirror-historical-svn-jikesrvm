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

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.AbstractMethod;
import org.jikesrvm.classloader.LocalVariable;
import org.jikesrvm.classloader.MemberReference;
import org.jikesrvm.classloader.NativeMethod;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.MethodReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.debug.JikesRVMJDWP;

import gnu.classpath.jdwp.exception.AbsentInformationException;
import gnu.classpath.jdwp.exception.InvalidMethodException;
import gnu.classpath.jdwp.exception.JdwpException;
import gnu.classpath.jdwp.exception.NotImplementedException;
import gnu.classpath.jdwp.util.LineTable;
import gnu.classpath.jdwp.util.VariableTable;

/** JikesRVM Specific implementation of VMMethod. */
public final class VMMethod  {

  /** The size of the JDWP methodId is 8 (long). */
  public static final int SIZE = 8;

  /** Obtain a vmmethod from the stream. */
  public final static VMMethod readId(Class<?> klass, ByteBuffer bb)
      throws JdwpException {
    long mid = bb.getLong();
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWriteln("VMMethod.readId: ", mid);
    }
    MemberReference mref = MemberReference.getMemberRef((int) mid);
    MethodReference methref = mref.asMethodReference();
    RVMMethod meth = methref.peekResolvedMethod();
    if (meth == null) {
      throw new InvalidMethodException(mid);
    }
    return new VMMethod(meth);
  }

  /** The RVM Method. package private visibility. */
  final RVMMethod meth;

  /** Constructor. */
  VMMethod(RVMMethod meth) {
    this.meth = meth;
    if (VM.VerifyAssertions) {
      VM._assert(meth != null);
    }
  }

  /** Returns the internal method ID for this method. */
  private final long getId() {
    return meth.getId();
  }

  /** Returns the method's declaring class. */
  public final Class<?> getDeclaringClass() {
    RVMClass rvmclass = meth.getDeclaringClass();
    Class<?> cls = rvmclass.getClassForType();
    return cls;
  }

  /** Returns the name of this method. */
  public final String getName() {
    final String name = meth.getName().toString();
    return name;
  }

  /** Returns the signature of this method. */
  public final String getSignature() {
    String mdesc = meth.getDescriptor().toString();
    return mdesc;
  };

  /** Returns the method's modifier flags. */
  public final int getModifiers() {
    final int modifiers = meth.getModifiers();
    return modifiers;
  }

  /** Retrieve the line number map for this Java method. */
  public final LineTable getLineTable() throws JdwpException {
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWriteln("getLineTable: in ", toString());
    }
    if (meth instanceof NativeMethod) {
      return new LineTable(-1, -1, new int[0], new long[0]);
    } else if (meth instanceof AbstractMethod) {
      return new LineTable(0, 0, new int[0], new long[0]);
    }
    if (VM.VerifyAssertions) {
      VM._assert(meth instanceof NormalMethod);
    }

    NormalMethod nmeth = (NormalMethod) meth;
    int[] rvmLineMap = nmeth.getLineNumberMap();
    int numEntries = rvmLineMap == null ? 0 : rvmLineMap.length;

    int start = 0;
    int end = nmeth.getBytecodeLength()-1;
    int[] lineNumbers = new int[numEntries];
    long[] lineCodeIndecies = new long[numEntries];
    for (int i = 0; i < numEntries; i++) {
      int bcindex = rvmLineMap[i] & 0xffff;
      int lineNumber = rvmLineMap[i] >>> 16;
      if (VM.VerifyAssertions) {
        VM._assert(bcindex >= start && bcindex <= end);
      }
      lineCodeIndecies[i] = bcindex;
      lineNumbers[i] = lineNumber;
      if (JikesRVMJDWP.getVerbose() >= 3) {
        VM.sysWriteln("  lineNumbers[i]: ", lineNumbers[i]);
        VM.sysWriteln("  lineCodeIndecies[i]: ", lineCodeIndecies[i]);
      }
    }
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWriteln("  start: ", start);
      VM.sysWriteln("  end: ", end);
    }
    return new LineTable(start, end, lineNumbers, lineCodeIndecies);
  }

  /** TODO: to-be-implemented. Retrieve the Java variable information.*/
  public final VariableTable getVariableTable() throws JdwpException {
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWriteln("getVariableTable:", " in ", toString());
    }
    if (meth instanceof NormalMethod == false ){
      throw new AbsentInformationException("not Java method"); 
    }
    NormalMethod m = (NormalMethod)meth;
    LocalVariable[] table = m.getlocalVariableTable();
    if (table == null) {
      throw new AbsentInformationException("no debugging information");
    }
    int argCnt = m.getParameterWords() + (m.isStatic() ? 0 : 1);
    int slots = table.length;

    long[] lineCI = new long[slots];
    int[] slot = new int[slots];
    int[] lengths = new int[slots];
    String[] sigs = new String[slots];
    String[] names= new String[slots];
    for(int i =0; i < slots;i++) {
      LocalVariable lv = table[i]; 
      lineCI[i] = lv.getStart_pc();
      slot[i] = lv.getIndex();
      lengths[i] = lv.getLength();
      sigs[i] = lv.getDescriptor().toString();
      names[i] = lv.getName().toString();
    }
    return new VariableTable(argCnt, slots, lineCI, names,
        sigs, lengths, slot);
  }

  /** Write the methodId into the stream. */
  public final void writeId(DataOutputStream ostream) throws IOException {
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWriteln("writeId: " +  getId() + " in " + toString());
    }
    ostream.writeLong(getId());
  }

  /**
   * For the use with the Location. 
   * @see gnu.classpath.jdwp.util.Location#hashCode
   */
  public int hashCode() {
    return meth.hashCode();
  }

  /** 
   * For the use with the Location.
   * @see gnu.classpath.jdwp.util.Location#equals The method. 
   */
  public boolean equals(Object obj) {
    if (obj instanceof VMMethod) {
      return this.meth.equals(((VMMethod)obj).meth);
    } else {
      return false;
    }
  }

  /** Get a user friendly string representation. */
  public final String toString() {
    return meth.getDeclaringClass().toString()
        + "." + meth.getName()
        + meth.getDescriptor();
  }
}
