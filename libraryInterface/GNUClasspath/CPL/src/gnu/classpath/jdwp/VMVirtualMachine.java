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
import gnu.classpath.jdwp.transport.JdwpConnection;
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
import org.jikesrvm.classloader.TypeReference;
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
import org.jikesrvm.runtime.Reflection;
import org.jikesrvm.scheduler.Scheduler;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.util.HashMapRVM;
import org.vmmagic.pragma.Uninterruptible;
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


  /** Suspend a debugee thread. */
  public static void suspendThread(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgentOrDebugeeThread(Scheduler.getCurrentThread()));
      VM._assert(isDebugeeThread(JikesRVMSupport.getThread(thread)));
    }

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.suspendThread: ", thread.getName(),
          " in ", Scheduler.getCurrentThread().getName());
    }

    RVMThread suspendThread = JikesRVMSupport.getThread(thread);
    RVMThread currentThread = Scheduler.getCurrentThread();
    if (currentThread == suspendThread) {
      // The thread is debugee thread, and this is myself.
      Thread javaCurrentThread = currentThread.getJavaLangThread(); 
      getThreadInfo(javaCurrentThread).suspendCurrentThread();
    } else {
      //I'm not the to-be-suspended thread.
      getThreadInfo(thread).suspendThread();
    }
  }

  /**
   * Suspend all the debuggee threads. Two calling contexts are considered here.
   * The first one is from VMVirtualMachine.suspend, and the current thread
   * would be PacketProcessor. The second one is from the debugee thread due to
   * SUSPEND_ALL event suspension policy.
   */
  public static void suspendAllThreads() throws JdwpException {
    RVMThread currentThread = Scheduler.getCurrentThread();
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgentOrDebugeeThread(Scheduler.getCurrentThread()));
    }

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.suspendAllThreads:  in ", 
          Scheduler.getCurrentThread().getName());
    }

    //suspend except for currentThread.
    synchronized(threadInfo) {
      for (final RVMThread vmThread : threadInfo.keys()) {
        if (vmThread != Scheduler.getCurrentThread()) {
          Thread t = vmThread.getJavaLangThread();
          getThreadInfo(t).suspendThread();
        }
      }
    }

    if (isDebugeeThread(currentThread)) { 
      Thread javaCurrentThread = currentThread.getJavaLangThread(); 
      getThreadInfo(javaCurrentThread).suspendCurrentThread();
    }
  }

  /** 
   * Resume a debugee thread.
   */
  public static void resumeThread(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
    }

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.resumeThread: ", thread.getName());
    }

    getThreadInfo(thread).resumeThread();
  }

  /**
   * Resume all threads. 
   */
  public static void resumeAllThreads() throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.resumeAllThreads");
    }
    synchronized(threadInfo) {
      for (final RVMThread vmThread : threadInfo.keys()) {
        Thread t = vmThread.getJavaLangThread();
        getThreadInfo(t).resumeThread();
      }
    }
  }

  /**
   * Get the suspend count for a thread. 
   */
  public static int getSuspendCount(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
    }

    int count = getThreadInfo(thread).getSuspendCount(); 
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.getSuspendCount: " + count + " for ",
          thread.getName());
    }

    return count; 
  };

  /** 
   * Returns the status of a thread. 
   */
  public static int getThreadStatus(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
    }
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

  /** Return all loaded classes. */
  public static Collection<Class<?>> getAllLoadedClasses() throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
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
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
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
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
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
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
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
    final ArrayList<VMFrame> vmFrames = new ArrayList<VMFrame>();
    ThreadInfo ti = getThreadInfo(thread);
    final RVMThread vmThread = ti.thread;
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
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
    }
    //perform call stack walk.
    final class FrameRef { VMFrame f;}
    final FrameRef fref = new FrameRef();
      ThreadInfo ti = getThreadInfo(thread);
    final RVMThread vmThread = ti.thread;
    int threadID = (int) (frameID >>> 32);
    final int fid = (int) (frameID & 0x0FFFFFFFFL);
    if (threadID != vmThread.getIndex()) {
      throw new InvalidFrameException(frameID);
    }
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
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
    }
    //perform call stack walk.
    final class FrameCount {int count;}
    final FrameCount fcnt = new FrameCount();

    ThreadInfo ti = getThreadInfo(thread);
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
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
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
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
      VM._assert(isDebugeeThread(JikesRVMSupport.getThread(thread)));
    }
    Object[] argOthers = new Object[values.length];
    for(int i=0; i < argOthers.length;i++) {
      argOthers[i] = toReflectionObject(values[i]);
    }
    MethodInvokeRequest req = new MethodInvokeRequest(method.meth, obj, argOthers, options);
    // enqueue request.
    ThreadInfo ti = getThreadInfo(thread);
    ti.requestMethodInvocation(req);
    if (!req.isInvokeSingleThreaded()) {
      synchronized(threadInfo) {
        for (final RVMThread vmThread : threadInfo.keys()) {
          if (vmThread != ti.thread) {
            threadInfo.get(vmThread).resumeThread();
          }
        }
      }
    }

    // dequeue the result.
    MethodResult result = ti.waitForMethodInovationResult();
    if (!req.isInvokeSingleThreaded()) {
      synchronized(threadInfo) {
        for (final RVMThread vmThread : threadInfo.keys()) {
          if (vmThread != ti.thread) {
            threadInfo.get(vmThread).suspendThread();
          }
        }
      }
    }
    return result;
  }

  /** Get the source file name for a class. */
  public static String getSourceFile(Class<?> clazz) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
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
  @SuppressWarnings("unchecked")
  public static void registerEvent(final EventRequest request)
      throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
    }

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
      // JikesRVM does not unload class. If I return
      // UNIMPLEMENTED. the JDB gives up starting the debugging session. Here,
      // this agent will pretends that the class unloading event is available,
      // but never report the class unloading event since this will never happen
      // before the JikesRVM implements the class unloading.
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
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWrite("unregisterEvent [ID = ", request.getId());
      VM.sysWrite(" Event = ", getEventKindName(request.getEventKind()));
      VM.sysWriteln(" Suspend =  ", request.getSuspendPolicy(), " ]");
    }

    // TODO: to-be-implemented. Do need to do actually anything? This
    // is seems to be only performance issue. The JDWP's event matching
    // logic will automatically deal with uninteresting events.
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
      // JikesRVM does not unload class. If I return
      // UNIMPLEMENTED. the JDB gives up starting the debugging session. Here,
      // this agent will pretends that the class unloading event is available,
      // but never report the class unloading event since this will never happen
      // before the JikesRVM implements the class unloading.
      break;
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
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
    }
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

  /** 
   * Redefine a class byte code.
   * This optional feature will not be implemented.
   * @see VMVirtualMachine#canRedefineClasses 
   */
  public static void redefineClasses(Class<?>[] types, byte[][] bytecodes)
      throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
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
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
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
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
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
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
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
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
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
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
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
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
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
      VM._assert(isJDWPAgent(Scheduler.getCurrentThread()));
    }
    throw new NotImplementedException("popFrames");
  }

  /** Get JDWP thread info for a user thread. */
  private static ThreadInfo getThreadInfo(Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {VM._assert(thread != null);}
    RVMThread vmthread = JikesRVMSupport.getThread(thread);
    if (!isDebugeeThread(vmthread)) {
      throw new InvalidThreadException(-1);
    }
    if (VM.VerifyAssertions) {VM._assert(isDebugeeThread(vmthread));}
    ThreadInfo ti = null;
    synchronized(threadInfo) {
      ti = threadInfo.get(vmthread);
    }
    if (VM.VerifyAssertions) {VM._assert(ti != null);}
    return ti;
  }

  /** Get the class status. */
  @Uninterruptible
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

  /** From JDWP wrapper to the JikesRVM reflection wrapper.*/
  private static Object toReflectionObject(Value v) {
    if (v instanceof BooleanValue) {
      return Reflection.wrapBoolean(((BooleanValue)v).getValue()? 1:0);
    } else if (v instanceof ByteValue) {
      return Reflection.wrapByte(((ByteValue)v).getValue());
    } else if (v instanceof ShortValue) {
      return Reflection.wrapShort(((ShortValue)v).getValue());
    } else if (v instanceof CharValue) {
      return Reflection.wrapChar(((CharValue)v).getValue());
    } else if (v instanceof IntValue) {
      return Reflection.wrapInt(((IntValue)v).getValue());
    } else if (v instanceof FloatValue) {
      return Reflection.wrapFloat(((FloatValue)v).getValue());
    } else if (v instanceof DoubleValue) {
      return Reflection.wrapDouble(((DoubleValue)v).getValue());
    } else if (v instanceof ObjectValue ) {
      return ((ObjectValue)v).getValue();
    } else {
      if (VM.VerifyAssertions) { VM._assert(false);}
      return null;  
    }
  }

  /** Checks whether or not a thread is debuggable thread. */
  private static boolean isDebugeeThread(RVMThread thread) {
    if (null == thread) {
      return true;
    }
    final boolean rValue = !isJDWPAgent(thread) && !thread.isBootThread()
        && !thread.isDebuggerThread() && !thread.isGCThread()
        && !thread.isSystemThread() && !thread.isIdleThread();
    return rValue;
  };

  /** Whether or not a thread is JDWP work thread or the debugee thread. */
  private static boolean isJDWPAgentOrDebugeeThread(RVMThread t) {
    return isJDWPAgent(t) || isDebugeeThread(t);
  }

  /** Whether or not a thread is JDWP work thread. */
  private static boolean isJDWPAgent(RVMThread thread) {
    if (thread == null) {
      return false;
    }
    Thread jthread = thread.getJavaLangThread();
    if (jthread == null) {
      return false;
    }
    if (jthread instanceof Jdwp 
        || jthread instanceof JdwpConnection) {
      return true;
    }
    ThreadGroup jgroup = jthread.getThreadGroup();
    return jgroup == Jdwp.getDefault().getJdwpThreadGroup();
  }

  /** Get a readable JDWP event kind name from the JDWP constant. */
  @Uninterruptible
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
  @Uninterruptible
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
  @Uninterruptible
  private static void showPrologue(Address ip, Address fp) {
    VM.sysWrite("   at [ip = ");
    VM.sysWrite(ip);
    VM.sysWrite(", fp = ");
    VM.sysWrite(fp);
    VM.sysWrite("] ");
  }

  /** Print the hardware trap frame. */
  @Uninterruptible
  private static void showHardwareTrapMethod(Address fp) {
    VM.sysWrite("   at [fp ");
    VM.sysWrite(fp);
    VM.sysWriteln("] Hardware trap");
  }

  /** Print a stack frame for the native method. */
  @Uninterruptible
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
  @Uninterruptible
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

    /** The mask for disabling all the event.*/
    private boolean notificationEnabled = true;

    /** Getters/Setters. */
    private synchronized void setBreakPointEnabled(boolean breakPointEnabled) {
      this.breakPointEnabled = breakPointEnabled;
    }
    private synchronized void setVmDeathEnabled(boolean vmExitEnabled) {
      this.vmExitEnabled = vmExitEnabled;
    }
    private synchronized void setClassPrepareEnabled(boolean classPrepareEnabled) {
      this.classPrepareEnabled = classPrepareEnabled;
    }
    private synchronized void setThreadStartEnabled(boolean threadStartEnabled) {
      this.threadStartEnabled = threadStartEnabled;
    }
    private synchronized void setThreadEndEnabled(boolean threadEndEnabled) {
      this.threadEndEnabled = threadEndEnabled;
    }
    private synchronized void setExceptionCatchEnabled(boolean exceptionThrowEnabled) {
      this.exceptionCatchEnabled = exceptionThrowEnabled;
    }

    /** control overall event notification to the JDWP event handler.*/
    private synchronized final void disableEventNotify() {
      this.notificationEnabled = false;
    }
    private synchronized final void enableEventNotify() {
      this.notificationEnabled = true;
    }

    /** Event notification status. */
    private synchronized boolean canNotifyBreakPoint() {
      return notificationEnabled && breakPointEnabled;
    }
    private synchronized boolean canNotifyVmExit() {
      return notificationEnabled && vmExitEnabled;
    }
    private synchronized boolean canNotifyThreadEnd() {
      return notificationEnabled && threadEndEnabled;
    }
    private synchronized boolean canNotifyThreadStart() {
      return notificationEnabled && threadStartEnabled;
    }
    private synchronized boolean canNotifyClassPrepare() {
      return notificationEnabled && classPrepareEnabled;
    }
    private synchronized boolean canNotifyExceptionCatch() {
      return notificationEnabled && exceptionCatchEnabled;
    }

    /** VM call back handlers. */
    public void notifyStartup() {
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("Firing VM_INIT");
      }
      Jdwp.notify(new VmInitEvent(Thread.currentThread()));
    }

    public void notifyExit(int value) {
      if (!canNotifyVmExit()) {
        return;
      }
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("Firing VM_DEATH");
      }
      Jdwp.notify(new VmDeathEvent());
    }

    public void notifyClassResolved(RVMClass vmClass) {
      if (!canNotifyClassPrepare()) {
        return;
      }
      Class<?> clazz = vmClass.getClassForType();
      if (vmClass.getDescriptor().isBootstrapClassDescriptor()) {
        return;
      }
      if (canNotifyClassPrepare()) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("Firing CLASS_PREPARE: ", clazz.getName());
        }
        Event event = new ClassPrepareEvent(Thread.currentThread(), clazz,
            getStatus(vmClass));
        Jdwp.notify(event);
      }
    }

    /** Notification from a new thread. */
    public void notifyThreadStart(RVMThread vmThread) {
      if (!isDebugeeThread(vmThread)) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("THREAD_START: skip thread ", vmThread.getName());
        }
        return;
      }
      synchronized(threadInfo) {
        if (VM.VerifyAssertions) {
          // this must be the first time.
          VM._assert(threadInfo.get(vmThread) == null);
        }
        threadInfo.put(vmThread, new ThreadInfo(vmThread));
      }
      if (canNotifyThreadStart()) {
        Thread thread = vmThread.getJavaLangThread();
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("THREAD_START: firing ", thread.getName());
        }
        Event event = new ThreadStartEvent(thread);
        Jdwp.notify(event);
      }
    }

    /** Notification from the to-be-dead thread. */
    public void notifyThreadEnd(RVMThread vmThread) {
      if (!isDebugeeThread(vmThread)) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("THREAD_END: skip system thread ", vmThread.getName());
        }
        return;
      }
      if (canNotifyThreadEnd()) {
        Thread thread = vmThread.getJavaLangThread();
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("THREAD_END: firing : ", thread.getName());
        }
        Event event = new ThreadStartEvent(thread);
        Jdwp.notify(event);
      }
      synchronized(threadInfo) {
        ThreadInfo ti = threadInfo.get(vmThread);
        if (VM.VerifyAssertions) {
          // the application thread must be registered previously.
          VM._assert((ti != null) && isDebugeeThread(vmThread));
        }
        threadInfo.remove(vmThread);
      }
    }

    /**
     * Notification from a thread that this thread will catch catch an
     * exception.
     */
    public void notifyExceptionCatch(Throwable e, NormalMethod sourceMethod,
        int sourceByteCodeIndex, NormalMethod catchMethod,
        int catchByteCodeIndex) {
      RVMThread vmThread = Scheduler.getCurrentThread();
      if (!isDebugeeThread(vmThread)) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("EXCEPTION_CATCH: skip system thread ", vmThread.getName());
        }
        return;
      }
      synchronized(threadInfo) {
        ThreadInfo ti = threadInfo.get(vmThread);
        if (ti == null) {
          if (JikesRVMJDWP.getVerbose() >= 2) {
            VM.sysWriteln("NOTIFY_EXCEPTION: skip system thread ", vmThread
                .getName());
          }
          return;
        }
      }
      if (canNotifyExceptionCatch()) {
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
      if (!canNotifyBreakPoint() || !isDebugeeThread(t)) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWrite("BREAKPOINT_HIT: skip break point ", t.getName());
          VM.sysWriteln(" at ", bcindex, method.toString());
        }
        return;
      }

      if (canNotifyBreakPoint()) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("firing a break point hit: bcindex ", bcindex, 
              method.toString());
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

    /** The debugee thread requested suspending itself. */
    private boolean selfSuspended;

    /** Method invocation request. */
    private MethodInvokeRequest methodInvokeRequest;

    /** The result of method invocation. */
    private MethodResult methodResult;

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

    /**
     * Perform special suspension due to the JDWP event suspension. The calling
     * thread is the debugee thread, which triggered a JDWP event.
     */
    public synchronized void suspendCurrentThread() {
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("suspendFromJDWPEventNotify: name = ", thread.getName(),
            " suspendCount = ", suspendCount);
      }
      if (VM.VerifyAssertions) {
        VM._assert(thread == Scheduler.getCurrentThread());
      }
      selfSuspended = true;
      suspendCount++;
      while(suspendCount > 0) {
        try {wait();} catch(InterruptedException e) {}
        if (methodInvokeRequest != null) {
          if (VM.VerifyAssertions) {
            VM._assert(suspendCount > 0);
          }
          int saveSuspendCount = suspendCount;
          suspendCount = 0; //resume state
          jdwpNotifier.disableEventNotify();
          methodResult = methodInvokeRequest.execute();
          suspendCount = saveSuspendCount; //recover the old state.
          jdwpNotifier.enableEventNotify();
          notify();
        }
      }
      methodInvokeRequest = null;
      methodResult = null;
      selfSuspended = false;
    }

    /** Perform JDWP's thread resumption. */
    public synchronized void resumeThread() {
      // "Resumes the execution of a given thread. If this thread was not previously 
      // suspended by the front-end, calling this command has no effect. Otherwise, 
      // the count of pending suspends on this thread is decremented. If it is 
      // decremented to 0, the thread will continue to execute."
      //  [jdwp-protocol.html#JDWP_ThreadReference_Suspend]
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("resumeThread: name = ", thread.getName(),
            " suspendCount = ", suspendCount);
      }
      if (VM.VerifyAssertions) {
        VM._assert(thread != Scheduler.getCurrentThread());
      }

      boolean actualResume = false;
      if (suspendCount > 0) {
        if (VM.VerifyAssertions) {
          // suspended thread can resume itself.
          VM._assert(Scheduler.getCurrentThread() != thread);
        }
        suspendCount--;
        if (suspendCount == 0) {
          actualResume = true;
        }
      }
      if (actualResume) {
        Thread jthread = thread.getJavaLangThread();
        if (selfSuspended) {
          notify();
        } else {
          if (VM.VerifyAssertions) {
            VM._assert(jthread != null);
          }
          synchronized (jthread) {
            thread.resume();
          }
        }
      }
    }

    /** Getter/setters for the suspendCount. */
    private synchronized int getSuspendCount() {
      return suspendCount;
    }

    private synchronized void requestMethodInvocation(MethodInvokeRequest req)
        throws JdwpException {
      if (!selfSuspended || suspendCount == 0) {
        throw new JdwpException(JdwpConstants.Error.THREAD_NOT_SUSPENDED,
            "Method invoke is possible only within a suspend thread by JDWP event.");

      }
      methodInvokeRequest = req;
      notify();
    }

    private synchronized MethodResult waitForMethodInovationResult() {
      MethodResult result;
      try {
        while(methodResult == null) {
          wait();
        }
        result = methodResult;  
        methodInvokeRequest = null;
        methodResult = null;  
      } catch(InterruptedException e) {
        if (VM.VerifyAssertions) {
          result = null;
          VM._assert(false, "unexpected interruption.");
        }
      }
      return result;
    }

    /**
     * Walk the call frames in the suspended thread. If the thread is suspended,
     * visit call frames.
     */
    private synchronized void stackWalk(StackFrameVisitor v) throws InvalidThreadException {
      if (suspendCount <= 0) {
        if (JikesRVMJDWP.getVerbose() >= 1) {
          VM.sysWriteln("Reject stack walk on the running thread: ", thread
              .getName());
        }
        throw new InvalidThreadException(-1);
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
                    return; // finish frame walk.
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
                    return;
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
                  if (isDebuggableMethod(meth)) {
                    if (!v.visit(fno++, meth, bci, ocm, ipOffset, fpOffset, iei)) {
                      return;
                    }
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
                    return;
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
    }
  }

  static class MethodInvokeRequest {
    private final Object thisArg;
    private final RVMMethod method;
    private final Object[] otherArgs;
    private final int options;

    /** Constructor. */
    private MethodInvokeRequest(RVMMethod method, Object obj,
        Object[] otherArgs, int options) {
      this.method = method;
      this.thisArg = obj;
      this.options = options;
      this.otherArgs = otherArgs;
    }

    private boolean isInvokeSingleThreaded() {
      return (options & JdwpConstants.InvokeOptions.INVOKE_SINGLE_THREADED) 
        == JdwpConstants.InvokeOptions.INVOKE_SINGLE_THREADED; 
    }

    private MethodResult execute() {
      Value resultValue;
      Throwable exceptionResult;
      try {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("executing a method: ", method.toString());
        }
        Object o = Reflection.invoke(method, thisArg, otherArgs);
        TypeReference type = method.getReturnType();
        if (type.isVoidType()) {
          resultValue = new VoidValue();
        } else if (type.isPrimitiveType()) {
          if (type.isBooleanType()) {
            resultValue = new BooleanValue(Reflection.unwrapBoolean(o));
          } else if (type.isByteType()) {
            resultValue = new ByteValue(Reflection.unwrapByte(o));
          } else if (type.isCharType()) {
            resultValue = new CharValue(Reflection.unwrapChar(o));
          } else if (type.isShortType()) {
            resultValue = new ShortValue(Reflection.unwrapShort(o));
          } else if (type.isIntType()) {
            resultValue = new IntValue(Reflection.unwrapInt(o));
          } else if (type.isFloatType()) {
            resultValue = new FloatValue(Reflection.unwrapFloat(o));
          } else if (type.isDoubleType()) {
            resultValue = new DoubleValue(Reflection.unwrapDouble(o));
          } else {
            resultValue = null; 
            exceptionResult = null;
            if (VM.VerifyAssertions) {VM._assert(false);}
          }
        } else if (type.isReferenceType()) {
          resultValue = new ObjectValue(o);
        } else {
          resultValue = null; 
          exceptionResult = null;
          if (VM.VerifyAssertions) {VM._assert(false);}
        }
        exceptionResult = null;
      } catch(Exception e) {
        exceptionResult = e;
        resultValue = new VoidValue();
      }
      return new MethodResult(resultValue, exceptionResult);
    }
  }
} 
