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
import gnu.classpath.jdwp.util.LineTable;
import gnu.classpath.jdwp.util.VariableTable;

/** JikesRVM Specific implementation of VMMethod. */
public final class VMMethod  {

  /**
   * The size of the JDWP methodId is 8 (long), but we internally use only
   * 32bits (int). methodId numbers more than 0xFFFFFFFF are invalid method
   * identifiers.
   */
  public static final int SIZE = 8;

  /** Obtain a vmmethod from the stream. */
  public final static VMMethod readId(Class<?> klass, ByteBuffer bb)
      throws JdwpException {
    long mid = bb.getLong();
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWriteln("VMMethod.readId: ", mid);
    }
    if ((mid & 0x0000FFFFL) != mid) {
      throw new InvalidMethodException(mid);
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
    if (VM.VerifyAssertions) {VM._assert(meth != null);}
  }

  /** Returns the internal method ID for this method. */
  private long getId() {
    return meth.getId();
  }

  /** Returns the method's declaring class. */
  public Class<?> getDeclaringClass() {
    RVMClass rvmclass = meth.getDeclaringClass();
    Class<?> cls = rvmclass.getClassForType();
    return cls;
  }

  /** 
   * Returns the name of this method.
   * @see jdwp-protocol.html#JDWP_ReferenceType_Methods 
   */
  public String getName() {
    final String name = meth.getName().toString();
    return name;
  }

  /**
   * Returns the signature of this method. The signature terminology here is
   * quite confusing. The JDWP SPEC says this is a "JNI signature" in the JNI
   * 1.2 (Java Native Interface) specification, which in turn says
   * "the Java VM's representation of type signature." The description in the
   * JNI manual seems to mean the method descriptor rather than the method
   * signature. The JDK 1.5 explicitly differentiates between the method
   * descriptor and signature. The method signature is the additional
   * information to support Java generic. Here we return the method descriptor
   * even thought the name is getSignature.
   * 
   * @see java.sun.com/javase/6/docs/technotes/guides/jni/spec/types.html#
   *      wp16432
   * @see java.sun.com/docs/books/jvms/second_edition/ClassFileFormat-Java5.pdf
   */
  public String getSignature() {
    String mdesc = meth.getDescriptor().toString();
    return mdesc;
  };

  /**
   * Returns the method's modifier flags. Now report only defined modifiers in
   * the JVM spec. For instance, do not report synthetic modifier bit even
   * though the JikesRVM keeps this attribute.
   * 
   * @see jdwp-protocol.html#JDWP_ReferenceType_Methods
   * @see JVM spec $4.6 Methods
   * @see ClassLoaderConstants
   * @see VMVirtualMachine#canGetSyntheticAttribute
   */
  public int getModifiers() {
    int modifiers = meth.getModifiers() & 0x053F;
    return modifiers;
  }

  /** 
   * Retrieve the line number map for this Java method.
   * 
   * @see jdwp-protocol.html#JDWP_Method_LineTable
   * @see JVM spec 4.7.8 The LineNumberTable Attribute 
   */
  public LineTable getLineTable() throws JdwpException {
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

  /**
   * Retrieve Java variable information.
   * 
   * @see JVM spec 4.7.9 The LocalVariableTable Attribute
   * @see jdwp-protocol.html#JDWP_Method_VariableTable
   */
  public VariableTable getVariableTable() throws JdwpException {
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
      // this is actually descriptor rather than the signature.
      sigs[i] = lv.getDescriptor().toString(); 
      names[i] = lv.getName().toString();
    }
    return new VariableTable(argCnt, slots, lineCI, names,
        sigs, lengths, slot);
  }

  /** Write the methodId into the stream. */
  public void writeId(DataOutputStream ostream) throws IOException {
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
  public String toString() {
    return meth.toString();
  }
}
