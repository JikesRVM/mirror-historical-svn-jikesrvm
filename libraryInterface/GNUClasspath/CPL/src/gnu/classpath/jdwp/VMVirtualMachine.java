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
import java.lang.JikesRVMSupport;

import org.jikesrvm.VM;
import org.jikesrvm.debug.Breakpoint;
import org.jikesrvm.debug.BreakpointManager;
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
public final class VMVirtualMachine implements StackframeLayoutConstants,
    EventCallbacks {

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

  private static final VMVirtualMachine vm = new VMVirtualMachine(); 
  
  /** Suspend a debugee thread. */
  public static void suspendThread(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(!Threads.isAgentThread(JikesRVMSupport.getThread(thread)));
    }

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.suspendThread: ", thread.getName(),
          " in ", Scheduler.getCurrentThread().getName());
    }
    suspendThreadInternal(JikesRVMSupport.getThread(thread));

  }

  private static void suspendThreadInternal(RVMThread t) {
    if (t == Scheduler.getCurrentThread()) {
      while(true) {
        synchronized(suspendedThreads) {
          SuspendInfo i = ensureNewSuspendInfo(t);
          if (VM.VerifyAssertions) {VM._assert(i.getSuspendCount() == 0);}
          i.increaseSuspendCount();
          i.selfSuspended = true;
        }
        Threads.suspendThread(Scheduler.getCurrentThread());
        if (!checkMethodInvocation()) {
          break;
        } else {
          //suspend all other threads except for me.
          LinkedListRVM<RVMThread> listToSuspend = new LinkedListRVM<RVMThread>();
          for(RVMThread thread : Threads.getAllThreads()) {
            if (thread != Scheduler.getCurrentThread()) {
              listToSuspend.add(thread);
            }
          }
          RVMThread[] list = new RVMThread[listToSuspend.size()];
          int i=0;
          for(RVMThread thread: listToSuspend) {
            list[i++] = thread;
          }
          suspendThreadsInternal(list);
        }
      }
    } else {
      synchronized(suspendedThreads) {
        SuspendInfo i = ensureNewSuspendInfo(Scheduler.getCurrentThread());
        if (i.getSuspendCount() == 0) {
          Threads.suspendThread(t);
        }
        i.increaseSuspendCount();
      }
    } 
  }

  public static void suspendAllThreads() throws JdwpException {
    suspendThreadsInternal(Threads.getAllThreads());
  }

  /**
   * Suspend all the debuggee threads. Two calling contexts are considered here.
   * The first one is from VMVirtualMachine.suspend, and the current thread
   * would be PacketProcessor. The second one is from the debugee thread due to
   * SUSPEND_ALL event suspension policy.
   */
  public static void suspendThreadsInternal(RVMThread[] threads) {
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.suspendAllThreads:  in ", 
          Scheduler.getCurrentThread().getName());
    }

    //suspend except for currentThread.
    boolean currentThreadSuspend = false;
    synchronized(suspendedThreads) {
      LinkedListRVM<SuspendInfo> suspendThreads = new LinkedListRVM<SuspendInfo>();
      LinkedListRVM<RVMThread> actualSuspendThreads = new LinkedListRVM<RVMThread>();
      for (final RVMThread vmThread : threads) {
        if (!Threads.isAgentThread(vmThread)) {
          if (vmThread != Scheduler.getCurrentThread()) {
            SuspendInfo i = ensureNewSuspendInfo(vmThread);
            if (i.getSuspendCount() == 0) {
              actualSuspendThreads.add(vmThread);
            } 
            suspendThreads.add(i);
          } else {
            currentThreadSuspend = true;
          }
        }
      }
      Threads.suspendThreadList(actualSuspendThreads);
      for(final SuspendInfo i : suspendThreads) {
        i.increaseSuspendCount();
      }
    }
    if (currentThreadSuspend) {
      suspendThreadInternal(Scheduler.getCurrentThread());
    }
  }

  /** 
   * Resume a debugee thread.
   */
  public static void resumeThread(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.resumeThread: ", thread.getName());
    }
    resumeThreadInternal(JikesRVMSupport.getThread(thread));
  }

  private static void resumeThreadInternal(RVMThread t) {
    synchronized(suspendedThreads) {
      SuspendInfo i = suspendedThreads.get(t); 
      if ( i != null) {
        if (VM.VerifyAssertions) {VM._assert(i.getSuspendCount() > 0);}
        i.decreaseSuspendCount();
        if (i.getSuspendCount() == 0) {
          suspendedThreads.remove(t);
          Threads.resumeThread(t);
        } 
       }
    }
  }
  
  /**
   * Resume all threads. 
   */
  public static void resumeAllThreads() throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.resumeAllThreads");
    }
    synchronized(suspendedThreads) {
      LinkedListRVM<RVMThread> acutalResumeThreads = new LinkedListRVM<RVMThread>();
      for (final RVMThread t : suspendedThreads.keys()) {
        if (VM.VerifyAssertions) {VM._assert(t != Scheduler.getCurrentThread());}
        SuspendInfo i = suspendedThreads.get(t);
        if (VM.VerifyAssertions) { VM._assert(i.getSuspendCount() > 0);}
        i.decreaseSuspendCount();
        if (i.getSuspendCount() == 0) {
          acutalResumeThreads.add(t);
        }
      }
      for(final RVMThread t : acutalResumeThreads) {
        suspendedThreads.remove(t);
      }
      Threads.resumeThreadList(acutalResumeThreads);
    }
  }

  /**
   * Get the suspend count for a thread. 
   */
  public static int getSuspendCount(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }

    RVMThread t = JikesRVMSupport.getThread(thread);
    int count; 
    synchronized(suspendedThreads) {
      SuspendInfo i = suspendedThreads.get(t);
      count = (i == null) ?  0 : i.getSuspendCount(); 
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.getSuspendCount: " + count + " for ",
          t.getName());
    }

    return count; 
  };

  /** 
   * Returns the status of a thread. 
   */
  public static int getThreadStatus(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    RVMThread rvmThread =  JikesRVMSupport.getThread(thread);
    if (VM.VerifyAssertions) {
      VM._assert(thread != null);
    }
    // using switch statement does not work here since
    // the javac takes the enum integer constant values from
    // its JDK Thread.State, and Thread.State enum constants values in
    // the JDK Threads.State and the GNU Thread.State could be different.
    // This actually happened with JDK 1.6.
    final int status;
    Thread.State s = rvmThread.getState();
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
          rvmThread.getState().toString());
      VM.sysWriteln(" jdwpstatus =", getThreadStateName(status));
    }
    return status;
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
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
      VM._assert(!Threads.isAgentThread(JikesRVMSupport.getThread(thread)));
    }
    RVMThread rvmThread = JikesRVMSupport.getThread(thread);
    if (VM.VerifyAssertions) {
      VM._assert(!Threads.isAgentThread(rvmThread));
    }
    
    Object[] argOthers = new Object[values.length];
    for(int i=0; i < argOthers.length;i++) {
      argOthers[i] = toReflectionObject(values[i]);
    }
    
    synchronized(suspendedThreads) {
      SuspendInfo ti = suspendedThreads.get(rvmThread);
      if (ti == null || ti.getSuspendCount() == 0) {
        throw new JdwpException(JdwpConstants.Error.THREAD_NOT_SUSPENDED,
        "The target thread is not suspended");
      }
      if (!ti.isSelfSuspended()) {
        throw new JdwpException(JdwpConstants.Error.THREAD_NOT_SUSPENDED,
        "The tearget tread is not suspended by JDWP event before.");
      }
    }
    // enqueue request.
    MethodInvokeRequest req = new MethodInvokeRequest(method.meth, obj, argOthers, options);
    requestMethodInvocation(rvmThread, req);
    if (req.isInvokeSingleThreaded()) {
       resumeAllThreads();
    } else {
       resumeThreadInternal(rvmThread);
    }

    MethodResult result = req.waitForResult();
    return result;
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
  @SuppressWarnings("unchecked")
  public static void registerEvent(final EventRequest request)
      throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
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
      Collection<IEventFilter> filters = (Collection<IEventFilter>)request.getFilters();
      for(final IEventFilter filter : filters) {
        if (filter instanceof LocationOnlyFilter) {
          LocationOnlyFilter locFilter = (LocationOnlyFilter)filter;
          Location loc = locFilter.getLocation();
          RVMMethod m = loc.getMethod().meth;
          int bcIndex = (int)loc.getIndex();
          if (m instanceof NormalMethod && bcIndex >= 0 ) {
            NormalMethod method = (NormalMethod) m;
            Breakpoint.setBreakPoint(method, bcIndex);
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
      RVMDebug.getRVMDebug().setEventNotificationMode(EventType.VM_EXCEPTION,
          true, null);
      break;
    }
    case JdwpConstants.EventKind.USER_DEFINED: {
      VM.sysWriteln("USER_DEFINED event is not implemented");
      throw new NotImplementedException("USER DEFINED event is not implemented");
    }
    case JdwpConstants.EventKind.THREAD_START: {
      RVMDebug.getRVMDebug().setEventNotificationMode(
          EventType.VM_THREAD_START, true, null);
      break;
    }
    case JdwpConstants.EventKind.THREAD_END: {
      RVMDebug.getRVMDebug().setEventNotificationMode(EventType.VM_THREAD_END,
          true, null);
      break;
    }
    case JdwpConstants.EventKind.CLASS_PREPARE: {
      RVMDebug.getRVMDebug().setEventNotificationMode(
          EventType.VM_CLASS_PREPARE, true, null);
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
      RVMDebug.getRVMDebug().setEventNotificationMode(EventType.VM_DEATH, true,
          null);
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
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
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
            Breakpoint.clearBreakPoint(method, bcIndex);
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
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("clearEvents[Event: ", getEventKindName(kind));
    }
    switch (kind) {
    case JdwpConstants.EventKind.SINGLE_STEP: {
      //TODO:to-be-implemented.
      throw new NotImplementedException("SINGLE_STEP");
    }
    case JdwpConstants.EventKind.BREAKPOINT: {
      //TODO:to-be-implemented.
      throw new NotImplementedException("BREAKPOINT");
    }
    case JdwpConstants.EventKind.FRAME_POP: {
      // This optional feature will not be implemented.
      throw new NotImplementedException("FRAME_POP");
    }
    case JdwpConstants.EventKind.USER_DEFINED: {
      // This optional feature will not be implemented.
      throw new NotImplementedException("USER_DEFINED");
    }
    case JdwpConstants.EventKind.THREAD_START: {
      RVMDebug.getRVMDebug().setEventNotificationMode(
          EventType.VM_THREAD_START, false, null);
      break;
    }
    case JdwpConstants.EventKind.THREAD_END: {
      RVMDebug.getRVMDebug().setEventNotificationMode(
          EventType.VM_THREAD_START, false, null);
      break;
    }
    case JdwpConstants.EventKind.CLASS_PREPARE: {
      RVMDebug.getRVMDebug().setEventNotificationMode(
          EventType.VM_CLASS_PREPARE, false, null);
      break;
    }
    case JdwpConstants.EventKind.CLASS_UNLOAD: {
      //TODO: Does Jikes RVM unload class?
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
      RVMDebug.getRVMDebug().setEventNotificationMode(
          EventType.VM_EXCEPTION_CATCH, false, null);
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
      RVMDebug.getRVMDebug().setEventNotificationMode(EventType.VM_DEATH,
          false, null);
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

  /** Get JDWP thread info for a user thread. */
  private static SuspendInfo ensureNewSuspendInfo(RVMThread thread) {
    if (VM.VerifyAssertions) {
      VM._assert(thread != null);
      VM._assert(!Threads.isAgentThread(thread));
    }
    SuspendInfo ti = new SuspendInfo();
    synchronized(suspendedThreads) {
      if (VM.VerifyAssertions) {
        VM._assert(suspendedThreads.get(thread) == null);
      }
      suspendedThreads.put(thread, ti);
    }
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
//
//  /** Checks whether or not a thread is debuggable thread. */
//  private static boolean !Threads.isAgentThread(RVMThread thread) {
//    if (null == thread) {
//      return true;
//    }
//    final boolean rValue = !Threads.isAgentThread(thread) && !thread.isBootThread()
//        && !thread.isDebuggerThread() && !thread.isGCThread()
//        && !thread.isSystemThread() && !thread.isIdleThread();
//    return rValue;
//  };

//  /** Whether or not a thread is JDWP work thread or the debugee thread. */
//  private static boolean isJDWPAgentOrDebugeeThread(RVMThread t) {
//    return Threads.isAgentThread(t) || !Threads.isAgentThread(t);
//  }

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

  /** The JDWP specific user thread information. */
  static private final HashMapRVM<RVMThread,SuspendInfo> suspendedThreads = 
    new HashMapRVM<RVMThread, SuspendInfo>();

  static private final HashMapRVM<RVMThread,MethodInvokeRequest> invokeRequets =
    new HashMapRVM<RVMThread,MethodInvokeRequest>();
    
  
  private VMVirtualMachine() {}

  /** The internal initialization process. */
  public static void boot(String jdwpArgs) throws Exception {
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
    
    rvmdbg.setEventCallbacks(vm);
    rvmdbg.setEventNotificationMode(EventType.VM_INIT, true, null);
    rvmdbg.setEventNotificationMode(EventType.VM_DEATH, true, null);
    if (VM.VerifyAssertions) {
      VM._assert(Jdwp.isDebugging, "The JDWP must be initialized here.");
    }
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

  public void vmStart() {}

  private LinkedListRVM<RVMThread> notifyingThreads = new LinkedListRVM<RVMThread>();

  /**
   * Notify an event to the JDWP client. This method prevent reporting an JDWP
   * event while reporting the JDWP in a thread. This overlapped JDWP reportings
   * in a thread is possible since the JDWP agent code is also the Java thread.
   * 
   * @param e The JDWP event.
   */
  private void notifyEvent(Event e) {
    synchronized(invokeRequets) {
      if (invokeRequets.get(Scheduler.getCurrentThread()) != null) {
        return; //skip events during the method invocation.
      }
    }

     if (VM.VerifyAssertions) {
        synchronized(notifyingThreads) {
          boolean isExecutedBefore = notifyingThreads.contains(Scheduler.getCurrentThread());
          VM._assert(!isExecutedBefore, "JDWP event can not overlap.");
          notifyingThreads.add(Scheduler.getCurrentThread());
        }
    }
    try {
      Jdwp.notify(e);
    } finally {
      if (VM.VerifyAssertions) {
        synchronized(notifyingThreads) {
          notifyingThreads.remove(Scheduler.getCurrentThread());
        }
      }
    }
  }

  public void vmInit(RVMThread t) {
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("Firing VM_INIT");
    }
    notifyEvent(new VmInitEvent(t.getJavaLangThread()));
  }
  
  public void vmDeath() {
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("Firing VM_DEATH");
    }
    notifyEvent(new VmDeathEvent());
  }

  public void threadStart(RVMThread vmThread) {
    if (!!Threads.isAgentThread(vmThread)) {
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("THREAD_START: skip thread ", vmThread.getName());
      }
      return;
    }

    Thread thread = vmThread.getJavaLangThread();
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("THREAD_START: firing ", thread.getName());
    }
    Event event = new ThreadStartEvent(thread);
    notifyEvent(event);
  }
  
  public void threadEnd(RVMThread vmThread) {
    if (!!Threads.isAgentThread(vmThread)) {
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("THREAD_END: skip system thread ", vmThread.getName());
      }
      return;
    }
    synchronized(suspendedThreads) {
      SuspendInfo ti = suspendedThreads.get(vmThread);
      if (ti != null) {
        suspendedThreads.remove(vmThread);
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("THREAD_END: in the JDWP suspended state.", vmThread.getName());
        }
      }
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("THREAD_END: firing : ", vmThread.getName());
    }
    Event event = new ThreadEndEvent(vmThread.getJavaLangThread());
    notifyEvent(event);
  }
  public void classLoad(RVMThread t, RVMClass c) {
    if (VM.VerifyAssertions) {
      VM._assert(false, "Not implemented");
    }
  }
  
  public void classPrepare(RVMThread t, RVMClass vmClass) {
    Class<?> clazz = vmClass.getClassForType();
    if (vmClass.getDescriptor().isBootstrapClassDescriptor()) {
      return;
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("Firing CLASS_PREPARE: ", clazz.getName());
    }
    Event event = new ClassPrepareEvent(Thread.currentThread(), clazz,
        getStatus(vmClass));
    notifyEvent(event);
  }

  public void exception(RVMThread t, NormalMethod sourceMethod, int sourceByteCodeIndex, Throwable e,
      NormalMethod catchMethod, int catchByteCodeIndex) {
    RVMThread vmThread = Scheduler.getCurrentThread();
    if (!!Threads.isAgentThread(vmThread)) {
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("EXCEPTION_CATCH: skip system thread ", vmThread.getName());
      }
      return;
    }
    synchronized(suspendedThreads) {
      SuspendInfo ti = suspendedThreads.get(vmThread);
      if (ti == null) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("NOTIFY_EXCEPTION: skip system thread ", vmThread
              .getName());
        }
        return;
      }
    }

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
    notifyEvent(event);
  }
  
  public void exceptionCatch(RVMThread t, NormalMethod method, int bcindex, Throwable e) {
    if (VM.VerifyAssertions) {
      VM._assert(false, "Not implemented");
    }
  }

  public void breakpoint(RVMThread t, NormalMethod method, int bcindex) {
    if (VM.VerifyAssertions) {
      VM._assert(method != null && bcindex >= 0);
    }
    if (!!Threads.isAgentThread(t)) {
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWrite("BREAKPOINT_HIT: skip break point ", t.getName());
        VM.sysWriteln(" at ", bcindex, method.toString());
      }
      return;
    }

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
    notifyEvent(event);
  }

  /** JDWP agent specific per-thread information. */
  static final class SuspendInfo {

    /** The JDWP suspension count. */
    private int suspendCount = 0;
  
    /** The debugee thread requested suspending itself. */
    private boolean selfSuspended = false;
  
    SuspendInfo() {}
  
    void increaseSuspendCount() {
      suspendCount++;
    }
    
    void decreaseSuspendCount() {
      suspendCount--;
      if (VM.VerifyAssertions) {
        VM._assert(suspendCount >=0);
      }
    }
    /** Getter/setters for the suspendCount. */
    int getSuspendCount() {
      return suspendCount;
    }
    boolean isSelfSuspended() {
      return selfSuspended;
    }
  }
  
  private static boolean checkMethodInvocation() {
    MethodInvokeRequest req;
    synchronized(invokeRequets) {
      req = invokeRequets.get(Scheduler.getCurrentThread());
     
    }
    if (req == null) {
      return false;
    }

    req.execute();
    synchronized(invokeRequets) {
      invokeRequets.remove(Scheduler.getCurrentThread()); 
    }
    return true;
  }
  
  private static void requestMethodInvocation(RVMThread t, MethodInvokeRequest req)
      throws JdwpException {
    synchronized(suspendedThreads) {
      if (VM.VerifyAssertions) {
        VM._assert(suspendedThreads.get(t) != null);
      }
      synchronized(invokeRequets) {
        invokeRequets.put(t, req);
      }
    }
  }

  static class MethodInvokeRequest {
    private final Object thisArg;
    private final RVMMethod method;
    private final Object[] otherArgs;
    private final int options;

    /** The result of method invocation. */
    private MethodResult methodResult;
    
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

    private synchronized MethodResult waitForResult() {
      try {
        while(methodResult == null) {
          wait();
        }
      } catch(InterruptedException e) {
        if (VM.VerifyAssertions) {
          VM._assert(false, "unexpected interruption.");
        }
      }
      return methodResult;
    }

    private synchronized void execute() {
      if (VM.VerifyAssertions) { VM._assert(methodResult == null);} 
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
      methodResult = new MethodResult(resultValue, exceptionResult);
      notify();
    }
  } 
}
