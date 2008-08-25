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

import gnu.classpath.jdwp.SuspendedThreadState.SuspendInfo;
import gnu.classpath.jdwp.event.BreakpointEvent;
import gnu.classpath.jdwp.event.ClassPrepareEvent;
import gnu.classpath.jdwp.event.Event;
import gnu.classpath.jdwp.event.EventRequest;
import gnu.classpath.jdwp.event.ExceptionEvent;
import gnu.classpath.jdwp.event.SingleStepEvent;
import gnu.classpath.jdwp.event.ThreadEndEvent;
import gnu.classpath.jdwp.event.ThreadStartEvent;
import gnu.classpath.jdwp.event.VmDeathEvent;
import gnu.classpath.jdwp.event.VmInitEvent;
import gnu.classpath.jdwp.event.filters.IEventFilter;
import gnu.classpath.jdwp.event.filters.LocationOnlyFilter;
import gnu.classpath.jdwp.event.filters.StepFilter;
import gnu.classpath.jdwp.event.filters.ThreadOnlyFilter;
import gnu.classpath.jdwp.exception.InvalidClassException;
import gnu.classpath.jdwp.exception.InvalidEventTypeException;
import gnu.classpath.jdwp.exception.InvalidFrameException;
import gnu.classpath.jdwp.exception.InvalidLocationException;
import gnu.classpath.jdwp.exception.InvalidThreadException;
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
import gnu.xml.pipeline.EventFilter;

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
import org.jikesrvm.debug.StackFrame.FrameLocation;
import org.jikesrvm.runtime.Reflection;
import org.jikesrvm.scheduler.Scheduler;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.util.HashMapRVM;
import org.jikesrvm.util.LinkedListRVM;
import org.vmmagic.pragma.Uninterruptible;
import org.jikesrvm.ArchitectureSpecific.StackframeLayoutConstants;

/** JikesRVM implementation of VMVirtualMachine. */
public final class VMVirtualMachineImpl implements StackframeLayoutConstants,
    EventCallbacks {
  
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

  private static RVMThread getThread(EventRequest req) {
    try {
      for (Object o : req.getFilters()) {
        if (o instanceof ThreadOnlyFilter) {
          ThreadOnlyFilter f = (ThreadOnlyFilter) o;
          Thread thread = f.getThread().getThread();
          RVMThread t = JikesRVMSupport.getThread(thread);
          return t;
        }
      }
    } catch (InvalidThreadException e) {
    }
    return null;
  }
  
  static StepFilter getStepFilter(EventRequest req) {
    for (Object o : req.getFilters()) {
      if (o instanceof StepFilter) {
        return (StepFilter)o;
      }
    }
    return null;
  }
  
  /** The JDWP specific user thread information. */
  private SuspendedThreadState suspendedThreadState =  new SuspendedThreadState();

  /** This is only for thread local variable. */
  private ThreadLocalInfos threadLocalInfos = new ThreadLocalInfos();

  private SingleStepState singleStepState = new SingleStepState();

  VMVirtualMachineImpl() {}

  /** Suspend a debugee thread. */
  public void suspendThread(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(!Threads.isAgentThread(JikesRVMSupport.getThread(thread)));
    }

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.suspendThread: ", thread.getName(),
          " in ", Scheduler.getCurrentThread().getName());
    }
    suspendThreadInternal(JikesRVMSupport.getThread(thread));

  }

  void suspendThreadInternal(RVMThread t) {
    if (t == Scheduler.getCurrentThread()) {
      while(true) {
        suspendedThreadState.addSelfSuspendedThread(t);
        Threads.suspendThread(t);
        if (!checkMethodInvocation(t)) {
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
      if (suspendedThreadState.isSuspended(t)) {
        suspendedThreadState.increaseSuspendCount(t);
      } else {
        suspendedThreadState.addSuspendedThread(t);
        Threads.suspendThread(t);  
      }
    } 
  }

  public void suspendAllThreads() throws JdwpException {
    suspendThreadsInternal(Threads.getAllThreads());
  }

  /**
   * Suspend all the debuggee threads. Two calling contexts are considered here.
   * The first one is from VMVirtualMachine.suspend, and the current thread
   * would be PacketProcessor. The second one is from the debugee thread due to
   * SUSPEND_ALL event suspension policy.
   */
  public void suspendThreadsInternal(RVMThread[] threads) {
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.suspendAllThreads:  in ", 
          Scheduler.getCurrentThread().getName());
    }

    //suspend except for currentThread.
    boolean currentThreadSuspend = false;
      LinkedListRVM<RVMThread> suspendThreads = new LinkedListRVM<RVMThread>();
      LinkedListRVM<RVMThread> actualSuspendThreads = new LinkedListRVM<RVMThread>();
      for (final RVMThread vmThread : threads) {
      if (!Threads.isAgentThread(vmThread)) {
        if (vmThread != Scheduler.getCurrentThread()) {
          if (!suspendedThreadState.isSuspended(vmThread)) {
            actualSuspendThreads.add(vmThread);
          }
          suspendThreads.add(vmThread);
        } else {
          currentThreadSuspend = true;
        }
      }
      Threads.suspendThreadList(actualSuspendThreads);
      for(final RVMThread t : suspendThreads) {
        if (suspendedThreadState.isSuspended(t)) {
          suspendedThreadState.increaseSuspendCount(t);
        } else {
          suspendedThreadState.addSuspendedThread(t);
        }
      }
    }

    if (currentThreadSuspend) {
      suspendThreadInternal(Scheduler.getCurrentThread());
    }
  }

  /** 
   * Resume a debugee thread.
   */
  public void resumeThread(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.resumeThread: ", thread.getName());
    }
    resumeThreadInternal(JikesRVMSupport.getThread(thread));
  }

  void resumeThreadInternal(RVMThread t) {
    if (suspendedThreadState.isSuspended(t)) {
      if (suspendedThreadState.getSuspendCount(t) > 1) {
        suspendedThreadState.decreaseSuspendCount(t);
      } else {
        suspendedThreadState.release(t);
        Threads.resumeThread(t);
      }
    } else {
      if (VM.VerifyAssertions) {VM._assert(false, "thread is not suspended: " + t);}
    }
  }
  
  /**
   * Resume all threads. 
   */
  public void resumeAllThreads() throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("VMVirtualMachine.resumeAllThreads");
    }
    LinkedListRVM<RVMThread> acutalResumeThreads = 
      suspendedThreadState.decreaseSuspendCountAndGetResumableThreads();
    Threads.resumeThreadList(acutalResumeThreads);
  }

  /**
   * Get the suspend count for a thread. 
   */
  public int getSuspendCount(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }

    RVMThread t = JikesRVMSupport.getThread(thread);
    int count; 
    if (suspendedThreadState.isSuspended(t)) {
      count = 0;
    } else {
      count = suspendedThreadState.getSuspendCount(t);
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
  public int getThreadStatus(final Thread thread) throws JdwpException {
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

  /** Dump call stack frames in a suspended thread. */
  public ArrayList<VMFrame> getFrames(final Thread thread,
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
    if (suspendedThreadState.isSuspended(rvmThread)) {
      throw new InvalidThreadException(0); // FIXME
    }

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
  public VMFrame getFrame(final Thread thread, long frameID)
      throws JdwpException {    
    if (VM.VerifyAssertions) {
      VM._assert(Threads.isAgentThread(Scheduler.getCurrentThread()));
    }
    final RVMThread rvmThread = JikesRVMSupport.getThread(thread);
    if (VM.VerifyAssertions) {
      VM._assert(rvmThread != null);
    }
    if (suspendedThreadState.isSuspended(rvmThread)) {
      throw new InvalidThreadException(0); // FIXME
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
  public int getFrameCount(final Thread thread) throws JdwpException {
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

  /** Execute a Java method in a suspended thread.*/
  public MethodResult executeMethod(Object obj, Thread thread,
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
    
    if (!suspendedThreadState.isSuspended(rvmThread)) {
      throw new JdwpException(JdwpConstants.Error.THREAD_NOT_SUSPENDED,
      "The tearget tread is not suspended by JDWP event before.");
    } else if (!suspendedThreadState.isSelfSuspended(rvmThread)) {
      throw new JdwpException(JdwpConstants.Error.THREAD_NOT_SUSPENDED,
      "The target tread is not suspended by JDWP event before.");
    }
    
    // enqueue request.
    boolean isInvokeSingleThreaded = (options & JdwpConstants.InvokeOptions.INVOKE_SINGLE_THREADED) 
      == JdwpConstants.InvokeOptions.INVOKE_SINGLE_THREADED;
    MethodInvokeRequest req = new MethodInvokeRequest(method.meth, obj, argOthers);
    threadLocalInfos.addMethodInvokeRequest(rvmThread, req);
    if (isInvokeSingleThreaded) {
       resumeAllThreads();
    } else {
       resumeThreadInternal(rvmThread);
    }

    MethodResult result = req.waitForResult();
    return result;
  }

  /** Set a JDWP event. */
  @SuppressWarnings("unchecked")
  public void registerEvent(final EventRequest request)
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
      RVMThread t = getThread(request);
      if (t == null) {
        throw new InvalidThreadException(0); //FIXME
      }
      if (VM.VerifyAssertions) {VM._assert(t != null);}
      FrameLocation loc = StackFrame.getFrameLocation(t, 0);
      RVMMethod m = loc.getMethod();
      if (m instanceof NormalMethod == false) {
        throw new InvalidLocationException();
      }
      NormalMethod nm = (NormalMethod)m;
      int depth = StackFrame.getFrameCount(t);
      singleStepState.setSingleStep(request, nm, loc.getLocation(), depth);
      RVMDebug.getRVMDebug().setEventNotificationMode(EventType.VM_SINGLE_STEP, true, t);
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
            RVMDebug.getRVMDebug().setBreakPoint(method, bcIndex);
            RVMDebug.getRVMDebug().setEventNotificationMode(RVMDebug.EventType.VM_BREAKPOINT, true, null);
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
  public void unregisterEvent(EventRequest request) throws JdwpException {
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
      RVMThread t = getThread(request);
      if (VM.VerifyAssertions) {VM._assert(t != null);}
      singleStepState.resetSingleStep(t);
      RVMDebug.getRVMDebug().setEventNotificationMode(EventType.VM_SINGLE_STEP, false, t);
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
            RVMDebug.getRVMDebug().clearBreakPoint(method, bcIndex);
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
  public void clearEvents(byte kind) throws JdwpException {
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
   * Notify an event to the JDWP client. This method prevent reporting an JDWP
   * event while reporting the JDWP in a thread. This overlapped JDWP reportings
   * in a thread is possible since the JDWP agent code is also the Java thread.
   * 
   * @param e The JDWP event.
   */
  private void notifyEvent(RVMThread t, Event e) {
    
    if (threadLocalInfos.isInvokingMethod(t)) {
      return; // skip events during the method invocation.
    }

    try {
      if (VM.VerifyAssertions) {
        VM._assert(!threadLocalInfos.isNotifyingEvent(t),
            "JDWP event can not overlap.");
        threadLocalInfos.setNotifyingEvent(t, true);
      }
      Jdwp.notify(e);
    } finally {
      if (VM.VerifyAssertions) {
        threadLocalInfos.setNotifyingEvent(t, false);
      }
    }
  }

  public void vmStart() {}

  public void vmInit(RVMThread t) {
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("Firing VM_INIT");
    }
    notifyEvent(t, new VmInitEvent(t.getJavaLangThread()));
  }
  
  public void vmDeath() {
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("Firing VM_DEATH");
    }
    notifyEvent(Scheduler.getCurrentThread(), new VmDeathEvent());
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
    notifyEvent(vmThread, event);
  }
  
  public void threadEnd(RVMThread vmThread) {
    if (!!Threads.isAgentThread(vmThread)) {
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("THREAD_END: skip system thread ", vmThread.getName());
      }
      return;
    }
    
    // flush all state information for the dead thread.
    if (suspendedThreadState.isSuspended(vmThread)) {
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("A suspended thread terminates abrubtly: " + vmThread); 
      }
      suspendedThreadState.release(vmThread);
    }
    threadLocalInfos.release(vmThread);
    
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("THREAD_END: firing : ", vmThread.getName());
    }
    Event event = new ThreadEndEvent(vmThread.getJavaLangThread());
    notifyEvent(vmThread, event);
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
        VMVirtualMachine.getStatus(vmClass));
    notifyEvent(t, event);
  }

  public void exception(RVMThread t, NormalMethod sourceMethod, int sourceByteCodeIndex, Throwable e,
      NormalMethod catchMethod, int catchByteCodeIndex) {
    RVMThread vmThread = Scheduler.getCurrentThread();
    if (Threads.isAgentThread(vmThread)) {
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("EXCEPTION_CATCH: skip system thread ", vmThread.getName());
      }
      return;
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
    notifyEvent(t, event);
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
    notifyEvent(t, event);
  }

  public void singleStep(RVMThread t, NormalMethod method, int bcindex) {
    if (VM.VerifyAssertions) {
      VM._assert(false, "not implemented");
    }
    if (singleStepState.checkStepCompletion(t, method, bcindex)) {
      VMMethod m = new VMMethod(method);
      Location loc = new Location(m, bcindex);
      SingleStepEvent e = new SingleStepEvent(t.getJavaLangThread(), loc, null);
      notifyEvent(t, e);
    }
  }
  private boolean checkMethodInvocation(RVMThread t) {
    try {
      MethodInvokeRequest req = threadLocalInfos
          .getAndRemoveMethodInvokeRequest(t);
      if (req == null) {
        return false;
      }
      threadLocalInfos.setInvokingMethod(t, true);
      req.execute();
    } finally {
      threadLocalInfos.setInvokingMethod(t, false);
    }
    return true;
  }
}

final class SuspendedThreadState {
  
  /** JDWP agent specific per-thread information. */
  static final class SuspendInfo {
  
    /** The JDWP suspension count. */
    int suspendCount = 0;
  
    /** The debugee thread requested suspending itself. */
    boolean selfSuspended = false;
  
    SuspendInfo() {}
  }
  /** The JDWP specific user thread information. */
  private HashMapRVM<RVMThread, SuspendInfo> suspendedThreads = 
    new HashMapRVM<RVMThread, SuspendInfo>();

  synchronized private SuspendInfo createSuspendInfo(RVMThread t) {
    if (VM.VerifyAssertions) {
      VM._assert(suspendedThreads.get(t) == null);
    }
    SuspendInfo si = new SuspendInfo();
    suspendedThreads.put(t, si);
    return si;
  }

  synchronized boolean isSuspended(RVMThread t) {
    SuspendInfo si = suspendedThreads.get(t);
    return si != null;
  }

  synchronized int getSuspendCount(RVMThread t) {
    SuspendInfo si = suspendedThreads.get(t);
    if (si == null) {return 0;}
    else {return si.suspendCount;}
  }

  void increaseSuspendCount(RVMThread t) {
    if (VM.VerifyAssertions) {
      VM._assert(isSuspended(t));
    }
    suspendedThreads.get(t).suspendCount++;
  }
  
  void decreaseSuspendCount(RVMThread t) {
    if (VM.VerifyAssertions) {
      VM._assert(isSuspended(t));
    }
    SuspendInfo si = suspendedThreads.get(t);
    si.suspendCount--;
    if (VM.VerifyAssertions) {
      VM._assert(si.suspendCount >=0);
    }
  }

  synchronized void release(RVMThread t) {
    if (VM.VerifyAssertions) {
      VM._assert(isSuspended(t));
    }
    SuspendInfo si = suspendedThreads.get(t);
    if (si == null) {return;}
    else {
      if (VM.VerifyAssertions) {
        VM._assert(si.suspendCount == 1);
      }
      suspendedThreads.remove(t);
    }
  }

  synchronized boolean isSelfSuspended(RVMThread t) {
    SuspendInfo si = suspendedThreads.get(t);
    if (si == null) {return false;}
    else {
      return si.selfSuspended;
    }
  }
  synchronized void addSelfSuspendedThread(RVMThread t) {
    SuspendInfo si = createSuspendInfo(t);
    si.suspendCount = 1;
    si.selfSuspended = true;
  }
  
  synchronized void addSuspendedThread(RVMThread t) {
    SuspendInfo si = createSuspendInfo(t);
    si.suspendCount = 1;
    si.selfSuspended = false;
  }

  synchronized LinkedListRVM<RVMThread> decreaseSuspendCountAndGetResumableThreads() {
    LinkedListRVM<RVMThread> actuallyresumedThreads = new LinkedListRVM<RVMThread>();
    for(RVMThread t : suspendedThreads.keys()) {
      SuspendInfo si = suspendedThreads.get(t);
      si.suspendCount--;
      if (si.suspendCount <= 0 ) {
        actuallyresumedThreads.add(t);
      }
    }
    
    for(RVMThread t: actuallyresumedThreads) {
      suspendedThreads.remove(t);
    }
    
    return actuallyresumedThreads;
  }
}


final class MethodInvokeRequest {
  private final Object thisArg;
  private final RVMMethod method;
  private final Object[] otherArgs;

  /** The result of method invocation. */
  private MethodResult methodResult;
  
  /** Constructor. */
  MethodInvokeRequest(RVMMethod method, Object obj,
      Object[] otherArgs) {
    this.method = method;
    this.thisArg = obj;
    this.otherArgs = otherArgs;
  }

  synchronized MethodResult waitForResult() {
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

  synchronized void execute() {
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


class ThreadLocalInfos {
  static class ThreadLocalInfo {
    boolean isNotifyingEvent = false;
    boolean isInvokingMethod = false;
    MethodInvokeRequest methodInvokeRequest = null;
    ThreadLocalInfo() {}
  }
  private HashMapRVM<RVMThread,ThreadLocalInfo> threadLocalInfos = 
    new HashMapRVM<RVMThread,ThreadLocalInfo>();
  ThreadLocalInfos() {}

  synchronized boolean isNotifyingEvent(RVMThread thread) {
    ThreadLocalInfo li = threadLocalInfos.get(thread);
    if (li == null) {return false;}
    else {return li.isNotifyingEvent;}
  }

  synchronized void setNotifyingEvent(RVMThread thread, boolean v) {
    ThreadLocalInfo li = threadLocalInfos.get(thread);
    if (li == null) {
      li = new ThreadLocalInfo();
      threadLocalInfos.put(thread,li);
    }
    li.isNotifyingEvent = v;
  }
  
  synchronized boolean isInvokingMethod(RVMThread thread) {
    ThreadLocalInfo li = threadLocalInfos.get(thread);
    if (li == null) {return false;}
    else {return li.isInvokingMethod;}
  }

  synchronized void setInvokingMethod(RVMThread thread, boolean v) {
    ThreadLocalInfo li = threadLocalInfos.get(thread);
    if (li == null) {
      li = new ThreadLocalInfo();
      threadLocalInfos.put(thread,li);
    }
    li.isInvokingMethod = v;
  }

  synchronized void release(RVMThread t) {
    threadLocalInfos.remove(t);
  }

  synchronized void addMethodInvokeRequest(RVMThread t,MethodInvokeRequest req) {
    ThreadLocalInfo si = threadLocalInfos.get(t);
    if (VM.VerifyAssertions) {
      VM._assert(si != null,
          "can not send method invocation to an active thread: " + t);
    }
    si.methodInvokeRequest = req;
  }

  synchronized MethodInvokeRequest getAndRemoveMethodInvokeRequest(RVMThread t) {
    ThreadLocalInfo si = threadLocalInfos.get(t);
    if (si == null) {return null;}
    else {
      threadLocalInfos.remove(t);
      return si.methodInvokeRequest;
    }
  }   
}

class SingleStepState {
  private static class SingleStepInfo {
    private final EventRequest singleStepRequest;
    private final NormalMethod beginMethod;
    private final int beginByteCodeIndex;
    private final int beginFrameDepth;
    private final StepFilter stepFilter;
    public SingleStepInfo(EventRequest singleStepRequest,
        NormalMethod beginMethod,int beginByteCodeIndex, 
        int beginFrameDepth) {
      this.beginByteCodeIndex = beginByteCodeIndex;
      this.beginFrameDepth = beginFrameDepth;
      this.beginMethod = beginMethod;
      this.singleStepRequest = singleStepRequest;
      this.stepFilter = VMVirtualMachineImpl.getStepFilter(singleStepRequest);
      if (VM.VerifyAssertions) {
        VM._assert(singleStepRequest.getEventKind() == EventRequest.EVENT_SINGLE_STEP);
      }
    }
    final EventRequest getSingleStepRequest() {
      return singleStepRequest;
    }
    final NormalMethod getBeginMethod() {
      return beginMethod;
    }
    final int getBeginByteCodeIndex() {
      return beginByteCodeIndex;
    }
    final int getBeginFrameDepth() {
      return beginFrameDepth;
    }
  }

  private HashMapRVM<RVMThread, SingleStepInfo> stepStates = 
    new HashMapRVM<RVMThread, SingleStepInfo>();

  synchronized void setSingleStep(EventRequest singleStepRequest,
      NormalMethod beginMethod,int beginByteCodeIndex, 
      int beginFrameDepth) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(singleStepRequest.getEventKind() == EventRequest.EVENT_SINGLE_STEP);
    }
    StepFilter filter = VMVirtualMachineImpl.getStepFilter(singleStepRequest);
    if (VM.VerifyAssertions) {VM._assert(filter != null);}
    RVMThread t = JikesRVMSupport.getThread(filter.getThread().getThread());
    if (VM.VerifyAssertions) {VM._assert(stepStates.get(t) == null);}
    SingleStepInfo si = new SingleStepInfo(singleStepRequest, beginMethod,
        beginByteCodeIndex, beginFrameDepth);
    stepStates.put(t,si);
  }
  
  synchronized void resetSingleStep(RVMThread t) {
    stepStates.remove(t);
  }
  
  synchronized boolean checkStepCompletion(RVMThread t,
      NormalMethod method, int bcindex) {
    int depth = StackFrame.getFrameCount(t);
    SingleStepInfo si =  stepStates.get(t);
    StepFilter stepfilter = si.stepFilter;
    int stepDepth = stepfilter.getDepth();
    int stepSize = stepfilter.getSize();
    
    switch (stepDepth) {
    case JdwpConstants.StepDepth.INTO:
      if (stepSize == JdwpConstants.StepSize.MIN) {
        return !(si.getBeginByteCodeIndex() == bcindex);
      } else if (stepSize == JdwpConstants.StepSize.LINE) {
        int beginLine = si.getBeginMethod().getLineNumberForBCIndex(
            si.getBeginByteCodeIndex());
        int line = method.getLineNumberForBCIndex(bcindex);
        return (line > 0)
            && !(beginLine == line && si.getBeginMethod() == method);
      }
      break;
    case JdwpConstants.StepDepth.OUT:
      if (stepSize == JdwpConstants.StepSize.MIN) {
        return si.getBeginFrameDepth() > StackFrame.getFrameCount(t);
      } else if (stepSize == JdwpConstants.StepSize.LINE) {
        int line = method.getLineNumberForBCIndex(bcindex);
        return (line > 0)
            && si.getBeginFrameDepth() > StackFrame.getFrameCount(t)
            && method.getLineNumberForBCIndex(bcindex) > 0;
      }
      break;
    case JdwpConstants.StepDepth.OVER:
      if (stepSize == JdwpConstants.StepSize.MIN) {
        return depth <= si.getBeginFrameDepth();
      } else if (stepSize == JdwpConstants.StepSize.LINE) {
        int beginLine = si.getBeginMethod().getLineNumberForBCIndex(
            si.getBeginByteCodeIndex());
        int line = method.getLineNumberForBCIndex(bcindex);
        return (line > 0) && (depth <= si.getBeginFrameDepth())
            && !(beginLine == line && si.getBeginMethod() == method);
      }
      break;
    }
  if (VM.VerifyAssertions) {
    VM._assert(false, "not reachable");
  }
  return false;
  }
}

