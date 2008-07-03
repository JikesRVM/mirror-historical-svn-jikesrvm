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

  /** The size of frameID is 8 (long. */
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
  private final BaselineCompiledMethod bcm;
  private final Offset ipOffset;
  private final Offset fpOffset;

  VMBaseFrame(int frameno, NormalMethod m, int bcinex, RVMThread thread,
      BaselineCompiledMethod bcm, Offset ipOffset, Offset fpOffset) {
    super(frameno, new Location(new VMMethod(m), bcinex), thread);
    this.bcm = bcm;
    this.ipOffset = ipOffset;
    this.fpOffset = fpOffset;
  }

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

/** The internal stack frame from the optimizing compiler. */
final class VMOptFrame extends VMFrame {
  private final OptCompiledMethod ocm;
  private final Offset ipOffset;
  private final Offset fpOffset;
  private final int iei;

  VMOptFrame(int frameno, Location l,  RVMThread thread,
      OptCompiledMethod ocm, Offset ipOffset, Offset fpOffset, int iei) {
    super(frameno, l, thread);
    this.ocm = ocm;
    this.ipOffset = ipOffset;
    this.fpOffset = fpOffset;
    this.iei = iei;
  }
  VMOptFrame(int frameno, RVMMethod m, int bcinex, RVMThread thread,
      OptCompiledMethod ocm, Offset ipOffset, Offset fpOffset, int iei) {
    super(frameno, new Location(new VMMethod(m), bcinex), thread);
    this.ocm = ocm;
    this.ipOffset = ipOffset;
    this.fpOffset = fpOffset;
    this.iei = iei;
  }
  
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
  VMNativeFrame(int frameno, NativeMethod m, RVMThread thread ){
    super(frameno, new Location(new VMMethod(m), -1), thread);
  }
  VMNativeFrame(int frameno, Location location, RVMThread thread ){
    super(frameno, location, thread);
  }
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
