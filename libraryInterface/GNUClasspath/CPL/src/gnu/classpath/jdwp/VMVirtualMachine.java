/* VMVirtualMachine.java -- JikesRVM implementation of VMVirtualMachine */

package gnu.classpath.jdwp;

import gnu.classpath.jdwp.event.ClassPrepareEvent;
import gnu.classpath.jdwp.event.Event;
import gnu.classpath.jdwp.event.EventRequest;
import gnu.classpath.jdwp.event.ExceptionEvent;
import gnu.classpath.jdwp.event.ThreadStartEvent;
import gnu.classpath.jdwp.event.VmDeathEvent;
import gnu.classpath.jdwp.event.VmInitEvent;
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
import org.jikesrvm.classloader.MemberReference;
import org.jikesrvm.classloader.MethodReference;
import org.jikesrvm.classloader.NativeMethod;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.compilers.baseline.BaselineCompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.compilers.opt.runtimesupport.OptCompiledMethod;
import org.jikesrvm.compilers.opt.runtimesupport.OptEncodedCallSiteTree;
import org.jikesrvm.compilers.opt.runtimesupport.OptMachineCodeMap;
import org.jikesrvm.jni.JNICompiledMethod;
import org.jikesrvm.memorymanagers.mminterface.MM_Interface;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.Scheduler;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.util.HashMapRVM;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.jikesrvm.ArchitectureSpecific.StackframeLayoutConstants;

/** JikesRVM Specific implementation of VMVirtualMachine. */
public final class VMVirtualMachine 
  implements StackframeLayoutConstants {

	/** JDWP JVM capabilities - mostly disabled now.*/
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
	private static final JDWPEventNotifier jdwpNotifier = new JDWPEventNotifier();

	/** The JDWP specific user thread information. */
	private static final HashMapRVM<RVMThread,ThreadInfo> threadInfo = 
	  new HashMapRVM<RVMThread,ThreadInfo>();

	/** The internal initialization process. */
	public static void boot() {
    Callbacks.addStartupMonitor(jdwpNotifier);
    Callbacks.addExitMonitor(jdwpNotifier);
    Callbacks.addClassResolvedMonitor(jdwpNotifier);
    Callbacks.addClassLoadedMonitor(jdwpNotifier);
    Callbacks.addThreadStartedMonitor(jdwpNotifier);
    Callbacks.addThreadEndMonitor(jdwpNotifier);
	}

  /** Suspend a debugee thread. */
  public static void suspendThread(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) VM._assert(thread != null);
    RVMThread vmthread = JikesRVMSupport.getThread(thread);
    if (VM.VerifyAssertions) VM._assert(isDebugeeThread(vmthread));
    ThreadInfo ti = threadInfo.get(vmthread);
    if (VM.VerifyAssertions) VM._assert(ti != null);

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("suspendThread: ...suspendcount = ",
          ti.getSuspendCount(), thread.getName());
    }
    synchronized(thread) {
      ti.incSuspendCount();
      vmthread.suspend();
    }
  }

	/** Suspend all the debuggee threads. */
  public static void suspendAllThreads() throws JdwpException {
    for(final RVMThread vmThread : threadInfo.keys() ) {
      Thread t = vmThread.getJavaLangThread();
      suspendThread(t);
    }
  }

	/** Resume a debugee thread. */
	public static void resumeThread(final Thread thread) throws JdwpException {
	  RVMThread vmthread = JikesRVMSupport.getThread(thread);
		if (!isDebugeeThread(vmthread)) throw new InvalidThreadException(-1);
		ThreadInfo ti = threadInfo.get(vmthread);
    if (VM.VerifyAssertions) VM._assert(ti != null);

		final int suspendCount = ti.getSuspendCount();
		if ( suspendCount <=  0){
			return;
		}
		synchronized(thread) {
  		vmthread.resume();
  		ti.decSuspendCount();
		}
	}

	/** Resume all threads. */
	public static void resumeAllThreads() throws JdwpException {
    for(final RVMThread vmThread : threadInfo.keys() ) {
      Thread t = vmThread.getJavaLangThread();
      resumeThread(t);
    }
	}

	/** Get the suspend count for a thread. */
	public static int getSuspendCount(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) {
      VM._assert(thread != null);
    }
    RVMThread vmThread = JikesRVMSupport.getThread(thread);
    ThreadInfo ti = threadInfo.get(vmThread);
    if (ti == null) {
      throw new InvalidThreadException(-1);
    }
    return ti.getSuspendCount();
  };

	/** Returns the status of a thread. */
	public static int getThreadStatus(final Thread thread) throws JdwpException {
    if (VM.VerifyAssertions) VM._assert(thread != null);
    final RVMThread vmThread = java.lang.JikesRVMSupport.getThread(thread);
    ThreadInfo ti = threadInfo.get(vmThread);
    if (ti == null) {
      throw new InvalidThreadException(-1);
    }

    // using switch statement does not work here since
    // the javac takes the enum integer constant values from
    // its JDK Thread.State, and Thread.State enum constants values in
    // the JDK Threads.State and the GNU Thread.State could be different.
    // This case actually happened.
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
      VM.sysWrite("getThreadStatus:", thread.getName(), " vmthread status =", vmThread
          .getState().toString());
      VM.sysWriteln(" jdwpstatus =", getThreadStateName(status));
    }
    return status;
  }

	/** Returns the number of loaded classes in the VM. */
	public static int getAllLoadedClassesCount() throws JdwpException{
		final int rValue = getAllLoadedClasses().size();
		if (JikesRVMJDWP.getVerbose() >= 2) {
		  VM.sysWriteln("getAllLoadedClassesCount: ",rValue );
		}
		return rValue;
	};

	/** Return all loaded classes. */
	public static Collection<Class<?>> getAllLoadedClasses()
			throws JdwpException {
	  if (JikesRVMJDWP.getVerbose() >= 2) {
	    VM.sysWriteln("getAllLoadedClasses: ");
	  }
	  Class<?>[] loadedClasses = JikesRVMSupport.getAllLoadedClasses();
		final int len1 = loadedClasses.length + 2;
		 final Class<?>[] classes = new Class<?> [len1];
		 int idx = 0;
		 for(Class<?> clazz :loadedClasses) {
			 classes[ idx++ ] = clazz;
		 }
		 classes[idx++] = java.lang.Error.class;
		 classes[idx++] = java.lang.Throwable.class;
		 return Arrays.asList(classes);
	};

	/** Return the class status.*/
	public static int getClassStatus(final Class<?> clazz) throws JdwpException{
		if(null  == clazz)
			throw new InvalidClassException(-1);

		RVMType vmType = JikesRVMSupport.getTypeForClass(clazz);
		if (!(vmType instanceof RVMClass)) {
		  throw new InvalidClassException(-1);
		}
		RVMClass cls = (RVMClass) vmType;
		int rValue = getStatus(cls);

		if (JikesRVMJDWP.getVerbose() >= 2) {
		  VM.sysWrite("getClassStatus:", clazz.getName());
		  VM.sysWriteln(" value = ", rValue);
		}
		return rValue;
	}

	/** Get the class status.*/
	private static int getStatus(RVMClass cls) {
	  int rValue = 0;
    if(cls.isLoaded())
      rValue |= JdwpConstants.ClassStatus.VERIFIED;
    if (cls.isResolved())
      rValue |= JdwpConstants.ClassStatus.PREPARED;
    if( cls.isInstantiated())
      rValue |= JdwpConstants.ClassStatus.INITIALIZED;
    rValue = (0 == rValue) ?  JdwpConstants.ClassStatus.ERROR: rValue;
    return rValue;
	}
	
	/** Get the all the declared method in a class. */
	public static VMMethod[] getAllClassMethods(final Class<?> clazz)
			throws JdwpException{

	  RVMType vmType = java.lang.JikesRVMSupport.getTypeForClass(clazz);
		RVMClass vmClass = (RVMClass) vmType;
		
		// Calculate length.
		RVMMethod[] rmethods = vmClass.getDeclaredMethods();
		VMMethod[] vmMethods = new VMMethod[rmethods.length];
		int index = 0;
		for (RVMMethod rvmMethod : vmClass.getDeclaredMethods()) {
			vmMethods[index++] = new VMMethod(rvmMethod);
		}

		if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("getAllClassMethods for class : ",clazz.getName());
    } 

		return vmMethods;
	}

	/** Dump call stack frames in a suspended thread. */
	public static ArrayList<VMFrame> getFrames(final Thread thread,
			final int start, final int length) throws JdwpException{
	  // "Returns the current call stack of a suspended thread. The sequence of
    // frames starts with the currently executing frame, followed by its caller,
    // and so on. The thread must be suspended, and the returned frameID is
    // valid only while the thread is suspended."
	  // [jdwp-protocol.html#JDWP_ThreadReference_Frames]
	  if (VM.VerifyAssertions) { VM._assert(thread != null);}
	  final RVMThread vmThread = JikesRVMSupport.getThread(thread);
	  if (!isDebugeeThread(vmThread) || !vmThread.isAlive()) {
	    throw new InvalidThreadException(-1);
	  }
	  ThreadInfo ti = threadInfo.get(vmThread);
	  if (ti.getSuspendCount() <= 0) {
	    throw new InvalidThreadException(-1);
	  }

	  if (start < 0 || length < -1) {
      return new ArrayList<VMFrame>(); // simply return empty frame list.
    }

		final ArrayList<VMFrame> vmFrames = new ArrayList<VMFrame>();
		stackWalkInSuspendedThread(vmThread, new StackFrameVisitor() {
		  int countDown = start;
		  int remaining = length;
		  boolean visit(int fno, NormalMethod m, int bcIndex, 
	        BaselineCompiledMethod bcm, Offset ip, Offset fp) {
		    if(countDown > 0) {countDown--;return true;}
	      else if (length != -1 && remaining <= 0) {return false;}
	      else {remaining--;}
		    VMFrame f = new VMBaseFrame(fno, m, bcIndex, vmThread, bcm, ip, fp); 
		    vmFrames.add(f);
		    return true;
		  }

	    boolean visit(int frameno, NormalMethod m, int bcIndex,
	        OptCompiledMethod ocm, Offset ipOffset, Offset fpOffset, int iei) {
	      if(countDown > 0) {countDown--;return true;}
	      else if (length != -1 && remaining <= 0) {return false;}
	      else {remaining--;}
        VMFrame f = new VMOptFrame(frameno, m, bcIndex, vmThread, ocm, 
           ipOffset, fpOffset, iei); 
        vmFrames.add(f);
	      return true;
	    }

	    boolean visit(int frameno, NativeMethod m) {
	      if(countDown > 0) {countDown--;return true;}
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
    if (VM.VerifyAssertions) { VM._assert(thread != null);}
    final RVMThread vmThread = JikesRVMSupport.getThread(thread);
    if (!isDebugeeThread(vmThread) || !vmThread.isAlive()) {
      throw new InvalidThreadException(-1);
    }
    ThreadInfo ti = threadInfo.get(vmThread);
    if (ti.getSuspendCount() <= 0) {
      throw new InvalidThreadException(-1);
    }
    
    int threadID = (int)(frameID >>> 32);
    final int fid = (int)(frameID & 0x0FFFFFFFFL);
    if (threadID != vmThread.getIndex()) {
      throw new InvalidFrameException(frameID);
    }
    
    final class FrameRef {VMFrame f;}
    final FrameRef fref = new FrameRef();
	  stackWalkInSuspendedThread(vmThread, new StackFrameVisitor() {
	    boolean visit(int fno, NormalMethod m, int bcIndex,
          BaselineCompiledMethod bcm, Offset ip, Offset fp) {
        if (fno == fid) {
          fref.f = new VMBaseFrame(fno, m, bcIndex, vmThread, bcm, ip, fp);
          return false;
        } else { return true; }
      }

	    boolean visit(int fno, NormalMethod m, int bcIndex,
          OptCompiledMethod ocm, Offset ip, Offset fp, int iei) {
        if (fno == fid) {
          fref.f = new VMOptFrame(fno, m, bcIndex, vmThread, ocm, ip, fp, iei);
          return false;
        } else {
          return true;
        }
      }

	    boolean visit(int fno, NativeMethod m) {
        if (fno == fid) {
          fref.f = new VMNativeFrame(fno, m, vmThread);
          return false;
        } else {
          return true;
        }
      }
    });

    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("getFrame", thread.getName(), " frameID = ", (int) frameID);
    }
    return fref.f;
  }

	/** Count the number of call frames in a suspended thread. */
	public static int getFrameCount(final Thread thread) 
		throws JdwpException{

    if (VM.VerifyAssertions) { VM._assert(thread != null);}
    final RVMThread vmThread = JikesRVMSupport.getThread(thread);
    if (!isDebugeeThread(vmThread) || !vmThread.isAlive()) {
      throw new InvalidThreadException(-1);
    }
    ThreadInfo ti = threadInfo.get(vmThread);
    if (ti.getSuspendCount() <= 0) {
      throw new InvalidThreadException(-1);
    }

    final class FrameCount { int count;}
    final FrameCount fcnt = new FrameCount();
     stackWalkInSuspendedThread(vmThread, new StackFrameVisitor() {
        boolean visit(int frameno, NormalMethod m, int bcIndex, 
           BaselineCompiledMethod bcm, Offset ipOffset, Offset fpOffset) {
          fcnt.count++;
          return true;
       }
       boolean visit(int frameno, NormalMethod m, int bcIndex,
           OptCompiledMethod ocm, Offset ipOffset, Offset fpOffset, int iei) {
         fcnt.count++;
         return true;
       }
       boolean visit(int frameno, NativeMethod m) {
         fcnt.count++;
         return true;
       }
     });
     
		if (JikesRVMJDWP.getVerbose() >= 2) {
		  VM.sysWriteln("getFrameCount: ", thread.getName(), " count = ",fcnt.count);
		}
		return fcnt.count;
	}

	/** Get a list of classes which were requested to be loaded by a class loader.
	 */
	public static ArrayList<Class<?>> getLoadRequests(final ClassLoader cl)
			throws JdwpException {
		if (JikesRVMJDWP.getVerbose() >= 2) {
		  VM.sysWriteln("getLoadRequests: ");
		}
		Class<?>[] classes = JikesRVMSupport.getInitiatedClasses(cl);
		return new ArrayList<Class<?>>(Arrays.asList(classes)); 
	};

  /** Execute a Java method in a suspended thread. */
  public static MethodResult executeMethod(Object obj, Thread thread,
      Class<?> clazz, VMMethod method, Value[] values, int options)
      throws JdwpException {
    throw new NotImplementedException("executeMethod");
  }

	/** Get the source file name for a class. */
	public static String getSourceFile(Class<?> clazz) throws JdwpException {
		RVMType vmType = java.lang.JikesRVMSupport.getTypeForClass(clazz);
		RVMClass vmClass = (RVMClass) vmType;
		String rValue = vmClass.getSourceName().toString();
		if (JikesRVMJDWP.getVerbose() >= 2) {
		  VM.sysWrite("getSourceFile ", clazz.getName());
		  VM.sysWriteln(" , Source file name = ", rValue);
		}
		return rValue;
	};

  /** Set a JDWP event.*/
	public static void registerEvent(final EventRequest request)
			throws JdwpException {

	  if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWrite("registerEvent[ID: ", request.getId());
      VM.sysWrite("Event: ", getEventKindName(request.getEventKind()));
      VM.sysWriteln("Suspend: ", request.getSuspendPolicy(), "]");
    }

    final byte eventKind = request.getEventKind();
    switch (eventKind) {
    case JdwpConstants.EventKind.SINGLE_STEP: {
      VM.sysWriteln("SINGLE_STEP event is not implemented");
      throw new NotImplementedException("SINGLE_STEP event is not implemented");
    }
    case JdwpConstants.EventKind.BREAKPOINT: {
      VM.sysWriteln("BREAKPOINT event is not implemented");
      throw new NotImplementedException("BREAKPOINT event is not implemented");
    }
    case JdwpConstants.EventKind.FRAME_POP: {
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
      if (JikesRVMJDWP.getVerbose() >= 1) {
        VM.sysWriteln("ignoring set(CLASS_UNLOAD)");
      }
      break;
    }
    case JdwpConstants.EventKind.CLASS_LOAD: {
      jdwpNotifier.setClassLoaedEnabled(true);
      break;
    }
    case JdwpConstants.EventKind.FIELD_ACCESS: {
      VM.sysWriteln("FIELD_ACCESS event is not implemented.");
      throw new NotImplementedException("FIELD_ACCESS");
    }
    case JdwpConstants.EventKind.FIELD_MODIFICATION: {
      VM.sysWriteln("FIELD_MODIFICATION event is not implemented.");
      throw new NotImplementedException("FIELD_MODIFICATION");
    }
    case JdwpConstants.EventKind.EXCEPTION_CATCH: {
      VM.sysWriteln("EXCEPTION_CATCH event is not implemented.");
      throw new NotImplementedException("EXCEPTION_CATCH");
    }
    case JdwpConstants.EventKind.METHOD_ENTRY: {
      VM.sysWriteln("METHOD_ENTRY event is not implemented.");
      throw new NotImplementedException("METHOD_ENTRY");
    }
    case JdwpConstants.EventKind.METHOD_EXIT: {
      VM.sysWriteln("METHOD_EXIT event is not implemented.");
      throw new NotImplementedException("METHOD_EXIT");
    }
    case JdwpConstants.EventKind.VM_START: {
      //ignore the VM_START enable/disable.
      // "This event is always generated by the target VM, even if not explicitly requested. "
      // [http://java.sun.com/javase/6/docs/jpda/
      //  jdwp/jdwp-protocol.html#JDWP_EventRequest_Clear]
      break;
    }
    case JdwpConstants.EventKind.VM_DEATH: {
      jdwpNotifier.setVmDeathEnabled(true);
      break;
    }
    case JdwpConstants.EventKind.VM_DISCONNECTED: {
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
	public static void unregisterEvent(EventRequest request)
			throws JdwpException {
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWrite("unregisterEvent[ID: ", request.getId());
      VM.sysWrite("Event: ", getEventKindName(request.getEventKind()));
      VM.sysWriteln("Suspend: ", request.getSuspendPolicy(), "]");
    }
	   
    final byte eventKind = request.getEventKind();
    switch (eventKind) {
    case JdwpConstants.EventKind.SINGLE_STEP: {
      VM.sysWriteln("SINGLE_STEP event is not implemented");
      throw new NotImplementedException("SINGLE_STEP event is not implemented");
    }
    case JdwpConstants.EventKind.BREAKPOINT: {
      VM.sysWriteln("BREAKPOINT event is not implemented");
      throw new NotImplementedException("BREAKPOINT event is not implemented");
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
      throw new NotImplementedException("VM_DISCONNECTED event is not implemented");
    }
    default: {
      VM.sysWriteln("Can not recognize the event type ", eventKind);
      throw new InvalidEventTypeException(eventKind);
    }
    }
	};

	/** Clear all the JDWP event. */
	public static void clearEvents(byte kind) throws JdwpException{
    if (JikesRVMJDWP.getVerbose() >= 2) {
      VM.sysWriteln("clearEvents[Event: ", getEventKindName(kind));
    }
    switch (kind) {
    case JdwpConstants.EventKind.SINGLE_STEP: {
      VM.sysWriteln("SINGLE_STEP event is not implemented");
      throw new NotImplementedException("SINGLE_STEP");
    }
    case JdwpConstants.EventKind.BREAKPOINT: {
      VM.sysWriteln("BREAKPOINT event is not implemented");
      throw new NotImplementedException("BREAKPOINT");
    }
    case JdwpConstants.EventKind.FRAME_POP: {
      VM.sysWriteln("FRAME_POP event is not implemented");
      throw new NotImplementedException("FRAME_POP");
    }
    case JdwpConstants.EventKind.USER_DEFINED: {
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
      VM.sysWriteln("CLASS_UNLOAD event is not implemented");
      throw new NotImplementedException("CLASS_UNLOAD");
    }
    case JdwpConstants.EventKind.CLASS_LOAD: {
      jdwpNotifier.setClassLoaedEnabled(false);
      break;
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
      jdwpNotifier.setExceptionCatchEnabled(false);
    }
    case JdwpConstants.EventKind.METHOD_ENTRY: {
      VM.sysWriteln("METHOD_ENTRY event is not implemented");
      throw new NotImplementedException("METHOD_ENTRY");
    }
    case JdwpConstants.EventKind.METHOD_EXIT: {
      VM.sysWriteln("METHOD_EXIT event is not implemented");
      throw new NotImplementedException("METHOD_EXITd");
    }
    case JdwpConstants.EventKind.VM_START: {
      //ignore the VM_START can not disabled.
      // "This event is always generated by the target VM, even if not explicitly requested. "
      // [http://java.sun.com/javase/6/docs/jpda/
      //  jdwp/jdwp-protocol.html#JDWP_EventRequest_Clear]
      break;
    }
    case JdwpConstants.EventKind.VM_DEATH: {
      jdwpNotifier.setVmDeathEnabled(false);
      break;
    }
    case JdwpConstants.EventKind.VM_DISCONNECTED: {
      VM.sysWriteln("VM_DISCONNECTED should not be requested.");
      throw new NotImplementedException("VM_DISCONNECTED event is not implemented");
    }
    default: {
      VM.sysWriteln("Can not recognize the event type ", kind);
      throw new InvalidEventTypeException(kind);
    }
    }
	};

	 /** Redefine a class byte code.*/
  public static void redefineClasses(Class<?>[] types, byte[][] bytecodes)
      throws JdwpException {
    VM.sysWriteln("redefineClasses is not implemnted!");
    throw new NotImplementedException("redefineClasses");
  };

 /** Set the default stratum.*/
  public static void setDefaultStratum(String stratum) throws JdwpException {
    VM.sysWriteln("setDefaultStratum is not implemnted!");
    throw new NotImplementedException("setDefaultStratum");
  };

 /** Get the source debug extension atrribute for a class.*/
  public static String getSourceDebugExtension(Class<?> klass)
      throws JdwpException {
    VM.sysWriteln("getSourceDebugExtension is not implemnted!");
    throw new NotImplementedException("getSourceDebugExtension");
  }

 /** Get a byte codes for a method.*/
  public static final byte[] getBytecodes(VMMethod method) throws JdwpException {
    VM.sysWriteln("getBytecodes is not implemnted!");
    throw new NotImplementedException("getBytecodes");
  }

 /** Get a monitor info. */
  public static MonitorInfo getMonitorInfo(Object obj) throws JdwpException {
    VM.sysWriteln("getMonitorInfo is not implemnted!");
    throw new NotImplementedException("getMonitorInfo");
  }

	/** Get a owned monitor.*/
  public static Object[] getOwnedMonitors(Thread thread) throws JdwpException {
    VM.sysWriteln("getOwnedMonitors is not implemnted!");
    throw new NotImplementedException("getOwnedMonitors");
  }

 /** Get a current contened monitor. */
  public static Object getCurrentContendedMonitor(Thread thread)
      throws JdwpException {
    VM.sysWriteln("getCurrentContendedMonitor is not implemnted!");
    throw new NotImplementedException("getCurrentContendedMonitor");
  };

  /** Pop all the frames until the given frame in a suspened thread. */
  public static void popFrames(Thread thread, long frameId)
      throws JdwpException {
    VM.sysWriteln("popFrames is not implemnted!");
    throw new NotImplementedException("popFrames");
  }

  /**Checks whether or not a thread is debuggable thread. */
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
    if (thread == null) { return false;}
    Thread jthread = thread.getJavaLangThread();
    if (jthread == null) { return false;}
    ThreadGroup jgroup = jthread.getThreadGroup(); 
    return  jgroup == Jdwp.getDefault().getJdwpThreadGroup();
  }

  /** Get a readable JDWP event kind name from the JDWP constant.*/
  private static String getEventKindName(final byte kind) {
    switch (kind) {
    case JdwpConstants.EventKind.SINGLE_STEP: return "SINGLE_STEP";
    case JdwpConstants.EventKind.BREAKPOINT: return "BREAKPOINT";
    case JdwpConstants.EventKind.FRAME_POP: return "FRAME_POP";
    case JdwpConstants.EventKind.EXCEPTION: return "EXCEPTION";
    case JdwpConstants.EventKind.USER_DEFINED: return "USER_DEFINED";
    case JdwpConstants.EventKind.THREAD_START: return "THREAD_START";
    case JdwpConstants.EventKind.THREAD_DEATH: return "THREAD_DEATH";
    case JdwpConstants.EventKind.CLASS_PREPARE: return "CLASS_PREPARE";
    case JdwpConstants.EventKind.CLASS_UNLOAD: return "CLASS_UNLOAD";
    case JdwpConstants.EventKind.CLASS_LOAD: return "CLASS_LOAD";
    case JdwpConstants.EventKind.FIELD_ACCESS: return "FIELD_ACCESS";
    case JdwpConstants.EventKind.FIELD_MODIFICATION: return "FIELD_MODIFICATION";
    case JdwpConstants.EventKind.EXCEPTION_CATCH: return "EXCEPTION_CATCH";
    case JdwpConstants.EventKind.METHOD_ENTRY: return "METHOD_ENTRY";
    case JdwpConstants.EventKind.METHOD_EXIT: return "METHOD_EXIT";
    case JdwpConstants.EventKind.VM_START: return "VM_START";
    case JdwpConstants.EventKind.VM_DEATH: return "VM_DEATH";
    case JdwpConstants.EventKind.VM_DISCONNECTED: return "VM_DISCONNECTED";
    default:return "unknown";
    }
  }

  /** Get a readable JDWP thraed status name from the JDWP constant.*/
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

  /** Walk the call frames in the suspended thread. */
  private static void stackWalkInSuspendedThread(RVMThread t,
      StackFrameVisitor v) {
    if (VM.VerifyAssertions) {
      VM._assert(t.isAlive() && t.getState() == Thread.State.WAITING);
      VM._assert(Scheduler.getCurrentThread() != t);
    }
    int fno = 0;
    Address fp = t.contextRegisters.getInnermostFramePointer();
    Address ip = t.contextRegisters.getInnermostInstructionAddress();
    while(Magic.getCallerFramePointer(fp).NE(STACKFRAME_SENTINEL_FP)) {
      if (!MM_Interface.addressInVM(ip)) {
        // skip the nativeframes until java frame or the end.
        while(!MM_Interface.addressInVM(ip) 
            && !fp.NE(STACKFRAME_SENTINEL_FP)) {
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
          //skip
        } else {
          CompiledMethod cm = CompiledMethods.getCompiledMethod(cmid);
          if (VM.VerifyAssertions) {
            VM._assert(cm != null, "no compiled method for cmid ="  
                  +cmid + " in thread " + t.getName());
          }
          int compilerType = cm.getCompilerType();
          switch(compilerType) {
          case CompiledMethod.BASELINE: {
            BaselineCompiledMethod bcm = (BaselineCompiledMethod)cm;
            NormalMethod method = (NormalMethod)cm.getMethod();
            Offset ipOffset = bcm.getInstructionOffset(ip, false);
            int bcindex = bcm.findBytecodeIndexForInstruction(ipOffset);
            Address stackbeg = Magic.objectAsAddress(t.getStack());
            Offset fpOffset = fp.diff(stackbeg);
            if (JikesRVMJDWP.getVerbose() >= 3 ) { showMethod(method, bcindex, ip, fp);}
            if (!v.visit(fno++, method, bcindex, bcm, ipOffset, fpOffset)) {
              return; //finish frame walk.
            }
            break;
          }
          case CompiledMethod.OPT: {
            final Address stackbeg = Magic.objectAsAddress(t.getStack());
            final Offset fpOffset = fp.diff(stackbeg);
            OptCompiledMethod ocm = (OptCompiledMethod)cm;
            Offset ipOffset = ocm.getInstructionOffset(ip, false);
            OptMachineCodeMap m = ocm.getMCMap();
            int bcindex = m.getBytecodeIndexForMCOffset(ipOffset);
            NormalMethod method = m.getMethodForMCOffset(ipOffset);
            int iei = m.getInlineEncodingForMCOffset(ipOffset);
            if (JikesRVMJDWP.getVerbose() >= 3 ) { showMethod(method, bcindex, ip, fp);}
            if (!v.visit(fno++, method, bcindex, ocm, ipOffset, fpOffset, iei)) {
              return;
            }
            //visit more inlined call sites.
            int[] e = m.inlineEncoding;
            for(iei = OptEncodedCallSiteTree.getParent(iei, e);
              iei >= 0; 
              iei = OptEncodedCallSiteTree.getParent(iei, e)) {
              int mid = OptEncodedCallSiteTree.getMethodID(iei, e);
              MethodReference mref = MemberReference.getMemberRef(mid)
                  .asMethodReference(); 
              method = (NormalMethod)mref.getResolvedMember();
              bcindex = OptEncodedCallSiteTree.getByteCodeOffset(iei, e);
              if (JikesRVMJDWP.getVerbose() >= 3 ) { showMethod(method, bcindex, ip, fp);}
              if (!v.visit(fno++, method, bcindex, ocm, ipOffset, fpOffset, iei)) {
                return;
              }  
            }
            break;
          }
          case CompiledMethod.JNI: {
            JNICompiledMethod jcm = (JNICompiledMethod)cm;
            NativeMethod method = (NativeMethod)jcm.getMethod();
            if (JikesRVMJDWP.getVerbose() >= 3 ) { showMethod(method, ip, fp);}
            if (!v.visit(fno++, method)) {
              return;
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
          ip = Magic.getReturnAddress(fp);
          fp = Magic.getCallerFramePointer(fp);
        }
      }
    }    
  }

  /** Print the beginning of call frame. */
  private static void showPrologue(Address ip, Address fp) {
    VM.sysWrite("   at [ip = ");
    VM.sysWrite(ip);
    VM.sysWrite(", fp ");
    VM.sysWrite(fp);
    VM.sysWrite("] ");
  }

  /** Print a stack frame for the native method.*/
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

  /** Print a stack frame for the Java method.*/
  private static void showMethod(RVMMethod method, int bcindex, Address ip, Address fp) {
    showPrologue(ip, fp);
    if (method == null) {
      VM.sysWrite("<unknown method>");
    } else {
      VM.sysWrite(method.getDeclaringClass().getDescriptor());
      VM.sysWrite(" ");
      VM.sysWrite(method.getName());
      VM.sysWrite(method.getDescriptor());
    }
    if (bcindex > 0) {
      VM.sysWrite(" at bcindex ");
      VM.sysWriteInt(bcindex);
    }
    VM.sysWrite("\n");
  }

  /** JikesRVM call back handler. */
  private static final class JDWPEventNotifier implements
      Callbacks.StartupMonitor, Callbacks.ExitMonitor,
      Callbacks.ClassResolvedMonitor, Callbacks.ClassLoadedMonitor,
      Callbacks.ThreadStartMonitor, Callbacks.ThreadEndMonitor,
      Callbacks.ExceptionCatchMonitor {
    JDWPEventNotifier() {
    }
    /** Event enable flag. */
    private boolean vmExitEnabled = false;
    private boolean classPrepareEnabled = false;
    private boolean classLoaedEnabled = false;
    private boolean threadStartEnabled = false;
    private boolean threadEndEnabled = false;
    private boolean exceptionCatchEnabled = false;

    private synchronized final boolean isVmExitEnabled() {
      return vmExitEnabled;
    }
    private synchronized final void setVmDeathEnabled(boolean vmExitEnabled) {
      this.vmExitEnabled = vmExitEnabled;
    }
    private synchronized final boolean isClassPrepareEnabled() {
      return classPrepareEnabled;
    }
    private synchronized final void setClassPrepareEnabled(
        boolean classPrepareEnabled) {
      this.classPrepareEnabled = classPrepareEnabled;
    }
    private synchronized final boolean isClassLoadEnabled() {
      return classLoaedEnabled;
    }
    private synchronized final void setClassLoaedEnabled(boolean classLoaedEnabled) {
      this.classLoaedEnabled = classLoaedEnabled;
    }
    private synchronized final boolean isThreadStartEnabled() {
      return threadStartEnabled;
    }
    private synchronized final void setThreadStartEnabled(boolean threadStartEnabled) {
      this.threadStartEnabled = threadStartEnabled;
    }
    private synchronized final boolean isThreadEndEnabled() {
      return threadEndEnabled;
    }
    private synchronized final void setThreadEndEnabled(boolean threadEndEnabled) {
      this.threadEndEnabled = threadEndEnabled;
    }
    private synchronized final boolean isExceptionCatchEnabled() {
      return exceptionCatchEnabled;
    }
    private synchronized final void setExceptionCatchEnabled(
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
      if (!isVmExitEnabled()) {return;}
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("Firing VM_DEATH");
      }
      Jdwp.notify(new VmDeathEvent());
    }

    public void notifyClassResolved(RVMClass vmClass) {
      if (!isClassPrepareEnabled()) {return;}
      Class<?> clazz = JikesRVMSupport.createClass(vmClass);
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

    public void notifyClassLoaded(RVMClass vmClass) {
      if (!isClassLoadEnabled()) {return;}
      Class<?> clazz = JikesRVMSupport.createClass(vmClass);
      if (JikesRVMJDWP.getVerbose() >= 2) {
        VM.sysWriteln("Firing CLASS_LOAD: ", clazz.getName());
      }
      Event event = new ClassPrepareEvent(Thread.currentThread(), clazz,
          JdwpConstants.ClassStatus.VERIFIED);
      Jdwp.notify(event);
    }

    public void notifyThreadStart(RVMThread vmThread) {
      if (VM.VerifyAssertions) {
        VM._assert(threadInfo.get(vmThread) == null);
      }
      if (!isDebugeeThread(vmThread)) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("THREAD_START: skip system thread ", vmThread.getName());
        }
        return;
      }
      threadInfo.put(vmThread, new ThreadInfo());

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

    public void notifyExceptionCatch(Throwable e, 
        NormalMethod sourceMethod, int sourceByteCodeIndex,
        NormalMethod catchMethod, int catchByteCodeIndex) {
      RVMThread vmThread = Scheduler.getCurrentThread();
      ThreadInfo ti = threadInfo.get(vmThread);
      if (ti == null) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("NOTIFY_EXCEPTION: skip system thread ", vmThread.getName());
        }
        return;
      }
      if (isExceptionCatchEnabled()) {
        if (JikesRVMJDWP.getVerbose() >= 2) {
          VM.sysWriteln("NOTIFY_EXCEPTION: firing : ", e.toString(), " in ", vmThread.getName());
        }
        Thread thread = vmThread.getJavaLangThread();
        Location sourceLoc = new Location(new VMMethod(sourceMethod), sourceByteCodeIndex);
        Location catchLoc =  catchMethod == null ? null :
            new Location(new VMMethod(catchMethod), catchByteCodeIndex);
        RVMClass rcls = sourceMethod.getDeclaringClass();
        Event event = new ExceptionEvent(e, thread, sourceLoc, catchLoc, rcls
            .getClassForType(), null);
        Jdwp.notify(event);
      }
    }
  }

  /** Stack frame walk call back interface. */
  private static abstract class StackFrameVisitor {
    /** return true if keep visiting frames.*/
    abstract boolean visit(int frameno, NormalMethod m, int bcIndex, 
        BaselineCompiledMethod bcm, Offset ipOffset, Offset fpOffset); 
    
    /** return true if keep visiting frames.*/
    abstract boolean visit(int frameno, NormalMethod m, int bcIndex,
        OptCompiledMethod ocm, Offset ipOffset, Offset fpOffset, int iei); 

    /** return true if keep visiting frames.*/
    abstract boolean visit(int frameno, NativeMethod m);
  }

  /** JDWP specific thread status. */
  private static class ThreadInfo {

    /** The JDWP suspension count.*/
    private int suspendCount = 0;

    /** Getter/setters for the suspendCount. */
    public int getSuspendCount() {
      return suspendCount;
    }

    public void decSuspendCount() {
      suspendCount--;
    }

    public void incSuspendCount() {
      suspendCount++;
    }
  }
}
