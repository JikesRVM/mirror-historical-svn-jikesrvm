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

import gnu.classpath.jdwp.event.BreakpointEvent;
import gnu.classpath.jdwp.event.ClassPrepareEvent;
import gnu.classpath.jdwp.event.Event;
import gnu.classpath.jdwp.event.EventRequest;
import gnu.classpath.jdwp.event.ExceptionEvent;
import gnu.classpath.jdwp.event.ThreadEndEvent;
import gnu.classpath.jdwp.event.ThreadStartEvent;
import gnu.classpath.jdwp.event.VmDeathEvent;
import gnu.classpath.jdwp.event.VmInitEvent;
import gnu.classpath.jdwp.event.filters.IEventFilter;
import gnu.classpath.jdwp.event.filters.LocationOnlyFilter;
import gnu.classpath.jdwp.exception.InvalidClassException;
import gnu.classpath.jdwp.exception.InvalidEventTypeException;
import gnu.classpath.jdwp.exception.InvalidFrameException;
import gnu.classpath.jdwp.exception.JdwpException;
import gnu.classpath.jdwp.exception.NotImplementedException;
import gnu.classpath.jdwp.util.Location;
import gnu.classpath.jdwp.util.MethodResult;
import gnu.classpath.jdwp.util.MonitorInfo;
import gnu.classpath.jdwp.value.BooleanValue;
import gnu.classpath.jdwp.value.ByteValue;
import gnu.classpath.jdwp.value.CharValue;
import gnu.classpath.jdwp.value.DoubleValue;
import gnu.classpath.jdwp.value.FloatValue;
import gnu.classpath.jdwp.value.IntValue;
import gnu.classpath.jdwp.value.ObjectValue;
import gnu.classpath.jdwp.value.ShortValue;
import gnu.classpath.jdwp.value.Value;
import gnu.classpath.jdwp.value.VoidValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.lang.JikesRVMSupport;

import org.jikesrvm.VM;
import org.jikesrvm.debug.EventCallbacks;
import org.jikesrvm.debug.RVMDebug;
import org.jikesrvm.debug.StackFrame;
import org.jikesrvm.debug.Threads;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.Primitive;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.debug.JikesRVMJDWP;
import org.jikesrvm.debug.RVMDebug.EventType;
import org.jikesrvm.debug.StackFrame.FrameInfoList;
import org.jikesrvm.runtime.Reflection;
import org.jikesrvm.scheduler.Scheduler;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.util.HashMapRVM;
import org.jikesrvm.util.LinkedListRVM;
import org.vmmagic.pragma.Uninterruptible;
import org.jikesrvm.ArchitectureSpecific.StackframeLayoutConstants;

/** JikesRVM implementation of VMVirtualMachine. */
public final class VMVirtualMachine implements StackframeLayoutConstants {

  /** JDWP JVM optional capabilities - disabled. */
  public static final boolean canWatchFieldModification = false;

  public static final boolean canWatchFieldAccess = false;

  public static final boolean canGetBytecodes = false;

  public static final boolean canGetSyntheticAttribute = false;

  public static final boolean canGetOwnedMonitorInfo = false;

  public static final boolean canGetCurrentContendedMonitor = false;

  public static final boolean canGetMonitorInfo = false;

  public static final boolean canRedefineClasses = false;

  public static final boolean canAddMethod = false;

  public static final boolean canUnrestrictedlyRedefineClasses = false;

  public static final boolean canPopFrames = false;

  public static final boolean canUseInstanceFilters = false;

  public static final boolean canGetSourceDebugExtension = false;

  public static final boolean canRequestVMDeathEvent = false;

  public static final boolean canSetDefaultStratum = false;

  /* The instance is for event call back from Jikes RVM. */
  private static VMVirtualMachineImpl _vm; 

  /** The internal initialization process. */
  public static void boot(String jdwpArgs) throws Exception {
    _vm = new VMVirtualMachineImpl();
    RVMDebug rvmdbg = RVMDebug.getRVMDebug();
    
    VMIdManager.init();
    Jdwp jdwp = new Jdwp();
    jdwp.setDaemon(true);
    jdwp.configure(jdwpArgs);
    jdwp.start();
    jdwp.join(); // wait for initialization. not related for starting suspended.
    
    // "The VM Start Event and VM Death Event are automatically generated events. This means they do not need to be requested using the EventRequest.Set command."
    // [http://java.sun.com/javase/6/docs/platform/jpda/jdwp/jdwp-protocol.html#
    // JDWP_Event_Composite
    rvmdbg.setEventCallbacks(_vm);
    rvmdbg.setEventNotificationMode(EventType.VM_INIT, true, null);
    rvmdbg.setEventNotificationMode(EventType.VM_DEATH, true, null);
    if (VM.VerifyAssertions) {
      VM._assert(Jdwp.isDebugging, "The JDWP must be initialized here.");
    }
  }
  
  private static VMVirtualMachineImpl vm() {
    if (VM.VerifyAssertions) {
      VM._assert(_vm != null);
    }
    return _vm;
  }

  /** Suspend a debugee thread. */
  public static void suspendThread(final Thread thread) throws JdwpException {
    vm().suspendThread(thread);
  }

  public static void suspendAllThreads() throws JdwpException {
    vm().suspendAllThreads();
  }

  /**
   * Suspend all the debuggee threads. Two calling contexts are considered here.
   * The first one is from VMVirtualMachine.suspend, and the current thread
   * would be PacketProcessor. The second one is from the debugee thread due to
   * SUSPEND_ALL event suspension policy.
   */
  public static void suspendThreadsInternal(RVMThread[] threads) {
    vm().suspendThreadsInternal(threads);
  }

  /** 
   * Resume a debugee thread.
   */
  public static void resumeThread(final Thread thread) throws JdwpException {
    vm().resumeThread(thread);
  }

  
  /**
   * Resume all threads. 
   */
  public static void resumeAllThreads() throws JdwpException {
    vm().resumeAllThreads();
  }

  /**
   * Get the suspend count for a thread. 
   */
  public static int getSuspendCount(final Thread thread) throws JdwpException {
    return vm().getSuspendCount(thread); 
  };

  /** 
   * Returns the status of a thread. 
   */
  public static int getThreadStatus(final Thread thread) throws JdwpException {
    return vm().getThreadStatus(thread);
  }

  /** Return all loaded classes. */
  public static Collection<Class<?>> getAllLoadedClasses() throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("getAllLoadedClasses: ");
    }
    // TODO: This only does not give the system classes loaded by the bootstrap
    // class. We might want to export some of these essential java classes such
    // as Object, Class, Throwable, Error and etc.
    Class<?>[] loadedClasses = JikesRVMSupport.getAllLoadedClasses();
    return Arrays.asList(loadedClasses);
  };    

  /** Return the class status. */
  public static int getClassStatus(final Class<?> clazz) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }

    int status =  getStatus(getRVMType(clazz));
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWrite("getClassStatus:", clazz.getName());
      VM.sysWriteln(" value = ", status);
    }
    return status;
  }

  /** Get the all the declared method in a class. */
  public static VMMethod[] getAllClassMethods(final Class<?> clazz)
      throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    RVMClass vmClass = getRVMClass(clazz);
    // Calculate length.
    RVMMethod[] rmethods = vmClass.getDeclaredMethods();
    VMMethod[] vmMethods = new VMMethod[rmethods.length];
    int index = 0;
    for (RVMMethod rvmMethod : vmClass.getDeclaredMethods()) {
      vmMethods[index++] = new VMMethod(rvmMethod);
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("getAllClassMethods for class : ", clazz.getName());
    }
    return vmMethods;
  }

  /** Dump call stack frames in a suspended thread. */
  public static ArrayList<VMFrame> getFrames(final Thread thread,
      final int start, final int length) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    // "Returns the current call stack of a suspended thread. The sequence of
    // frames starts with the currently executing frame, followed by its caller,
    // and so on. The thread must be suspended, and the returned frameID is
    // valid only while the thread is suspended."
    // [jdwp-protocol.html#JDWP_ThreadReference_Frames]
    if (start < 0 || length < -1) {
      return new ArrayList<VMFrame>(); // simply return empty frame list.
    }

    //perform call stack walk.
    RVMThread rvmThread =  JikesRVMSupport.getThread(thread);
    int actualLength;
    if (length == -1) {
      int fCount = StackFrame.getFrameCount(rvmThread);
      actualLength = fCount - start;
    } else {
      actualLength = length;
    }
    if (VM.VerifyAssertions) {VM._assert(actualLength >= 0);}
    FrameInfoList frames = new FrameInfoList(actualLength);
    StackFrame.getFrames(rvmThread, frames, start);
    ArrayList<VMFrame> list = new ArrayList<VMFrame>(); 
    for(int i = 0; i < frames.getCount();i++) {
      int depth = start + i;
      VMMethod m = new VMMethod(frames.getMethod(i));
      Location loc = new Location(m, frames.getLocation(i));
      VMFrame f = new VMFrame(depth, loc, rvmThread);
      list.add(f);
    }
    return list;
  }

  /** Retrieve a call frame from the thread and the frame identifier. */
  public static VMFrame getFrame(final Thread thread, long frameID)
      throws JdwpException {    
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    final RVMThread rvmThread = JikesRVMSupport.getThread(thread);
    if (VM.VerifyAssertions) {
      VM._assert(rvmThread != null);
    }

    int threadID = (int) (frameID >>> 32);
    final int depth = (int) (frameID & 0x0FFFFFFFFL);
    if (threadID != rvmThread.getIndex()) {
      throw new InvalidFrameException(frameID);
    }
    FrameInfoList frames = new FrameInfoList(1);
    StackFrame.getFrames(rvmThread, frames, depth);
    VMFrame f = null;
    if (frames.getCount() >= 1) {
      VMMethod m = new VMMethod(frames.getMethod(0));
      Location loc = new Location(m, frames.getLocation(0));
      f = new VMFrame(depth, loc, rvmThread);
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("getFrame", rvmThread.getName(), " frameID = ", (int) frameID);
    }
    return f;
  }

  /** Count the number of call frames in a suspended thread. */
  public static int getFrameCount(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    //perform call stack walk.
    final RVMThread rvmThread = JikesRVMSupport.getThread(thread);
    if (VM.VerifyAssertions) {
      VM._assert(rvmThread != null);
    }
    int count = StackFrame.getFrameCount(rvmThread);

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("getFrameCount: ", rvmThread.getName(), " count = ",
          count);
    }
    return count;
  }

  /** TODO: needs more inspection. Get a list of requested classes .*/
  public static ArrayList<Class<?>> getLoadRequests(final ClassLoader cl)
      throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("getLoadRequests: ");
    }
    Class<?>[] classes = JikesRVMSupport.getInitiatedClasses(cl);
    return new ArrayList<Class<?>>(Arrays.asList(classes));
  }

  /** Execute a Java method in a suspended thread.*/
  public static MethodResult executeMethod(Object obj, Thread thread,
      Class<?> clazz, VMMethod method, Value[] values, int options)
      throws JdwpException {
    return vm().executeMethod(obj, thread, clazz, method, values, options);
  }

  /** Get the source file name for a class. */
  public static String getSourceFile(Class<?> clazz) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    RVMClass vmClass = getRVMClass(clazz);
    String rValue = vmClass.getSourceName().toString();
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWrite("getSourceFile ", clazz.getName());
      VM.sysWriteln(" , Source file name = ", rValue);
    }
    return rValue;
  };

  /** Set a JDWP event. */
  public static void registerEvent(final EventRequest request)
      throws JdwpException {
    vm().registerEvent(request);
  }

  /** Clear a JDWP event. */
  public static void unregisterEvent(EventRequest request) throws JdwpException {
    vm().unregisterEvent(request);
  };

  /** Clear all the JDWP event. */
  public static void clearEvents(byte kind) throws JdwpException {
    vm().clearEvents(kind);
  };

  /** 
   * Redefine a class byte code.
   * This optional feature will not be implemented.
   * @see VMVirtualMachine#canRedefineClasses 
   */
  public static void redefineClasses(Class<?>[] types, byte[][] bytecodes)
      throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    throw new NotImplementedException("redefineClasses");
  };

  /**
   * Set the default stratum.
   * This optional feature will not be implemented.
   * @see VMVirtualMachine#canSetDefaultStratum
   */
  public static void setDefaultStratum(String stratum) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    throw new NotImplementedException("setDefaultStratum");
  };

  /**
   * Get the source debug extension attribute for a class.
   * This optional feature will not be implemented.
   * 
   * @see VMVirtualMachine#canGetSourceDebugExtension 
   */
  public static String getSourceDebugExtension(Class<?> klass)
      throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    throw new NotImplementedException("getSourceDebugExtension");
  }

  /**
   * Get a byte codes for a method. This optional feature will not be
   * implemented, but the implementation is trivial.
   * @see VMVirtualMachine#canGetBytecodes
   */
  public static final byte[] getBytecodes(VMMethod method) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    throw new NotImplementedException("getBytecodes");
  }

  /** 
   * Get a monitor info.
   * This optional feature will not be implemented.
   * @see VMVirtualMachine#canGetOwnedMonitorInfo
   */
  public static MonitorInfo getMonitorInfo(Object obj) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    throw new NotImplementedException("getMonitorInfo");
  }

  /** 
   * Get a owned monitor.
   * This optional feature will not be implemented.
   * @see VMVirtualMachine#canGetOwnedMonitorInfo
   */
  public static Object[] getOwnedMonitors(Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    throw new NotImplementedException("getOwnedMonitors");
  }

  /** 
   * Get a current contended monitor.
   * This optional feature will not be implemented.
   * 
   * @see VMVirtualMachine#canGetCurrentContendedMonitor
   */
  public static Object getCurrentContendedMonitor(Thread thread)
      throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    throw new NotImplementedException("getCurrentContendedMonitor");
  };

  /**
   * Pop all the frames until the given frame in a suspended thread.
   * This optional feature will not be implemented.
   * @see VMVirtualMachine#canPopFrames
   */
  public static void popFrames(Thread thread, long frameId)
      throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    throw new NotImplementedException("popFrames");
  }

  /**
   * Notify the RVM of the Agent thread.
   * 
   * @param thread The agent thread.
   */
  public static void setAgentThread(Thread thread) {
    RVMThread rvmThread = JikesRVMSupport.getThread(thread);
    if (VM.VerifyAssertions) {
      VM._assert(rvmThread != null);
    }
    Threads.setAgentThread(rvmThread);
  }

  /** Get the class status. */
  @Uninterruptible
  static int getStatus(RVMType vmType) {
    if (VM.VerifyAssertions) {
      VM._assert(vmType != null);
    }
    // At this time, the clazz is at least Linking-verified state
    // [ch 5.4.1, 2nd JVMSPEC]. This non-null clazz value indicates that the
    // class ware successfully loaded. The JDWP does not show if the class is loaded 
    // or not here since calling this routine automically implies that the class was 
    // loaded. Since the JikesRVM skips the verification
    // process. The clazz is already at least the verification stage.
    int rValue = 0 | JdwpConstants.ClassStatus.VERIFIED; // the return state value.
    if (vmType.isResolved()) {
      // RVMType.resolve() includes the preparation by allocating JTOC entries. 
      // The JVM specification says: 
      // "Preparation involves creating the static fields for the class or interface and 
      // initializing those fields to their standard default values"
      //
      rValue |= JdwpConstants.ClassStatus.PREPARED;
        if (vmType.isInitialized()) {
          rValue |= JdwpConstants.ClassStatus.INITIALIZED;
        }
    }
    return rValue;
  }
  /** Helper method for the class and rvmtype mapping. */
  private static RVMType getRVMType(final Class<?> clazz) throws JdwpException {
    if (null == clazz) {
      throw new InvalidClassException(-1);
    }
    RVMType vmType = JikesRVMSupport.getTypeForClass(clazz);
    if (VM.VerifyAssertions) {
      VM._assert(vmType != null && vmType instanceof Primitive == false);
    }
    return vmType;
  }

  /** Helper method for the class and rvmclass mapping. */
  private static RVMClass getRVMClass(final Class<?> clazz) throws JdwpException {
    RVMType vmType = getRVMType(clazz);
    return (RVMClass)vmType;
  }
}
