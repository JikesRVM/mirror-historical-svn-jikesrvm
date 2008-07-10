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
import gnu.classpath.jdwp.event.ThreadStartEvent;
import gnu.classpath.jdwp.event.VmDeathEvent;
import gnu.classpath.jdwp.event.VmInitEvent;
import gnu.classpath.jdwp.event.filters.IEventFilter;
import gnu.classpath.jdwp.event.filters.LocationOnlyFilter;
import gnu.classpath.jdwp.exception.InvalidClassException;
import gnu.classpath.jdwp.exception.InvalidEventTypeException;
import gnu.classpath.jdwp.exception.InvalidFrameException;
import gnu.classpath.jdwp.exception.InvalidThreadException;
import gnu.classpath.jdwp.exception.JdwpException;
import gnu.classpath.jdwp.exception.NotImplementedException;
import gnu.classpath.jdwp.util.Location;
import gnu.classpath.jdwp.util.MethodResult;
import gnu.classpath.jdwp.util.MonitorInfo;
import gnu.classpath.jdwp.value.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import org.jikesrvm.VM;
import org.jikesrvm.Callbacks;
import org.jikesrvm.debug.BreakPointManager;
import org.jikesrvm.classloader.MemberReference;
import org.jikesrvm.classloader.MethodReference;
import org.jikesrvm.classloader.NativeMethod;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.Primitive;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.compilers.baseline.BaselineCompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.compilers.opt.runtimesupport.OptCompiledMethod;
import org.jikesrvm.compilers.opt.runtimesupport.OptEncodedCallSiteTree;
import org.jikesrvm.compilers.opt.runtimesupport.OptMachineCodeMap;
import org.jikesrvm.debug.JikesRVMJDWP;
import org.jikesrvm.jni.JNICompiledMethod;
import org.jikesrvm.memorymanagers.mminterface.MM_Interface;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.Scheduler;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.util.HashMapRVM;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
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

  /** The VM event call back handler instance. */
  private static final JDWPEventNotifier jdwpNotifier = 
    new JDWPEventNotifier();

  /** The JDWP specific user thread information. */
  private static final HashMapRVM<RVMThread, ThreadInfo> threadInfo = 
    new HashMapRVM<RVMThread, ThreadInfo>();

  /** The internal initialization process. */
  public static void boot() {
    Callbacks.addStartupMonitor(jdwpNotifier);
    Callbacks.addExitMonitor(jdwpNotifier);
    Callbacks.addClassResolvedMonitor(jdwpNotifier);
    Callbacks.addThreadStartedMonitor(jdwpNotifier);
    Callbacks.addThreadEndMonitor(jdwpNotifier);
    BreakPointManager.init(jdwpNotifier);
  }

  /** Get JDWP thread info for a user thread. */
  private static ThreadInfo getThreadInfo(Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {VM._assert(thread != null);}
    RVMThread vmthread = JikesRVMSupport.getThread(thread);
    if (!isDebugeeThread(vmthread))
      throw new InvalidThreadException(-1);
    if (VM.VerifyAssertions) {VM._assert(isDebugeeThread(vmthread));}
    ThreadInfo ti = threadInfo.get(vmthread);
    if (VM.VerifyAssertions) {VM._assert(ti != null);}
    return ti;
  }

  /** Suspend a debugee thread. */
  public static void suspendThread(final Thread thread) throws JdwpException {
    getThreadInfo(thread).suspendThread();
  }

  /** Suspend all the debuggee threads. */
  public static void suspendAllThreads() throws JdwpException {
    for (final RVMThread vmThread : threadInfo.keys()) {
      Thread t = vmThread.getJavaLangThread();
      suspendThread(t);
    }
  }

  /** Resume a debugee thread. */
  public static void resumeThread(final Thread thread) throws JdwpException {
    getThreadInfo(thread).resumeThread();
  }

  /** Resume all threads. */
  public static void resumeAllThreads() throws JdwpException {
    for (final RVMThread vmThread : threadInfo.keys()) {
      Thread t = vmThread.getJavaLangThread();
      resumeThread(t);
    }
  }

  /** Get the suspend count for a thread. */
  public static int getSuspendCount(final Thread thread) throws JdwpException {
    return getThreadInfo(thread).getSuspendCount();
  };

  /** Returns the status of a thread. */
  public static int getThreadStatus(final Thread thread) throws JdwpException {
    ThreadInfo ti = getThreadInfo(thread);
    RVMThread vmThread = ti.thread;
    // using switch statement does not work here since
    // the javac takes the enum integer constant values from
    // its JDK Thread.State, and Thread.State enum constants values in
    // the JDK Threads.State and the GNU Thread.State could be different.
    // This actually happened with JDK 1.6.
    final int status;
    Thread.State s = vmThread.getState();
    if (s == Thread.State.BLOCKED) {
      status = JdwpConstants.ThreadStatus.MONITOR;
    } else if (s == Thread.State.NEW) {
      status = JdwpConstants.ThreadStatus.RUNNING;
    } else if (s == Thread.State.RUNNABLE) {
      status = JdwpConstants.ThreadStatus.RUNNING;
    } else if (s == Thread.State.TERMINATED) {
      status = JdwpConstants.ThreadStatus.ZOMBIE;
    } else if (s == Thread.State.TIMED_WAITING) {
      status = JdwpConstants.ThreadStatus.SLEEPING;
    } else if (s == Thread.State.WAITING) {
      status = JdwpConstants.ThreadStatus.WAIT;
    } else {
      status = -1;
      VM.sysFail("Unknown thread state " + s);
    }

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWrite("getThreadStatus:", thread.getName(), " vmthread status =",
          vmThread.getState().toString());
      VM.sysWriteln(" jdwpstatus =", getThreadStateName(status));
    }
    return status;
  }

  /** Returns the number of loaded classes in the VM. */
  public static int getAllLoadedClassesCount() throws JdwpException {
    final int rValue = getAllLoadedClasses().size();
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("getAllLoadedClassesCount: ", rValue);
    }
    return rValue;
  };

  /** Return all loaded classes. */
  public static Collection<Class<?>> getAllLoadedClasses() throws JdwpException {
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("getAllLoadedClasses: ");
    }
    // TODO: This only does not give the system classes loaded by the bootstrap
    // class. We might want to export some of these essential java classes such
    // as Object, Class, Throwable, Error and etc.
    Class<?>[] loadedClasses = JikesRVMSupport.getAllLoadedClasses();
    return Arrays.asList(loadedClasses);
  };

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
  
  /** Return the class status. */
  public static int getClassStatus(final Class<?> clazz) throws JdwpException {
    int status =  getStatus(getRVMType(clazz));
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWrite("getClassStatus:", clazz.getName());
      VM.sysWriteln(" value = ", status);
    }
    return status;
  }

  /** Get the class status. */
  private static int getStatus(RVMType vmType) {
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

  /** Get the all the declared method in a class. */
  public static VMMethod[] getAllClassMethods(final Class<?> clazz)
      throws JdwpException {
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
    // "Returns the current call stack of a suspended thread. The sequence of
    // frames starts with the currently executing frame, followed by its caller,
    // and so on. The thread must be suspended, and the returned frameID is
    // valid only while the thread is suspended."
    // [jdwp-protocol.html#JDWP_ThreadReference_Frames]
    ThreadInfo ti = getThreadInfo(thread);
    if (ti.getSuspendCount() <= 0 ) {
      throw new InvalidThreadException(-1);
    }
    if (start < 0 || length < -1) {
      return new ArrayList<VMFrame>(); // simply return empty frame list.
    }
    
    //perform call stack walk.
    final RVMThread vmThread = ti.thread;
    final ArrayList<VMFrame> vmFrames = new ArrayList<VMFrame>();
    ti.stackWalk(new StackFrameVisitor() {
      int countDown = start; 
      int remaining = length; /** remaining frames to get. */
      boolean visit(int fno, NormalMethod m, int bcIndex,
          BaselineCompiledMethod bcm, Offset ip, Offset fp) {
        if (countDown > 0) {countDown--;return true;}
        else if (length != -1 && remaining <= 0) { return false;}
        else { remaining--;}
        VMFrame f = new VMBaseFrame(fno, m, bcIndex, vmThread, bcm, ip, fp);
        vmFrames.add(f);
        return true;
      }
      boolean visit(int fno, NormalMethod m, int bci,
          OptCompiledMethod ocm, Offset ip, Offset fp, int iei) {
        if (countDown > 0) {countDown--;return true;}
        else if (length != -1 && remaining <= 0) {return false;}
        else {remaining--;}
        VMFrame f = new VMOptFrame(fno, m, bci, vmThread, ocm, ip, fp, iei);
        vmFrames.add(f);
        return true;
      }
      boolean visit(int frameno, NativeMethod m) {
        if (countDown > 0) {countDown--;return true;} 
        else if (length != -1 && remaining <= 0) {return false;}
        else {remaining--;}
        VMFrame f = new VMNativeFrame(frameno, m, vmThread);
        vmFrames.add(f);
        return true;
      }
    });

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("getFrames(" + start + ", " + length + ")");
    }
    return vmFrames;
  }

  /** Retrieve a call frame from the thread and the frame identifier. */
  public static VMFrame getFrame(final Thread thread, long frameID)
      throws JdwpException {
    ThreadInfo ti = getThreadInfo(thread);
    if (ti.getSuspendCount() <= 0 ) {
      throw new InvalidThreadException(-1);
    }
    
    //perform call stack walk.
    final RVMThread vmThread = ti.thread;
    int threadID = (int) (frameID >>> 32);
    final int fid = (int) (frameID & 0x0FFFFFFFFL);
    if (threadID != vmThread.getIndex()) {
      throw new InvalidFrameException(frameID);
    }
    final class FrameRef { VMFrame f;}
    final FrameRef fref = new FrameRef();
    ti.stackWalk(new StackFrameVisitor() {
      boolean visit(int fno, NormalMethod m, int bcIndex,
          BaselineCompiledMethod bcm, Offset ip, Offset fp) {
        if (fno == fid) {
          fref.f = new VMBaseFrame(fno, m, bcIndex, vmThread, bcm, ip, fp);
          return false;
        } else {return true;}
      }
      boolean visit(int fno, NormalMethod m, int bcIndex,
          OptCompiledMethod ocm, Offset ip, Offset fp, int iei) {
        if (fno == fid) {
          fref.f = new VMOptFrame(fno, m, bcIndex, vmThread, ocm, ip, fp, iei);
          return false;
        } else {return true;}
      }
      boolean visit(int fno, NativeMethod m) {
        if (fno == fid) {
          fref.f = new VMNativeFrame(fno, m, vmThread);
          return false;
        } else {return true;}
      }
    });

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("getFrame", thread.getName(), " frameID = ", (int) frameID);
    }
    return fref.f;
  }

  /** Count the number of call frames in a suspended thread. */
  public static int getFrameCount(final Thread thread) throws JdwpException {
    ThreadInfo ti = getThreadInfo(thread);
    if (ti.getSuspendCount() <= 0 ) {
      throw new InvalidThreadException(-1);
    }
    //perform call stack walk.
    final RVMThread vmThread = ti.thread;
    final class FrameCount {int count;}
    final FrameCount fcnt = new FrameCount();
    ti.stackWalk(new StackFrameVisitor() {
      boolean visit(int frameno, NormalMethod m, int bcIndex,
          BaselineCompiledMethod bcm, Offset ip, Offset fp) {
        fcnt.count++;
        return true;
      }
      boolean visit(int frameno, NormalMethod m, int bcIndex,
          OptCompiledMethod ocm, Offset ip, Offset fp, int iei) {
        fcnt.count++;
        return true;
      }
      boolean visit(int frameno, NativeMethod m) {
        fcnt.count++;
        return true;
      }
    });

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("getFrameCount: ", thread.getName(), " count = ",
          fcnt.count);
    }
    return fcnt.count;
  }

  /** TODO: needs more inspection. Get a list of requested classes .*/
  public static ArrayList<Class<?>> getLoadRequests(final ClassLoader cl)
      throws JdwpException {
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("getLoadRequests: ");
    }
    Class<?>[] classes = JikesRVMSupport.getInitiatedClasses(cl);
    return new ArrayList<Class<?>>(Arrays.asList(classes));
  };

  /** TODO: to-be-implemented. Execute a Java method in a suspended thread. */
  public static MethodResult executeMethod(Object obj, Thread thread,
      Class<?> clazz, VMMethod method, Value[] values, int options)
      throws JdwpException {
    throw new NotImplementedException("executeMethod");
  }

  /** Get the source file name for a class. */
  public static String getSourceFile(Class<?> clazz) throws JdwpException {
    RVMClass vmClass = getRVMClass(clazz);
    String rValue = vmClass.getSourceName().toString();
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWrite("getSourceFile ", clazz.getName());
      VM.sysWriteln(" , Source file name = ", rValue);
    }
    return rValue;
  };

  /** Set a JDWP event. */
  @SuppressWarnings("unchecked")
  public static void registerEvent(final EventRequest request)
      throws JdwpException {

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWrite("registerEvent [ID = ", request.getId());
      VM.sysWrite(" Event = ", getEventKindName(request.getEventKind()));
      VM.sysWriteln(" Suspend =  ", request.getSuspendPolicy(), " ]");
    }

    final byte eventKind = request.getEventKind();
    switch (eventKind) {
    case JdwpConstants.EventKind.SINGLE_STEP: {
      VM.sysWriteln("SINGLE_STEP event is not implemented");
      throw new NotImplementedException("SINGLE_STEP event is not implemented");
    }
    case JdwpConstants.EventKind.BREAKPOINT: {
      jdwpNotifier.setBreakPointEnabled(true);
      Collection<IEventFilter> filters = (Collection<IEventFilter>)request.getFilters();
      for(final IEventFilter filter : filters) {
        if (filter instanceof LocationOnlyFilter) {
          LocationOnlyFilter locFilter = (LocationOnlyFilter)filter;
          Location loc = locFilter.getLocation();
          RVMMethod m = loc.getMethod().meth;
          int bcIndex = (int)loc.getIndex();
          if (m instanceof NormalMethod && bcIndex >= 0 ) {
            NormalMethod method = (NormalMethod) m;
            BreakPointManager.getBreakPointManager().setBreakPoint(method, bcIndex);
          }
        } else {
          // TODO: what are possible filters?
          if (JikesRVMJDWP.getVerbose() >= 1) {
            VM.sysWriteln("ignoring event filter: ", filter.getClass().toString());
          }
        }
      }
      break;
    }
    case JdwpConstants.EventKind.FRAME_POP: {
      /* @see gnu.classpath.jdwp.VMVirtuualMachine#canPopFrames Disabled. */
      VM.sysWriteln("FRAME_POP event is not implemented");
      throw new NotImplementedException("FRAME_POP event is not implemented");
    }
    case JdwpConstants.EventKind.EXCEPTION: {
      jdwpNotifier.setExceptionCatchEnabled(true);
      break;
    }
    case JdwpConstants.EventKind.USER_DEFINED: {
      VM.sysWriteln("USER_DEFINED event is not implemented");
      throw new NotImplementedException("USER DEFINED event is not implemented");
    }
    case JdwpConstants.EventKind.THREAD_START: {
      jdwpNotifier.setThreadStartEnabled(true);
      break;
    }
    case JdwpConstants.EventKind.THREAD_END: {
      jdwpNotifier.setThreadEndEnabled(true);
      break;
    }
    case JdwpConstants.EventKind.CLASS_PREPARE: {
      jdwpNotifier.setClassPrepareEnabled(true);
      break;
    }
    case JdwpConstants.EventKind.CLASS_UNLOAD: {
      //TODO: Does JikesRVM actually perform class unloading?
      if (JikesRVMJDWP.getVerbose() >= 1) {
        VM.sysWriteln("pretend as if implemented(CLASS_UNLOAD).");
      }
      break;
    }
    case JdwpConstants.EventKind.CLASS_LOAD: {
      // CLASS_LOAD is in the JDWP.EventKind, but there is no specification 
      // for the CLASS_LOAD notification. Therefore, leave this request as
      // invalid event request.
      if (JikesRVMJDWP.getVerbose() >= 1) {
        VM.sysWriteln("CLASS_LOAD event is not valid event type.");
      }
      throw new InvalidEventTypeException(eventKind);
    }
    case JdwpConstants.EventKind.FIELD_ACCESS: {
      // @see gnu.classpath.jdwp.VMVirtualMachine#canWatchFieldAccess Disabled.
      if (JikesRVMJDWP.getVerbose() >= 1) {
        VM.sysWriteln("FIELD_ACCESS event is not implemented.");
      }
      throw new NotImplementedException("FIELD_ACCESS");
    }
    case JdwpConstants.EventKind.FIELD_MODIFICATION: {
      // @see gnu.classpath.jdwp.VMVirtualMachine#canWatchFieldModification
      if (JikesRVMJDWP.getVerbose() >= 1) {
        VM.sysWriteln("FIELD_MODIFICATION event is not implemented.");
      }
      throw new NotImplementedException("FIELD_MODIFICATION");
    }
    case JdwpConstants.EventKind.EXCEPTION_CATCH: {
      if (JikesRVMJDWP.getVerbose() >= 1) {
        VM.sysWriteln("EXCEPTION_CATCH event is not implemented.");
      }
      throw new NotImplementedException("EXCEPTION_CATCH");
    }
    case JdwpConstants.EventKind.METHOD_ENTRY: {
      // TODO: to-be-implemented. Maybe I'd better not to implement
      // this if this event type allows all the method entry during
      // the execution. choice1: patching brea point instruction for
      // each method prologue. --> too many methods? choice2:
      // piggy-back yield point proloue. --> this may actually turn
      // out to be nasty? choice3: generate code to check a flag at
      // the method prologue? --> maybe practical?
      VM.sysWriteln("METHOD_ENTRY event is not implemented.");
      throw new NotImplementedException("METHOD_ENTRY");
    }
    case JdwpConstants.EventKind.METHOD_EXIT: {
      // TODO: to-be-implemented. The same issues as the entry.
      VM.sysWriteln("METHOD_EXIT event is not implemented.");
      throw new NotImplementedException("METHOD_EXIT");
    }
    case JdwpConstants.EventKind.VM_START: {
      // ignore the VM_START enable/disable.
      // "This event is always generated by the target VM, even if not explicitly requested. "
      // [http://java.sun.com/javase/6/docs/jpda/
      // jdwp/jdwp-protocol.html#JDWP_EventRequest_Clear]
      break;
    }
    case JdwpConstants.EventKind.VM_DEATH: {
      jdwpNotifier.setVmDeathEnabled(true);
      break;
    }
    case JdwpConstants.EventKind.VM_DISCONNECTED: {
      // "VM_DISCONNECTED 100 Never sent across JDWP"
      // [http://java.sun.com/j2se/1.5.0/docs/guide/jpda/jdwp/jdwp-protocol.html]
      VM.sysWriteln("The JDWP channel can not request VM_DISCONNECTED event ");
      throw new InvalidEventTypeException(eventKind);
    }
    default: {
      VM.sysWriteln("Can not recognize the event type ", eventKind);
      throw new InvalidEventTypeException(eventKind);
    }
    }
  }

  /** Clear a JDWP event. */
  @SuppressWarnings("unchecked")
  public static void unregisterEvent(EventRequest request) throws JdwpException {
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWrite("unregisterEvent [ID = ", request.getId());
      VM.sysWrite(" Event = ", getEventKindName(request.getEventKind()));
      VM.sysWriteln(" Suspend =  ", request.getSuspendPolicy(), " ]");
    }

    //TODO: to-be-implemented. Do need to do actually anything? This
    //is seems to be only performance issue. The JDWP's event matching
    //logic will automacally deal with uninteresting events.
    final byte eventKind = request.getEventKind();
    switch (eventKind) {
    case JdwpConstants.EventKind.SINGLE_STEP: {
      VM.sysWriteln("SINGLE_STEP event is not implemented");
      throw new NotImplementedException("SINGLE_STEP event is not implemented");
    }
    case JdwpConstants.EventKind.BREAKPOINT: {
      Collection<IEventFilter> filters = (Collection<IEventFilter>)request.getFilters();
      for(final IEventFilter filter : filters) {
        if (filter instanceof LocationOnlyFilter) {
          LocationOnlyFilter locFilter = (LocationOnlyFilter)filter;
          Location loc = locFilter.getLocation();
          RVMMethod m = loc.getMethod().meth;
          int bcIndex = (int)loc.getIndex();
          if (m instanceof NormalMethod && bcIndex >= 0 ) {
            NormalMethod method = (NormalMethod) m;
            BreakPointManager manager = BreakPointManager.getBreakPointManager();
            manager.clearBreakPoint(method, bcIndex);
          }
        }
      }
      break;
    }
    case JdwpConstants.EventKind.FRAME_POP: {
      VM.sysWriteln("FRAME_POP event is not implemented");
      throw new NotImplementedException("FRAME_");
    }
    case JdwpConstants.EventKind.EXCEPTION: {
      VM.sysWriteln("EXCEPTION event is not implemented");
      throw new NotImplementedException("EXCEPTION");
    }
    case JdwpConstants.EventKind.USER_DEFINED: {
      VM.sysWriteln("USER_DEFINED event is not implemented");
      throw new NotImplementedException("USER_DEFINED");
    }
    case JdwpConstants.EventKind.THREAD_START: {
      VM.sysWriteln("THREAD_START is not implemented");
      throw new NotImplementedException("THREAD_START");
    }
    case JdwpConstants.EventKind.THREAD_END: {
      VM.sysWriteln("THREAD_END is not implemented");
      throw new NotImplementedException("THREAD_END");
    }
    case JdwpConstants.EventKind.CLASS_PREPARE: {
      if (JikesRVMJDWP.getVerbose() >= 1) {
        VM.sysWriteln("ignore unregister(CLASS_PREPARE)");
      }
      break;
    }
    case JdwpConstants.EventKind.CLASS_UNLOAD: {
      VM.sysWriteln("CLASS_UNLOAD is not implemented");
      throw new NotImplementedException("CLASS_UNLOAD");
    }
    case JdwpConstants.EventKind.CLASS_LOAD: {
      VM.sysWriteln("CLASS_LOAD event is not implemented");
      throw new NotImplementedException("CLASS_LOAD");
    }
    case JdwpConstants.EventKind.FIELD_ACCESS: {
      VM.sysWriteln("FIELD_ACCESS event is not implemented");
      throw new NotImplementedException("FIELD_ACCESS");
    }
    case JdwpConstants.EventKind.FIELD_MODIFICATION: {
      VM.sysWriteln("FIELD_MODIFICATION event is not implemented");
      throw new NotImplementedException("FIELD_MODIFICATION");
    }
    case JdwpConstants.EventKind.EXCEPTION_CATCH: {
      VM.sysWriteln("EXCEPTION_CATCH event is not implemented");
      throw new NotImplementedException("EXCEPTION_CATCH");
    }
    case JdwpConstants.EventKind.METHOD_ENTRY: {
      VM.sysWriteln("METHOD_ENTRY event is not implemented");
      throw new NotImplementedException("METHOD_ENTRY");
    }
    case JdwpConstants.EventKind.METHOD_EXIT: {
      VM.sysWriteln("METHOD_EXIT event is not implemented");
      throw new NotImplementedException("METHOD_EXIT");
    }
    case JdwpConstants.EventKind.VM_START: {
      VM.sysWriteln("VM_START event is not implemented");
      throw new NotImplementedException("VM_START");
    }
    case JdwpConstants.EventKind.VM_DEATH: {
      VM.sysWriteln("VM_DEATH event is not implemented");
      throw new NotImplementedException("VM_DEATH");
    }
    case JdwpConstants.EventKind.VM_DISCONNECTED: {
      VM.sysWriteln("VM_DISCONNECTED event is not implemented");
      throw new NotImplementedException(
          "VM_DISCONNECTED event is not implemented");
    }
    default: {
      VM.sysWriteln("Can not recognize the event type ", eventKind);
      throw new InvalidEventTypeException(eventKind);
    }
    }
  };

  /** Clear all the JDWP event. */
  public static void clearEvents(byte kind) throws JdwpException {
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("clearEvents[Event: ", getEventKindName(kind));
    }
    switch (kind) {
    case JdwpConstants.EventKind.SINGLE_STEP: {
      //TODO:to-be-implemented.
      VM.sysWriteln("SINGLE_STEP event is not implemented");
      throw new NotImplementedException("SINGLE_STEP");
    }
    case JdwpConstants.EventKind.BREAKPOINT: {
      //TODO:to-be-implemented.
      VM.sysWriteln("BREAKPOINT event is not implemented");
      throw new NotImplementedException("BREAKPOINT");
    }
    case JdwpConstants.EventKind.FRAME_POP: {
      // This optional feature will not be implemented.
      VM.sysWriteln("FRAME_POP event is not implemented");
      throw new NotImplementedException("FRAME_POP");
    }
    case JdwpConstants.EventKind.USER_DEFINED: {
      // This optional feature will not be implemented.
      VM.sysWriteln("USER_DEFINED event is not implemented");
      throw new NotImplementedException("USER_DEFINED");
    }
    case JdwpConstants.EventKind.THREAD_START: {
      jdwpNotifier.setThreadStartEnabled(false);
      break;
    }
    case JdwpConstants.EventKind.THREAD_END: {
      jdwpNotifier.setThreadEndEnabled(false);
      break;
    }
    case JdwpConstants.EventKind.CLASS_PREPARE: {
      jdwpNotifier.setClassPrepareEnabled(false);
      break;
    }
    case JdwpConstants.EventKind.CLASS_UNLOAD: {
      //TODO: Does Jikes RVM unload class?
      VM.sysWriteln("CLASS_UNLOAD event is not implemented");
      throw new NotImplementedException("CLASS_UNLOAD");
    }
    case JdwpConstants.EventKind.CLASS_LOAD: {
      // CLASS_LOAD is in the JDWP.EventKind, but there is no specification 
      // for the CLASS_LOAD notification. Therefore, leave this request as
      // invalid event request.
      if (JikesRVMJDWP.getVerbose() >= 1) {
        VM.sysWriteln("CLASS_LOAD event is not valid event type.");
      }
      throw new InvalidEventTypeException(kind);
    }
    case JdwpConstants.EventKind.FIELD_ACCESS: {
      // This optional feature will not be implemented.
      if (JikesRVMJDWP.getVerbose() >= 1) {
        VM.sysWriteln("FIELD_ACCESS event is not implemented");
      }
      throw new NotImplementedException("FIELD_ACCESS");
    }
    case JdwpConstants.EventKind.FIELD_MODIFICATION: {
      // This optional feature will not be implemented.
      if (JikesRVMJDWP.getVerbose() >= 1) {
        VM.sysWriteln("FIELD_MODIFICATION event is not implemented");
      }
      throw new NotImplementedException("FIELD_MODIFICATION");
    }
    case JdwpConstants.EventKind.EXCEPTION_CATCH: {
      jdwpNotifier.setExceptionCatchEnabled(false);
    }
    case JdwpConstants.EventKind.METHOD_ENTRY: {
      //TODO: Do we want to implemente this?
      VM.sysWriteln("METHOD_ENTRY event is not implemented");
      throw new NotImplementedException("METHOD_ENTRY");
    }
    case JdwpConstants.EventKind.METHOD_EXIT: {
      //TODO: Do we want to implemente this?
      VM.sysWriteln("METHOD_EXIT event is not implemented");
      throw new NotImplementedException("METHOD_EXIT");
    }
    case JdwpConstants.EventKind.VM_START: {
      // ignore the VM_START can not disabled.
      // "This event is always generated by the target VM, even if not explicitly requested. "
      // [http://java.sun.com/javase/6/docs/jpda/
      // jdwp/jdwp-protocol.html#JDWP_EventRequest_Clear]
      break;
    }
    case JdwpConstants.EventKind.VM_DEATH: {
      jdwpNotifier.setVmDeathEnabled(false);
      break;
    }
    case JdwpConstants.EventKind.VM_DISCONNECTED: {
      if (JikesRVMJDWP.getVerbose() >= 1) {
        VM.sysWriteln("VM_DISCONNECTED should not be requested.");
      }
      throw new NotImplementedException(
          "VM_DISCONNECTED event is not implemented");
    }
    default: {
      VM.sysWriteln("Can not recognize the event type ", kind);
      throw new InvalidEventTypeException(kind);
    }
    }
  };

  /** Redefine a class byte code. */
  public static void redefineClasses(Class<?>[] types, byte[][] bytecodes)
      throws JdwpException {
    // This optional feature will not be implemented.
    if (JikesRVMJDWP.getVerbose() >= 1) {
      VM.sysWriteln("redefineClasses is not implemnted!");
    }
    throw new NotImplementedException("redefineClasses");
  };

  /** Set the default stratum. */
  public static void setDefaultStratum(String stratum) throws JdwpException {
    // This optional feature will not be implemented.
    if (JikesRVMJDWP.getVerbose() >= 1) {
      VM.sysWriteln("setDefaultStratum is not implemnted!");
    }
    throw new NotImplementedException("setDefaultStratum");
  };

  /** Get the source debug extension atrribute for a class. */
  public static String getSourceDebugExtension(Class<?> klass)
      throws JdwpException {
    // This optional feature will not be implemented.
    if (JikesRVMJDWP.getVerbose() >= 1) {
      VM.sysWriteln("getSourceDebugExtension is not implemnted!");
    }
    throw new NotImplementedException("getSourceDebugExtension");
  }

  /** Get a byte codes for a method. */
  public static final byte[] getBytecodes(VMMethod method) throws JdwpException {
    // This optional feature will not be implemented, but the
    // implementation seems to be trivial.
    if (JikesRVMJDWP.getVerbose() >= 1) {
      VM.sysWriteln("getBytecodes is not implemnted!");
    }
    throw new NotImplementedException("getBytecodes");
  }

  /** Get a monitor info. */
  public static MonitorInfo getMonitorInfo(Object obj) throws JdwpException {
    // This optional feature will not be implemented.
    if (JikesRVMJDWP.getVerbose() >= 1) {
      VM.sysWriteln("getMonitorInfo is not implemnted!");
    }
    throw new NotImplementedException("getMonitorInfo");
  }

  /** Get a owned monitor. */
  public static Object[] getOwnedMonitors(Thread thread) throws JdwpException {
    // This optional feature will not be implemented.
    if (JikesRVMJDWP.getVerbose() >= 1) {
      VM.sysWriteln("getOwnedMonitors is not implemnted!");
    }
    throw new NotImplementedException("getOwnedMonitors");
  }

  /** Get a current contened monitor. */
  public static Object getCurrentContendedMonitor(Thread thread)
      throws JdwpException {
    // This optional feature will not be implemented.
    if (JikesRVMJDWP.getVerbose() >= 1) {
      VM.sysWriteln("getCurrentContendedMonitor is not implemnted!");
    }
    throw new NotImplementedException("getCurrentContendedMonitor");
  };

  /** Pop all the frames until the given frame in a suspened thread. */
  public static void popFrames(Thread thread, long frameId)
      throws JdwpException {
    // This optional feature will not be implemented.
    if (JikesRVMJDWP.getVerbose() >= 1) {
      VM.sysWriteln("popFrames is not implemnted!");
    }
    throw new NotImplementedException("popFrames");
  }

  /** Checks whether or not a thread is debuggable thread. */
  private static boolean isDebugeeThread(RVMThread thread) {
    if (null == thread) {
      return true;
    }
    final boolean rValue = !isJDWPThread(thread) && !thread.isBootThread()
        && !thread.isDebuggerThread() && !thread.isGCThread()
        && !thread.isSystemThread() && !thread.isIdleThread();
    return rValue;
  };

  /** Whether or not a thread is JDWP work thread. */
  private static boolean isJDWPThread(RVMThread thread) {
    if (thread == null) {
      return false;
    }
    Thread jthread = thread.getJavaLangThread();
    if (jthread == null) {
      return false;
    }
    ThreadGroup jgroup = jthread.getThreadGroup();
    return jgroup == Jdwp.getDefault().getJdwpThreadGroup();
  }

  /** Get a readable JDWP event kind name from the JDWP constant. */
  private static String getEventKindName(final byte kind) {
    switch (kind) {
    case JdwpConstants.EventKind.SINGLE_STEP:
      return "SINGLE_STEP";
    case JdwpConstants.EventKind.BREAKPOINT:
      return "BREAKPOINT";
    case JdwpConstants.EventKind.FRAME_POP:
      return "FRAME_POP";
    case JdwpConstants.EventKind.EXCEPTION:
      return "EXCEPTION";
    case JdwpConstants.EventKind.USER_DEFINED:
      return "USER_DEFINED";
    case JdwpConstants.EventKind.THREAD_START:
      return "THREAD_START";
    case JdwpConstants.EventKind.THREAD_DEATH:
      return "THREAD_DEATH";
    case JdwpConstants.EventKind.CLASS_PREPARE:
      return "CLASS_PREPARE";
    case JdwpConstants.EventKind.CLASS_UNLOAD:
      return "CLASS_UNLOAD";
    case JdwpConstants.EventKind.CLASS_LOAD:
      return "CLASS_LOAD";
    case JdwpConstants.EventKind.FIELD_ACCESS:
      return "FIELD_ACCESS";
    case JdwpConstants.EventKind.FIELD_MODIFICATION:
      return "FIELD_MODIFICATION";
    case JdwpConstants.EventKind.EXCEPTION_CATCH:
      return "EXCEPTION_CATCH";
    case JdwpConstants.EventKind.METHOD_ENTRY:
      return "METHOD_ENTRY";
    case JdwpConstants.EventKind.METHOD_EXIT:
      return "METHOD_EXIT";
    case JdwpConstants.EventKind.VM_START:
      return "VM_START";
    case JdwpConstants.EventKind.VM_DEATH:
      return "VM_DEATH";
    case JdwpConstants.EventKind.VM_DISCONNECTED:
      return "VM_DISCONNECTED";
    default:
      return "unknown";
    }
  }

  /** Get a readable JDWP thraed status name from the JDWP constant. */
  private static String getThreadStateName(final int status) {
    switch (status) {
    case JdwpConstants.ThreadStatus.RUNNING:
      return "RUNNING";
    case JdwpConstants.ThreadStatus.ZOMBIE:
      return "ZOMBIE";
    case JdwpConstants.ThreadStatus.SLEEPING:
      return "SLEEPING";
    case JdwpConstants.ThreadStatus.MONITOR:
      return "MONITOR";
    case JdwpConstants.ThreadStatus.WAIT:
      return "WAIT";
    default:
      if (VM.VerifyAssertions) {
        VM._assert(false, "should not be reachable");
      }
      return "error";
    }
  }

  /** Wheather or not visible method to the user. */
  private static boolean isDebuggableMethod(RVMMethod m) {
    RVMClass cls = m.getDeclaringClass();
    return !cls.getDescriptor().isBootstrapClassDescriptor();
  }

  /** Print the beginning of call frame. */
  private static void showPrologue(Address ip, Address fp) {
    VM.sysWrite("   at [ip = ");
    VM.sysWrite(ip);
    VM.sysWrite(", fp = ");
    VM.sysWrite(fp);
    VM.sysWrite("] ");
  }

  /** Print the hardware trap frame. */
  private static void showHardwareTrapMethod(Address fp) {
    VM.sysWrite("   at [fp ");
    VM.sysWrite(fp);
    VM.sysWriteln("] Hardware trap");
  }

  /** Print a stack frame for the native method. */
  private static void showMethod(NativeMethod method, Address ip, Address fp) {
    showPrologue(ip, fp);
    if (method == null) {
      VM.sysWrite("<unknown method>");
    } else {
      VM.sysWrite(method.getDeclaringClass().getDescriptor());
      VM.sysWrite(" ");
      VM.sysWrite(method.getName());
      VM.sysWrite(method.getDescriptor());
    }
    VM.sysWrite("\n");
  }

  /** Print a stack frame for the Java method. */
  private static void showMethod(RVMMethod method, int bcindex, Address ip,
      Address fp) {
    showPrologue(ip, fp);
    if (method == null) {
      VM.sysWrite("<unknown method>");
    } else {
      VM.sysWrite(method.getDeclaringClass().getDescriptor());
      VM.sysWrite(" ");
      VM.sysWrite(method.getName());
      VM.sysWrite(method.getDescriptor());
    }
    if (bcindex >= 0) {
      VM.sysWrite(" at bcindex ");
      VM.sysWriteInt(bcindex);
    }
    VM.sysWrite("\n");
  }

  /** JikesRVM call back handler. */
  private static final class JDWPEventNotifier implements
      Callbacks.StartupMonitor, Callbacks.ExitMonitor,
      Callbacks.ThreadStartMonitor, Callbacks.ThreadEndMonitor,
      Callbacks.ClassResolvedMonitor, Callbacks.ExceptionCatchMonitor, 
      BreakPointManager.BreakPointMonitor {
    private JDWPEventNotifier() {}
    /** Event enable flags. */
    private boolean vmExitEnabled = false;

    private boolean classPrepareEnabled = false;

    private boolean threadStartEnabled = false;

    private boolean threadEndEnabled = false;

    private boolean exceptionCatchEnabled = false;

    private boolean breakPointEnabled = false;

    /** Getters/Setters. */
    private synchronized boolean isBreakPointEnabled() {
      return breakPointEnabled;
    }

    private synchronized void setBreakPointEnabled(boolean breakPointEnabled) {
      this.breakPointEnabled = breakPointEnabled;
    }

    private synchronized boolean isVmExitEnabled() {
      return vmExitEnabled;
    }

    private synchronized void setVmDeathEnabled(boolean vmExitEnabled) {
      this.vmExitEnabled = vmExitEnabled;
    }

    private synchronized boolean isClassPrepareEnabled() {
      return classPrepareEnabled;
    }

    private synchronized void setClassPrepareEnabled(
        boolean classPrepareEnabled) {
      this.classPrepareEnabled = classPrepareEnabled;
    }

    private synchronized boolean isThreadStartEnabled() {
      return threadStartEnabled;
    }

    private synchronized void setThreadStartEnabled(
        boolean threadStartEnabled) {
      this.threadStartEnabled = threadStartEnabled;
    }

    private synchronized boolean isThreadEndEnabled() {
      return threadEndEnabled;
    }

    private synchronized void setThreadEndEnabled(boolean threadEndEnabled) {
      this.threadEndEnabled = threadEndEnabled;
    }

    private synchronized boolean isExceptionCatchEnabled() {
      return exceptionCatchEnabled;
    }

    private synchronized void setExceptionCatchEnabled(
        boolean exceptionThrowEnabled) {
      this.exceptionCatchEnabled = exceptionThrowEnabled;
    }

    /** VM call back handlers. */
    public void notifyStartup() {
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("Firing VM_INIT");
      }
      Jdwp.notify(new VmInitEvent(Thread.currentThread()));
    }

    public void notifyExit(int value) {
      if (!isVmExitEnabled()) {
        return;
      }
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("Firing VM_DEATH");
      }
      Jdwp.notify(new VmDeathEvent());
    }

    public void notifyClassResolved(RVMClass vmClass) {
      if (!isClassPrepareEnabled()) {
        return;
      }
      Class<?> clazz = vmClass.getClassForType();
      if (vmClass.getDescriptor().isBootstrapClassDescriptor()) {
        return;
      }
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("Firing CLASS_PREPARE: ", clazz.getName());
      }
      Event event = new ClassPrepareEvent(Thread.currentThread(), clazz,
          getStatus(vmClass));
      Jdwp.notify(event);
    }

    public void notifyThreadStart(RVMThread vmThread) {
      if (VM.VerifyAssertions) {
        VM._assert(threadInfo.get(vmThread) == null);
      }
      if (!isDebugeeThread(vmThread)) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM
              .sysWriteln("THREAD_START: skip system thread ", vmThread
                  .getName());
        }
        return;
      }
      threadInfo.put(vmThread, new ThreadInfo(vmThread));

      if (isThreadStartEnabled()) {
        Thread thread = vmThread.getJavaLangThread();
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("THREAD_START: firing ", thread.getName());
        }
        Event event = new ThreadStartEvent(thread);
        Jdwp.notify(event);
      }
    }

    public void notifyThreadEnd(RVMThread vmThread) {
      ThreadInfo ti = threadInfo.get(vmThread);
      if (ti == null) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("THREAD_END: skip system thread ", vmThread.getName());
        }
        return;
      }
      if (VM.VerifyAssertions) {
        VM._assert(isDebugeeThread(vmThread));
      }
      threadInfo.remove(vmThread);
      if (isThreadEndEnabled()) {
        Thread thread = vmThread.getJavaLangThread();
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("THREAD_END: firing : ", thread.getName());
        }
        Event event = new ThreadStartEvent(thread);
        Jdwp.notify(event);
      }
    }

    public void notifyExceptionCatch(Throwable e, NormalMethod sourceMethod,
        int sourceByteCodeIndex, NormalMethod catchMethod,
        int catchByteCodeIndex) {
      RVMThread vmThread = Scheduler.getCurrentThread();
      ThreadInfo ti = threadInfo.get(vmThread);
      if (ti == null) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("NOTIFY_EXCEPTION: skip system thread ", vmThread
              .getName());
        }
        return;
      }
      if (isExceptionCatchEnabled()) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("NOTIFY_EXCEPTION: firing : ", e.toString(), " in ",
              vmThread.getName());
        }
        Thread thread = vmThread.getJavaLangThread();
        Location sourceLoc = new Location(new VMMethod(sourceMethod),
            sourceByteCodeIndex);
        Location catchLoc = catchMethod == null ? null : new Location(
            new VMMethod(catchMethod), catchByteCodeIndex);
        RVMClass rcls = sourceMethod.getDeclaringClass();
        Event event = new ExceptionEvent(e, thread, sourceLoc, catchLoc, rcls
            .getClassForType(), null);
        Jdwp.notify(event);
      }
    }

    public void notifyBreakPointHit(NormalMethod method, int bcindex) {
      if (VM.VerifyAssertions) {
        VM._assert(method != null && bcindex >= 0);
      }
      RVMThread t = Scheduler.getCurrentThread();
      if (!isBreakPointEnabled() || !isDebugeeThread(t)) {
        return;
      }
      Thread bpThread = t.getJavaLangThread();
      if (VM.VerifyAssertions) {
        VM._assert(bpThread != null);
      }
      Location loc = new Location(new VMMethod(method), bcindex);
      Event event = new BreakpointEvent(bpThread, loc, null);
      Jdwp.notify(event);
    }
  }

  /** Stack frame walk call back interface. */
  private static abstract class StackFrameVisitor {
    /** return true if keep visiting frames. */
    abstract boolean visit(int frameno, NormalMethod m, int bcIndex,
        BaselineCompiledMethod bcm, Offset ipOffset, Offset fpOffset);

    /** return true if keep visiting frames. */
    abstract boolean visit(int frameno, NormalMethod m, int bcIndex,
        OptCompiledMethod ocm, Offset ipOffset, Offset fpOffset, int iei);

    /** return true if keep visiting frames. */
    abstract boolean visit(int frameno, NativeMethod m);
  }

  /** JDWP specific thread status. */
  private static final class ThreadInfo {

    /** The observed thread. */
    private final RVMThread thread;

    /** The JDWP suspension count. */
    private int suspendCount = 0;

    ThreadInfo(RVMThread t) {
      if (VM.VerifyAssertions) {
        VM._assert(t != null);
      }
      this.thread = t;
    }

    /** Perform JDWP's thread suspension. */
    public void suspendThread() {
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("suspendThread: name = ", thread.getName(),
            " suspendCount = ", suspendCount);
      }
      Thread jthread = thread.getJavaLangThread();
      if (VM.VerifyAssertions) {
        VM._assert(jthread != null);
      }

      //my thread could be to-be-suspended thread.
      boolean actualSuspend = false;
      synchronized(this) {
        suspendCount++;
        // hold the java.lang.Thread lock before vmthread.suspend().
        if (suspendCount == 1) {
          actualSuspend = true;
        }
      }
      if (actualSuspend) {
        synchronized (jthread) { 
          thread.suspend(); 
        }
      }
    }

    /** Perform JDWP's thread resumption. */
    public void resumeThread() {
      // "Resumes the execution of a given thread. If this thread was not previously 
      // suspended by the front-end, calling this command has no effect. Otherwise, 
      // the count of pending suspends on this thread is decremented. If it is 
      // decremented to 0, the thread will continue to execute."
      //  [jdwp-protocol.html#JDWP_ThreadReference_Suspend]
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("resumeThread: name = ", thread.getName(),
            " suspendCount = ", suspendCount);
      }
      Thread jthread = thread.getJavaLangThread();
      if (VM.VerifyAssertions) {
        VM._assert(jthread != null);
      }
      boolean actualResume = false;
      synchronized(this) {
        if (suspendCount > 0) {
          suspendCount--;
          if (suspendCount == 0) {
            actualResume = true;
          }
        }
      }
      if (actualResume) {
        synchronized (jthread) {
          thread.resume();
        }    
      }
    }

    /** Getter/setters for the suspendCount. */
    private synchronized int getSuspendCount() {
      return suspendCount;
    }

    /**
     * Walk the call frames in the suspended thread. If the thread is suspended,
     * visit call frames.
     */
    private synchronized boolean stackWalk(StackFrameVisitor v) {
      if (suspendCount <= 0) {
        if (JikesRVMJDWP.getVerbose() >= 1) {
          VM.sysWriteln("Reject stack walk on the running thread: ", thread
              .getName());
        }
        return false;
      }
      RVMThread t = thread;
      if (VM.VerifyAssertions) {
        VM._assert(t.isAlive() && t.getState() == Thread.State.WAITING);
        VM._assert(Scheduler.getCurrentThread() != t);
      }
      int fno = 0;
      Address fp = t.contextRegisters.getInnermostFramePointer();
      Address ip = t.contextRegisters.getInnermostInstructionAddress();
      while (Magic.getCallerFramePointer(fp).NE(STACKFRAME_SENTINEL_FP)) {
        if (!MM_Interface.addressInVM(ip)) {
          // skip the nativeframes until java frame or the end.
          while (!MM_Interface.addressInVM(ip) && !fp.NE(STACKFRAME_SENTINEL_FP)) {
            ip = Magic.getReturnAddress(fp);
            fp = Magic.getCallerFramePointer(fp);
          }
          if (VM.BuildForPowerPC) {
            // skip over main frame to mini-frame
            fp = Magic.getCallerFramePointer(fp);
          }
        } else {
          int cmid = Magic.getCompiledMethodID(fp);
          if (cmid == INVISIBLE_METHOD_ID) {
            // skip
          } else {
            CompiledMethod cm = CompiledMethods.getCompiledMethod(cmid);
            if (VM.VerifyAssertions) {
              VM._assert(cm != null, "no compiled method for cmid =" + cmid
                  + " in thread " + t.getName());
            }
            int compilerType = cm.getCompilerType();
            switch (compilerType) {
              case CompiledMethod.TRAP: {
                // skip since this is an artificial frame, and there is no
                // corresponding Java code.
                if (JikesRVMJDWP.getVerbose() >= 4) {showHardwareTrapMethod(fp);}
                break;
              }
              case CompiledMethod.BASELINE: {
                BaselineCompiledMethod bcm = (BaselineCompiledMethod) cm;
                NormalMethod meth = (NormalMethod) cm.getMethod();
                Offset ipOffset = bcm.getInstructionOffset(ip, false);
                int bci = bcm.findBytecodeIndexForInstruction(ipOffset);
                Address stackbeg = Magic.objectAsAddress(t.getStack());
                Offset fpOffset = fp.diff(stackbeg);
                if (JikesRVMJDWP.getVerbose() >= 4) {showMethod(meth,bci,ip,fp);}
                if (isDebuggableMethod(meth)) {
                  if (!v.visit(fno++, meth, bci, bcm, ipOffset, fpOffset)) {
                    return true; // finish frame walk.
                  }
                }
                break;
              }
              case CompiledMethod.OPT: {
                final Address stackbeg = Magic.objectAsAddress(t.getStack());
                final Offset fpOffset = fp.diff(stackbeg);
                OptCompiledMethod ocm = (OptCompiledMethod) cm;
                Offset ipOffset = ocm.getInstructionOffset(ip, false);
                OptMachineCodeMap m = ocm.getMCMap();
                int bci = m.getBytecodeIndexForMCOffset(ipOffset);
                NormalMethod meth = m.getMethodForMCOffset(ipOffset);
                int iei = m.getInlineEncodingForMCOffset(ipOffset);
                if (JikesRVMJDWP.getVerbose() >= 4) {showMethod(meth,bci,ip,fp);}
                if (isDebuggableMethod(meth)) {
                  if (!v.visit(fno++, meth, bci, ocm, ipOffset, fpOffset, iei)) {
                    return true;
                  }
                }
                // visit more inlined call sites.
                int[] e = m.inlineEncoding;
                for (iei = OptEncodedCallSiteTree.getParent(iei, e); 
                     iei >= 0;
                     iei = OptEncodedCallSiteTree.getParent(iei, e)) {
                  int mid = OptEncodedCallSiteTree.getMethodID(iei, e);
                  MethodReference mref = MemberReference.getMemberRef(mid)
                      .asMethodReference();
                  meth = (NormalMethod) mref.getResolvedMember();
                  bci = OptEncodedCallSiteTree.getByteCodeOffset(iei, e);
                  if (JikesRVMJDWP.getVerbose() >= 4) {
                    showMethod(meth, bci, ip, fp);
                  }
                  if (!v.visit(fno++, meth, bci, ocm, ipOffset, fpOffset, iei)) {
                    return true;
                  }
                }
                break;
              }
              case CompiledMethod.JNI: {
                JNICompiledMethod jcm = (JNICompiledMethod) cm;
                NativeMethod meth = (NativeMethod) jcm.getMethod();
                if (JikesRVMJDWP.getVerbose() >= 4) {showMethod(meth, ip, fp);}
                if (isDebuggableMethod(meth)) {
                  if (!v.visit(fno++, meth)) {
                    return true;
                  }
                }
                break;
              }
              default: {
                if (VM.VerifyAssertions) {
                  VM._assert(false, "can not recognize compiler type "
                      + compilerType);
                }
                break;
              }
            }
          }
          ip = Magic.getReturnAddress(fp);
          fp = Magic.getCallerFramePointer(fp);
        }
      }
      return true;
    }
  }
}
