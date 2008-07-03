/* VMMethod.java -- supplying JDWP operations. */

package gnu.classpath.jdwp;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.AbstractMethod;
import org.jikesrvm.classloader.MemberReference;
import org.jikesrvm.classloader.NativeMethod;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.MethodReference;
import org.jikesrvm.classloader.NormalMethod;

import gnu.classpath.jdwp.exception.InvalidMethodException;
import gnu.classpath.jdwp.exception.JdwpException;
import gnu.classpath.jdwp.exception.NotImplementedException;
import gnu.classpath.jdwp.util.LineTable;
import gnu.classpath.jdwp.util.VariableTable;

public final class VMMethod  {

  /** The size of the JDWP methodId is 8 (long).*/
  public static final int SIZE = 8;

  /** The RVM Method. */
  private final RVMMethod meth;

  /** Constructor.*/
  VMMethod(final RVMMethod meth) {
    this.meth = meth;
    if (VM.VerifyAssertions) {
      VM._assert(meth != null);
    }
  }

  /** Returns the internal method ID for this method. */
  private final long getId() {
    int mid = meth.getId();
    return mid;
  }

  /** Returns the method's declaring class. */
  public final Class<?> getDeclaringClass() {
    RVMClass rvmclass = meth.getDeclaringClass();
    Class<?> cls = rvmclass.getClassForType();
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWriteln("getDeclaringClass: ", cls.toString());
    }
    return cls;
  }

  /** Returns the name of this method. */
  public final String getName() {
    final String name = meth.getName().toString();
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWriteln("getName: ", name, " in ", toString());
    }
    return name;
  }

  /** Returns the signature of this method. */
  public final String getSignature() {
    String mdesc = meth.getDescriptor().toString();
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWriteln("getDescriptor: ", mdesc, " in ", toString());
    }
    return mdesc;
  };

  /** Returns the method's modifier flags. */
  public final int getModifiers() {
    final int modifiers = meth.getModifiers();
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWrite("getModifiers: ");
      VM.sysWriteHex(modifiers);
      VM.sysWriteln(" in ", toString());
    }
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

    int start = -1;
    int end = 0;
    int[] lineNumbers = new int[numEntries];
    long[] lineCodeIndecies = new long[numEntries];
    for (int i = 0; i < numEntries; i++) {
      int bcindex = rvmLineMap[i] & 0xffff;
      int lineNumber = rvmLineMap[i] >>> 16;
      if (start == -1 || start > bcindex) {start = bcindex;}
      if (end < bcindex) { end = bcindex;}
      lineCodeIndecies[i] = bcindex;
      lineNumbers[i] = lineNumber;
      if (JikesRVMJDWP.getVerbose() >= 3) {
        VM.sysWriteln("  lineNumbers[i]: ", lineNumbers[i]);
        VM.sysWriteln("  lineCodeIndecies[i]: ", lineCodeIndecies[i]);
      }
    }
    return new LineTable(start, end, lineNumbers, lineCodeIndecies);
  }

  /** Retrieve the Java variable information.*/
  public final VariableTable getVariableTable() throws JdwpException {
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWriteln("getVariableTable:", " in ", toString());
    }
    throw new NotImplementedException("getVariableTable");
  }

  /** Get a user friendly string representation. */
  public final String toString() {
    return meth.getDeclaringClass().toString()
        + "." + meth.getName()
        + meth.getDescriptor();
  }

  /** Write the methodId into the stream. */
  public final void writeId(DataOutputStream ostream) throws IOException {
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWriteln("writeId: " +  getId() + " in " + toString());
    }
    ostream.writeLong(getId());
  }

  /** Obtain a vm method from the stream. */
  public final static VMMethod readId(Class<?> klass, ByteBuffer bb)
      throws JdwpException {
    long mid = bb.getLong();
    if (JikesRVMJDWP.getVerbose() >= 3) {
      VM.sysWriteln("readId: ", mid);
    }
    MemberReference mref = MemberReference.getMemberRef((int) mid);
    MethodReference methref = mref.asMethodReference();
    RVMMethod meth = methref.peekResolvedMethod();
    if (meth == null) {
      throw new InvalidMethodException(mid);
    }
    return new VMMethod(meth);
  }
}
