/* VMVirtualMachine.java -- A reference implementation of a JDWP virtual
 machine

 Copyright (C) 2005, 2006 Free Software Foundation

 This file is part of GNU Classpath.

 GNU Classpath is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2, or (at your option)
 any later version.

 GNU Classpath is distributed in the hope that it will be useful, but
 WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with GNU Classpath; see the file COPYING.  If not, write to the
 Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 02110-1301 USA.

 Linking this library statically or dynamically with other modules is
 making a combined work based on this library.  Thus, the terms and
 conditions of the GNU General Public License cover the whole
 combination.

 As a special exception, the copyright holders of this library give you
 permission to link this library with independent modules to produce an
 executable, regardless of the license terms of these independent
 modules, and to copy and distribute the resulting executable under
 terms of your choice, provided that you also meet, for each linked
 terms of your choice, provided that you also meet, for each linked
 independent module, the terms and conditions of the license of that
 module.  An independent module is a module which is not derived from
 or based on this library.  If you modify this library, you may extend
 this exception to your version of the library, but you are not
 obligated to do so.  If you do not wish to do so, delete this
 exception statement from your version. */

package gnu.classpath.jdwp;

import gnu.classpath.jdwp.event.ClassPrepareEvent;
import gnu.classpath.jdwp.event.Event;
import gnu.classpath.jdwp.event.EventRequest;
import gnu.classpath.jdwp.event.ExceptionEvent;
import gnu.classpath.jdwp.event.ThreadStartEvent;
import gnu.classpath.jdwp.event.VmDeathEvent;
import gnu.classpath.jdwp.event.VmInitEvent;
import gnu.classpath.jdwp.exception.InvalidClassException;
import gnu.classpath.jdwp.exception.InvalidFrameException;
import gnu.classpath.jdwp.exception.InvalidMethodException;
import gnu.classpath.jdwp.exception.InvalidThreadException;
import gnu.classpath.jdwp.exception.JdwpException;
import gnu.classpath.jdwp.exception.NativeMethodException;
import gnu.classpath.jdwp.exception.NotImplementedException;
import gnu.classpath.jdwp.util.Location;
import gnu.classpath.jdwp.util.MethodResult;
import gnu.classpath.jdwp.util.MonitorInfo;
import gnu.classpath.jdwp.value.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;

import org.jikesrvm.VM;
import org.jikesrvm.VM_Callbacks;
import org.jikesrvm.classloader.VM_Class;
import org.jikesrvm.classloader.VM_Method;
import org.jikesrvm.classloader.VM_NormalMethod;
import org.jikesrvm.classloader.VM_Type;
import org.jikesrvm.scheduler.VM_Scheduler;
import org.jikesrvm.scheduler.VM_Thread;
import org.jikesrvm.scheduler.VM_Scheduler.FrameRecord;
import org.jikesrvm.util.VM_LinkedList;

/**
 * A virtual machine according to JDWP.
 * 
 * @author Keith Seitz <keiths@redhat.com>
 * @author Eslam AlMorshdy <e_morshdy@acm.org>
 */
public final class VMVirtualMachine {

	// VM Capabilities
	public static final boolean canWatchFieldModification = false;
	public static final boolean canWatchFieldAccess = false;
	public static final boolean canGetBytecodes = true;
	public static final boolean canGetSyntheticAttribute = false;
	public static final boolean canGetOwnedMonitorInfo = false;
	public static final boolean canGetCurrentContendedMonitor = false;
	public static final boolean canGetMonitorInfo = false;
	public static final boolean canRedefineClasses =
			gnu.java.lang.JikesRVMSupport.createInstrumentation()
					.isRedefineClassesSupported();
	public static final boolean canAddMethod = false;
	public static final boolean canUnrestrictedlyRedefineClasses = false;
	public static final boolean canPopFrames = false;
	public static final boolean canUseInstanceFilters = false;
	public static final boolean canGetSourceDebugExtension = false;
	public static final boolean canRequestVMDeathEvent = true;
	public static final boolean canSetDefaultStratum = false;
	
	
	private static boolean startedResolutionNotification = false;
	private static boolean vmInitialized = false;
	private static boolean classResolutionEventRegistered = false;
	
	private static final VM_LinkedList<VM_Class> deferredResolutionNotifications
		= new  VM_LinkedList<VM_Class>() ;
	private static final VM_LinkedList<Event> deferredEvents
		= new  VM_LinkedList<Event>() ; 

	static {
		initialize();
	}
	
	public static void initialize () {
	}
	
   /**
	 * Returns all threads that the jdwp front-end should be aware of. 
	 */
	 public static Thread[] getAllThreads() {
		 VM.sysWrite("    getAllThreads()");
		// Our JDWP thread group 
		final ThreadGroup jdwpGroup = Jdwp.getDefault().getJdwpThreadGroup();

		// Find the root ThreadGroup
		ThreadGroup group = jdwpGroup;
		ThreadGroup parent = group.getParent();
		while (parent != null) {
			group = parent;
			parent = group.getParent();
		}

		// Get all the threads in the system
		int num = group.activeCount() * 2 ;// 2 is big safety factor 
		final Thread[] threads = new Thread[num];
		num = group.enumerate(threads);// what we actually retreived
		
		// Count those that shouldn't be reported to jdwp front-end.
		final Thread[] nonSysThreads;
		int nonSysThreadsCount = 0;
		for (int i = 0 ; i < num ; i ++ ) {
			final Thread thread = threads[i];
			if (null != thread && isNonSystemThread(thread)) 
				nonSysThreadsCount++;
			 else 
				continue;
		}
		
		// Execlude those that shouldn't be reported to jdwp fron-tend.
		nonSysThreads = new Thread[nonSysThreadsCount];
		int nonSysThreadsIndex = 0;
		for (Thread thread : threads) 
			if (null != thread && isNonSystemThread(thread))
				nonSysThreads[nonSysThreadsIndex++] = thread;
		
		VM.sysWriteln("thread count", nonSysThreads.length);
		for(Thread thread:nonSysThreads)
			VM.sysWrite(thread.getName());
		return nonSysThreads;
	};
	
	/**
	 * Suspend a thread
	 * 
	 * @param thread the thread to suspend
	 * @throws JdwpException 
	 */
	@SuppressWarnings("deprecation")
	public static void suspendThread(final Thread thread) throws JdwpException {
		VM.sysWrite("suspendThread:");
		if (null == thread)
			throw new InvalidThreadException(-1);
		if (VM.VerifyAssertions) VM._assert(isNonSystemThread(thread));
		VM.sysWriteln( thread.getName());
		if (isSystemThread(thread)){
			VM.sysWriteln("suspendThread: skip..system thread ",
					      thread.getName()); 
			return;
		}
		VM.sysWrite("suspendThread: ...suspendcount = ", getSuspendCount(thread)) ;
		thread.suspend();
	}

	/**
	 * Suspend all non system threads
	 */
	public static void suspendAllThreads() throws JdwpException {
		VM.sysWriteln("    suspendAll");
		// Our JDWP thread group -- don't suspend any of those threads
		ThreadGroup jdwpGroup = Jdwp.getDefault().getJdwpThreadGroup();
		
		// current thread should always be a system thread.
		if (VM.VerifyAssertions) {	
			Thread current = Thread.currentThread();
			VM._assert(isSystemThread(current));
		}

		// Find the root ThreadGroup
		ThreadGroup group = jdwpGroup;
		ThreadGroup parent = group.getParent();
		while (parent != null) {
			group = parent;
			parent = group.getParent();
		}

		//TODO better way exist in the debuggerthread
		// Get all the threads in the system
		int num = group.activeCount() * 2 ;// 2 is big safety factor 
		final Thread[] threads = new Thread[num];
		num = group.enumerate(threads);// what we actually retreived
		
		for (int i = 0 ; i < num ; i ++ ) {
			final Thread thread = threads[i];
			if (thread != null) {
				if (isSystemThread(thread)) {
					// Don't suspend any System thread
					continue;
				} else {
					VM.sysWriteln("    suspendAll: ThreadName ",
					    thread.getName());
					suspendThread(thread);
				}
			}
		}
	}

	/**
	 * Resume a thread. A thread must be resumed as many times as it has
	 * been suspended.
	 * 
	 * @param thread the thread to resume
	 */
	@SuppressWarnings("deprecation")
	public static void resumeThread(final Thread thread) throws JdwpException {
		VM.sysWrite("resumeThread:");
		if (null == thread)
			throw new InvalidThreadException(-1);
		if (VM.VerifyAssertions) VM._assert(isNonSystemThread(thread));
		
		VM.sysWriteln( thread.getName());

		if (isSystemThread(thread)){
			VM.sysWriteln("resumeThread: skip..system thread ",
					      thread.getName()); 
			return;
		}
		final int suspendCount = getSuspendCount(thread);
		if ( suspendCount <=  0){
			VM.sysWrite("resumeThread: skip..suspend count = ", suspendCount) ;
			VM.sysWriteln(" thread-> ",	thread.getName()); 
			return;
		}
		VM.sysWriteln("...status", getThreadStatus(thread));
		thread.resume();
	}

	/**
	 * Resume all threads. This simply decrements the thread's suspend
	 * count. It can not be used to force the application to run.
	 */
	public static void resumeAllThreads() throws JdwpException {
		VM.sysWriteln("resumeAllThreads");
		
		// Our JDWP thread group -- don't resume
		final Thread current = Thread.currentThread();
		final ThreadGroup jdwpGroup = current.getThreadGroup();
		
		// current thread should always be a system thread.
		if (VM.VerifyAssertions) VM._assert(isSystemThread(current));
		

		// Find the root ThreadGroup
		ThreadGroup group = jdwpGroup;
		ThreadGroup parent = group.getParent();
		while (parent != null) {
			group = parent;
			parent = group.getParent();
		}

		int num = group.activeCount() * 2 ;// 2 is big safety factor 
		final Thread[] threads = new Thread[num];
		num = group.enumerate(threads);// what we actually retreived
		
		for (int i = 0 ; i < num ; i ++ ) {
			final Thread thread = threads[i];
			if (thread != null) {
				if (isSystemThread(thread) ) {
					// Don't resume system thread
					continue;
				} else {
					VM.sysWriteln("resumeAll: ThreadName ", thread.getName());
					resumeThread(thread);
				}
			}
		}
	}

	/**
	 * Get the suspend count for a given thread
	 * 
	 * @param thread the thread whose suspend count is desired
	 * @return the number of times the thread has been suspended
	 */
	public static int getSuspendCount(final Thread thread) 
		throws JdwpException {
		if (null == thread)
			throw new InvalidThreadException(-1);
		if (VM.VerifyAssertions) VM._assert(isNonSystemThread(thread));
		VM_Thread vmThread = java.lang.JikesRVMSupport.getThread(thread);
		return vmThread.getSuspendCount();
	};
	
	/**
	 * Returns the status of a thread
	 * 
	 * @param thread 	the thread being queried for status
	 * @return integer 	status of the thread
	 * @see JdwpConstants.ThreadStatus 
	 */
	public static int getThreadStatus(final Thread thread) throws JdwpException {
		VM.sysWrite("getThreadStatus:");
		if (null == thread)
			throw new InvalidThreadException(-1);
		if (VM.VerifyAssertions) VM._assert(isNonSystemThread(thread));
		VM.sysWrite(thread.getName());
		final VM_Thread vmThread = java.lang.JikesRVMSupport.getThread(thread);
		final int status = vmThread.getJdwpState();
		VM.sysWrite("....thread status ", status);
		VM.sysWriteln("....specifically ", vmThread.getThreadState());
		return status;
	}

	/**
	 * Returns a count of the number of loaded classes in the VM
	 */
	public static int getAllLoadedClassesCount() throws JdwpException{
		VM.sysWrite("    getAllLoadedClassesCount" );
		final int rValue = getAllLoadedClasses().size();
		VM.sysWrite("...", rValue);
		return rValue;
	};

	/**
	 * Returns an iterator over all the loaded classes in the VM
	 * execlude duplicates
	 */
	public static Collection<Class<?>> getAllLoadedClasses()
			throws JdwpException {
		VM.sysWriteln("    getAllLoadedClasses");
		final int len1 =
				java.lang.JikesRVMSupport
                          .getAllLoadedClasses().length
                          +
                          2
                          ;
		 final Class<?>[] classes = new Class<?> [len1];
		 int idx = 0;
		 for(Class<?> clazz : java.lang.JikesRVMSupport.getAllLoadedClasses())
			 classes[ idx++ ] = clazz;
		 classes[idx++] = java.lang.Error.class;
		 classes[idx++] = java.lang.Throwable.class;
		 return Arrays.asList(classes);
		 
		
//		 final Class<?>[] classes2 =  VM_TypeReference.getAllClasses();
//		 Class<?>[] rValue 
//		 	=  classes2;
//		 for ( Class<?> clazz : classes2 ) {
//			 if ( clazz.getName().contains("org.jikesrvm")
//					 ||  clazz.getName().contains("gnu.classpath")
//					 ||  clazz.getName().contains("org.vmmagic")
//					 ||  clazz.getName().contains("org.mmtk")
//					// ||  clazz.getName().contains("gnu.classpath.jdwp")
//					 ||  clazz.getName().contains("JikesRVMSupport")
//					 ||  clazz.getName().contains("VM")
//					// ||  clazz.getName().contains("gnu.classpath.debug")
//)
//				 continue;
//			 //VM.sysWriteln("wewewew", clazz.getName());
//			 rValue[ idx++ ] = clazz;
//		 }
//		 
//		 //After filtering
//		 rValue = Arrays.copyOf(rValue, idx);
//		 VM.sysWriteln("####getAllLoadedClasses...count =", rValue.length );
//		 return Arrays.asList(rValue);
	};

	/**
	 * Returns the status of the given class
	 * 
	 * @param clazz the class whose status is desired
	 * @return a flag containing the class's status
	 * @see JdwpConstants.ClassStatus
	 * TODO distinguish linkingg phases Verification,preparation,resolution
	 */
	public static int getClassStatus(final Class clazz) throws JdwpException{
		VM.sysWrite("getClassStatus:");
		if(null  == clazz)
			throw new InvalidClassException(-1);
		VM.sysWrite("...", clazz.getName());
		VM_Type vmType = java.lang.JikesRVMSupport.getTypeForClass(clazz);
		int rValue = 0;

		if(((VM_Class) vmType).isLoaded())
			rValue |= JdwpConstants.ClassStatus.VERIFIED; //not necessarily;
		
		if (vmType.isResolved())
			rValue |= JdwpConstants.ClassStatus.PREPARED;
		
		if( vmType.isInstantiated())
			rValue |= JdwpConstants.ClassStatus.INITIALIZED;
		
		rValue = (0 == rValue) ?  JdwpConstants.ClassStatus.ERROR: rValue;
		
		VM.sysWriteln("...", rValue); 
		return rValue;
	}
	
	

	/**
	 * Returns all of the methods defined in the given class. This includes
	 * all methods, constructors, and class initializers.
	 * 
	 * @param klass the class whose methods are desired
	 * @return an array of virtual machine methods
	 */
	public static VMMethod[] getAllClassMethods(final Class clazz)
			throws JdwpException{
		VM.sysWriteln("    getAllClassMethods for class : ",clazz.getName());
		VM_Type vmType = java.lang.JikesRVMSupport.getTypeForClass(clazz);
		VM_Class vmClass = (VM_Class) vmType;
		
		// Calculate length.
		int len = 0;
		//len += ( null == vmClass.getClassInitializerMethod() ) ? 0 : 1;
		//len += vmClass.getConstructorMethods().length;
		//len += vmClass.getStaticMethods().length;
		len += vmClass.getDeclaredMethods().length;
		
		VMMethod[] vmMethods = new VMMethod[len];
		
		int index = 0;
//		if ( null == vmClass.getClassInitializerMethod()) {
//			VM_Method rvmMethod =  vmClass.getClassInitializerMethod();
//			vmMethods[index++] = new VMMethod (clazz, rvmMethod.getId());
//		}
		
//		for (VM_Method rvmMethod : vmClass.getConstructorMethods()) {
//			vmMethods[index++] = new VMMethod(clazz, rvmMethod.getId());
//		}
//		
//		for (VM_Method rvmMethod : vmClass.getStaticMethods() ) {
//			vmMethods[index++] = new VMMethod(clazz, rvmMethod.getId());
//		}
		
		for (VM_Method rvmMethod : vmClass.getDeclaredMethods()) {
			vmMethods[index++] = new VMMethod(clazz, rvmMethod.getId());
		}
		// TODO Revise need vmClass.getVirtualMethods()
		return vmMethods;
	}

	/**
	 * A factory method for getting valid virtual machine methods which may be
	 * passed to/from the debugger.
	 * 
	 * @param  klass the class in which the method is defined
	 * @param  id    the ID of the desired method
	 * @return the desired internal representation of the method
	 * @throws InvalidMethodException
	 *             if the method is not defined in the class
	 * @throws JdwpException
	 *             for any other error
	 *  TODO
	 */
	public static VMMethod getClassMethod(final Class clazz, final long id)
			throws JdwpException{
		VM.sysWriteln("getClassMethod for ", clazz.getName());
		return new VMMethod(clazz, id);
	};

	/**
	 * Returns the thread's call stack
	 * 
	 * @param   thread thread for which to get call stack
	 * @param   start  index of first frame to return
	 * @param   length number of frames to return (-1 for all frames)
	 * @return a list of frames
	 * @throws 
	 */
	public static ArrayList getFrames(final Thread thread,
			final int start, final int length) throws JdwpException{
		VM.sysWriteln("getFrames");
		
		if (VM.VerifyAssertions) VM._assert(isNonSystemThread(thread));
		
		if (start < 0 || null == thread || isSystemThread(thread) || (0 >= getSuspendCount(thread)) ) {
			VM.sysWriteln("getFrames: INVALID THREAD");
			throw new InvalidThreadException(-1);
		}
			
		VM_Thread vmThread = java.lang.JikesRVMSupport.getThread(thread);
		
		if (vmThread == null || vmThread == VM_Scheduler.getCurrentThread()
				|| vmThread.isAlive())
			throw new InvalidThreadException(-1);
		
		ArrayList<VMFrame> vmFrames  = new ArrayList<VMFrame>();
        FrameRecord[] frames
        		= VM_Scheduler.dumpStackToArray(vmThread.contextRegisters
						.getInnermostFramePointer());
		int  end = (0 > length)? frames.length : length;
		//TODO Filter traps ,invisible
		for(int idx = start; idx <= end; idx++ ) {
			FrameRecord frame = frames[idx];
			Class clazz 
					= JikesRVMSupport.createClass(frame.getMethod()
							.getDeclaringClass());
			VMMethod vMethod 
					= new VMMethod(clazz, (long) frame.getMethod().getId());
			Location loc = new Location(vMethod, frame.getBci());
			//TODO calling object (this)
			vmFrames.add(new VMFrame(thread, idx, loc,frame.getInstance() ));
		}
		return vmFrames;
	}

	/**
	 * Returns the frame for a given thread with the frame ID in the buffer
	 * 
	 * @param thread the frame's thread
	 * @param bb buffer containing the frame's ID
	 * @return the desired frame
	 */
	public static VMFrame getFrame (final Thread thread, long frameID)
	  throws JdwpException{
		VM.sysWriteln("getFrame");
		ArrayList<VMFrame> frames = getFrames(thread, 0, -1);
		if (frames.size() < frameID )
		{
			VM.sysWriteln("getFrame..InvalidFrameException");
			throw new InvalidFrameException(frameID);
		}
		return frames.get((int) frameID);
	  }

	/**
	 * Returns the number of frames in the thread's stack
	 * 
	 * @param thread the thread for which to get a frame count
	 * @return the number of frames in the thread's stack
	 */
	public static int getFrameCount(final Thread thread) 
		throws JdwpException{
		VM.sysWrite("getFrameCount.." );
		int rvalue = getFrames(thread, 0, -1).size();
		VM.sysWriteln(rvalue);
		return rvalue;
	}

	/**
	 * Returns a list of all classes which this class loader has been
	 * requested to load
	 * 
	 * @param cl the class loader
	 * @return a list of all visible classes 
	 * TODO fix
	 */
	public static ArrayList getLoadRequests(final ClassLoader cl)
			throws JdwpException {
		VM.sysWriteln("    getLoadRequests");
		Class<?>[] classes = java.lang.JikesRVMSupport.getInitiatedClasses(cl);
		return new ArrayList<Class<?>>(Arrays.asList(classes)); 
	};

	  /**
	   * Executes a method in the virtual machine. The thread must already
	   * be suspended by a previous event. When the method invocation is
	   * complete, the thread (or all threads if INVOKE_SINGLE_THREADED is
	   * not set in options) must be suspended before this method returns.
	   *
	   * @param  obj      instance in which to invoke method (null for static)
	   * @param  thread   the thread in which to invoke the method
	   * @param  clazz    the class in which the method is defined
	   * @param  method   the method to invoke
	   * @param  values   arguments to pass to method
	   * @param  options  invocation options
	   * @return a result object containing the results of the invocation
	   */
	  public static MethodResult executeMethod(Object obj, Thread thread,
			Class clazz, VMMethod method, Value[] values, int options)
			throws JdwpException{
		  throw new NotImplementedException("executeMethod");
	  }

	/**
	 * "Returns the name of source file in which a reference type was declared"
	 * 
	 * @param clazz the class for which to return a source file
	 * @return a string containing the source file name; "no path information
	 *         for the file is included" 
	 *  TODO validate name format 
	 */
	public static String getSourceFile(Class clazz) throws JdwpException {
		VM.sysWrite("    getSourceFile for ", clazz.getName());
		VM_Type vmType = java.lang.JikesRVMSupport.getTypeForClass(clazz);
		VM_Class vmClass = (VM_Class) vmType;
		String rValue = vmClass.getSourceName().toString();
		VM.sysWrite(" , Source file name = ", rValue);
		return rValue;
	};

	/**
	 * Register a request from the debugger
	 * 
	 * Virtual machines have two options. Either do nothing and allow the
	 * event manager to take care of the request (useful for broadcast-type
	 * events like class prepare/load/unload, thread start/end, etc.) or do
	 * some internal work to set up the event notification (useful for
	 * execution-related events like breakpoints, single-stepping, etc.).
	 * ususallly a corrisponding notification to the vent manager is expected
	 * @throws JdwpException 
	 * 
	 */
	public static void registerEvent(final EventRequest request)
			throws JdwpException {
		VM.sysWriteln("");
		VM.sysWriteln("register Event From Debugger");
		VM.sysWriteln("Packet ID: ", request.getId());
		VM.sysWriteln("Event kind: ", request.getEventKind());
		VM.sysWriteln("Suspend Policy: ", request.getSuspendPolicy());
		
		// if(request.getSuspendPolicy() == JdwpConstants.SuspendPolicy.NONE) {
		// // Request not to suspend threads on an event, currently ignore
		// return;
		// }

		switch (request.getEventKind()) {
		case JdwpConstants.EventKind.VM_INIT: {
			VM.sysWriteln("VM_INIT requested.");
			final class StartupCallback implements VM_Callbacks.StartupMonitor {
				public void notifyStartup() {
					VM.sysWriteln("VM_INIT fired");
					// check wheter initialized or not.
					vmInitialized = true;
					if (Jdwp.isDebugging) {
						deferredEvents.add(new VmInitEvent(Thread.currentThread()));
						//Jdwp.notify(new VmInitEvent(Thread.currentThread()));
						// Now notify about deffered Events.
						notifyAboutDefferedEvents();
					}
				}
			}
			VM_Callbacks.addStartupMonitor(new StartupCallback());
			break;
		}
		case JdwpConstants.EventKind.VM_DEATH: {
			VM.sysWriteln("VM_VM_DEATH requested.");
			final class ExitCallback implements VM_Callbacks.ExitMonitor {
				public void notifyExit(int value) {
					VM.sysWriteln("VM_DEATH fired");
					if (Jdwp.isDebugging && vmInitialized) {
						Jdwp.notify(new VmDeathEvent());
					}
				}
			}
			VM_Callbacks.addExitMonitor(new ExitCallback());
			break;
		}
		
		/*
		 * it's OK to notify about any loaded classes.
		 * matching filters are applied by the packet processor. 
		 */
		case JdwpConstants.EventKind.CLASS_LOAD:
//		{
//			final class ClassLoadedCakllback implements
//											VM_Callbacks.ClassLoadedMonitor {
//				public void notifyClassLoaded(VM_Class vmClass) {
//					Class<?> clazz = JikesRVMSupport.createClass(vmClass);
//					VM.sysWriteln("CLASS_LOAD fired...class ",clazz.getName());
//					
//					if (Jdwp.isDebugging) {
//						VM.sysWriteln("    ",clazz.getName());
//						Event event 
//							= new ClassPrepareEvent(Thread.currentThread(),
//									clazz, JdwpConstants.ClassStatus.VERIFIED);
//						if (vmInitialized)
//							Jdwp.notify(event);
//						else
//							deferredEvents.add(event);
//					}
//				}
//			}
//			//TODO Add filters to the request
//			// Lorg/jikesrvm/
//			
//			VM.sysWriteln("CLASS_LOAD requested.");
//			//Notify about already loaded classes.
//			try {
//				Iterator iter = getAllLoadedClasses().iterator();
//				while (iter.hasNext()) {
//					Class<?> clazz = (Class<?>) iter.next();
//					if (Jdwp.isDebugging ) {
//						Event event 
//							= new ClassPrepareEvent(Thread.currentThread(),
//										clazz, getClassStatus(clazz));
//						VM.sysWriteln("    ", clazz.getName());
//						if ( vmInitialized )
//							Jdwp.notify(event);
//						else
//							deferredEvents.add(event);
//					}
//				}
//			} catch (JdwpException e) {
//				e.printStackTrace();
//			}
//			VM_Callbacks.addClassLoadedMonitor(new ClassLoadedCakllback());
//			break;
//		}
		
		/*
		 * it's OK to notify about any prepared classes.
		 * matching filters are applied by the packet processor. 
		 */
		case JdwpConstants.EventKind.CLASS_PREPARE: {
			/**
			 * class loading usually implies conseecuti
			 * @author emorshdy
			 *
			 */
			final class ClassResolvedCallback 
				implements VM_Callbacks.ClassResolvedMonitor {
				public void notifyClassResolved(VM_Class vmClass) {
					Class<?> clazz = JikesRVMSupport.createClass(vmClass);
					VM.sysWriteln("CLASS_PREPARE fired...class ",
								  clazz.getName());
					// deffer notifications from current thread or other till
					// current notification finishes.
					if ( startedResolutionNotification ) {
						VM.sysWriteln("notification deferred");
						deferredResolutionNotifications.add(vmClass);
						return;
					}
					if (clazz.getName().contains("org.jikesrvm")
							|| clazz.getName().contains("gnu.classpath")
							|| clazz.getName().contains("org.vmmagic")
							|| clazz.getName().contains("org.mmtk")
							// || clazz.getName().contains("gnu.classpath.jdwp")
							|| clazz.getName().contains("JikesRVMSupport")
							|| clazz.getName().contains("VM")
					// || clazz.getName().contains("gnu.classpath.debug")
					) {
						VM.sysWriteln("CLASS_PREPARE...skip");
						return;
					}
					startedResolutionNotification = true;
					if (Jdwp.isDebugging
						&& !ClassResolvedCallback.class.getName()
						.equals(clazz.getName())
						&& !ClassPrepareEvent.class.getName()
						.equals(clazz.getName())) {
						// deliberate
						Event event;
						try {
							event 
								= new ClassPrepareEvent(Thread.currentThread(),
							    clazz, getClassStatus(clazz));
						} catch (JdwpException e) {
							
							VM.sysWriteln("Error occured notifying about " +
									"class preparation");
							return;
						}
						//synchronized (deferredEvents) {
							if (vmInitialized)
								deferredResolutionNotifications.add(vmClass);
							else
								deferredEvents.add(event);
						//}
						
					}else {
						VM.sysWriteln("CLASS_PREPARE  skip");
					}
					
					// Now, notify about deffered events
					Event[] allEvents
							= new Event[deferredResolutionNotifications.size()];
					int idx = 0;
					Iterator<VM_Class> iter =
							deferredResolutionNotifications.iterator();
					while (iter.hasNext()) {
						VM_Class vmClass1 = (VM_Class) iter.next();
						clazz = JikesRVMSupport.createClass(vmClass1);
						try {
							allEvents[idx++] 
							          = new ClassPrepareEvent(Thread
												.currentThread(), clazz,
												getClassStatus(clazz));
						} catch (JdwpException e) {
							VM.sysWriteln("Error occured retreivieng class status ");
						}
					}
					
					if (vmInitialized && (0 < allEvents.length))
						Jdwp.notify(allEvents);
					else {
						//synchronized (deferredEvents) {
							for (Event event : allEvents )
								deferredEvents.add(event);
						//}
					}

					deferredResolutionNotifications.clear();
					startedResolutionNotification = false;
				}
			}
			//TODO add filters to the request
//		request.addFilter( new ClassExcludeFilter("org.jikesrvm.*"));
//		request.addFilter( new ClassExcludeFilter("org.vmmagic.*"));
//		request.addFilter( new ClassExcludeFilter("gnu.classpath.jdwp.*"));
//		request.addFilter( new ClassExcludeFilter("gnu.classpath.debug.*"));
//		request.addFilter( new ClassExcludeFilter("org.mmtk.*"));
//		request.addFilter( new ClassExcludeFilter("java.lang.JikesRVMSupport"));
//		request.addFilter( new ClassExcludeFilter("java.lang.VM*"));			
			
			new ClassPrepareEvent(null,null,0);
			
			if (classResolutionEventRegistered)
				break;
			ClassResolvedCallback resolvedCallBack 
				= new ClassResolvedCallback();
			classResolutionEventRegistered = true;
			
			//Always?
//			Notify about already loaded classes.
			Collection<Class<?>> loadedClasses = getAllLoadedClasses();
			VM.sysWrite("Adding already loaded classes to event notification",
						  loadedClasses.size());
			
			Iterator<Class<?>> iter = loadedClasses.iterator();
			while (iter.hasNext()) {
				Class<?> clazz = iter.next();
				VM_Class vmClass = (VM_Class)JikesRVMSupport.getTypeForClass(clazz);
				deferredResolutionNotifications.add(vmClass);
			}
			VM_Callbacks.addClassResolvedMonitor(resolvedCallBack);
			break;
		}

		case JdwpConstants.EventKind.FRAME_POP:
			VM.sysWriteln("FRAME_POP requested.");
			//TODO use stack unwind
			
			break;

		case JdwpConstants.EventKind.EXCEPTION:
			VM.sysWriteln("EXCEPTION requested.");
			final class ExceptionThrownCallback
					implements VM_Callbacks.ExceptionThrowMonitor{

				public void notifyExceptionthrown() {
					Class<?> clazz = JikesRVMSupport.createClass(null);
					Jdwp.notify(new ExceptionEvent((Throwable)(null),(Thread)null, (Location)null, (Location)null,(Class)null,(Object)null));
				}
			}
			break;

		case JdwpConstants.EventKind.USER_DEFINED:
			VM.sysWriteln("USER_DEFINED requested.");
			break;
		/*
		 * Only non system threads are reported. 
		 */
		case JdwpConstants.EventKind.THREAD_START:
			VM.sysWriteln("THREAD_START requested.");
			final class ThreadStartCallback 
				implements VM_Callbacks.ThreadStartMonitor {
				public void notifyThreadStart(VM_Thread vmThread) {
					Thread thread = vmThread.getJavaLangThread();
					VM.sysWriteln("THREAD_START fired: ",thread.getName());
					if (Jdwp.isDebugging
					    && isNonSystemThread(thread)) {
						Event event = new ThreadStartEvent(thread);
						//synchronized (deferredEvents) {
							if (vmInitialized)
								Jdwp.notify(event);
							else
								deferredEvents.add(event);
						//}
					} else {
						VM.sysWriteln("THREAD_START skipping");
					}
				}
			}
			//AddFilterering
			VM_Callbacks.addThreadStartedMonitor(new ThreadStartCallback());
			break;
			
		/*
		 * Only non system threads are reported. 
		 */
		case JdwpConstants.EventKind.THREAD_END:
			VM.sysWriteln("THREAD_END requested.");
			final class ThreadEndCallback 
				implements VM_Callbacks.ThreadEndMonitor {
				public void notifyThreadEnd(VM_Thread vmThread) {
					Thread thread = vmThread.getJavaLangThread();
					VM.sysWriteln("THREAD_END fired : ", thread.getName());
					if (Jdwp.isDebugging 
					    && isNonSystemThread(thread)) {
						Event event = new ThreadStartEvent(thread);
						//synchronized (deferredEvents) {
							if(vmInitialized)
								Jdwp.notify(event);
							else
								deferredEvents.add(event);
						//}
					} else {
						VM.sysWriteln("THREAD_START skipping");
					}
				}
			}
			VM_Callbacks.addThreadendMonitor(new ThreadEndCallback());
			break;
		case JdwpConstants.EventKind.CLASS_UNLOAD:
			VM.sysWriteln("CLASS_UNLOAD requested.");
			break;
		case JdwpConstants.EventKind.FIELD_ACCESS:
			VM.sysWriteln("FIELD_ACCESS requested.");
			//throw new NotImplementedException("FIELD_ACCESS");
			break;
		case JdwpConstants.EventKind.FIELD_MODIFICATION:
			VM.sysWriteln("FIELD_MODIFICATION requested.");
			//throw new NotImplementedException("FIELD_MODIFICATION");
			break;
		case JdwpConstants.EventKind.METHOD_ENTRY:
			VM.sysWriteln("METHOD_ENTRY requested.");
			//throw new NotImplementedException("METHOD_ENTRY");
			break;
		case JdwpConstants.EventKind.METHOD_EXIT:
			VM.sysWriteln("METHOD_EXIT requested.");
			//throw new NotImplementedException("METHOD_EXIT");
			break;
		default:
			VM.sysWriteln("Unhandled request to register JDWP event.");
			break;
			//throw new RuntimeException(
					//"Unhandled request to register JDWP event.");
		}
		VM.sysWriteln();
	}

	/**
	 * Unregisters the given request
	 * 
	 * @param request the request to unregister
	 */
	public static void unregisterEvent(EventRequest request)
			throws JdwpException {
		VM.sysWriteln("");
		VM.sysWriteln("Unregister Event From Debugger");
		VM.sysWriteln("Packet ID: ", request.getId());
		VM.sysWriteln("Event kind: ", request.getEventKind());
		VM.sysWriteln("Suspend Policy: ", request.getSuspendPolicy());
		switch (request.getEventKind()) {
		case JdwpConstants.EventKind.VM_INIT:
			VM.sysWriteln("unregisterEvent VM_INIT requested.");
			break;
		case JdwpConstants.EventKind.VM_DEATH:
			VM.sysWriteln("unregisterEvent VM_VM_DEATH requested.");
			break;
		case JdwpConstants.EventKind.CLASS_LOAD:
			VM.sysWriteln("unregisterEvent CLASS_LOAD requested.");
			break;
		case JdwpConstants.EventKind.CLASS_PREPARE:
			VM.sysWriteln("unregisterEvent CLASS_PREPARE requested.");
			break;
		case JdwpConstants.EventKind.FRAME_POP:
			VM.sysWriteln("unregisterEvent FRAME_POP requested.");
			break;
		case JdwpConstants.EventKind.EXCEPTION:
			VM.sysWriteln("unregisterEvent EXCEPTION requested.");
			break;
		case JdwpConstants.EventKind.USER_DEFINED:
			VM.sysWriteln("unregisterEvent USER_DEFINED requested.");
			break;
		case JdwpConstants.EventKind.THREAD_START:
			break;
		case JdwpConstants.EventKind.THREAD_END:
			break;
		case JdwpConstants.EventKind.CLASS_UNLOAD:
			VM.sysWriteln("unregisterEvent CLASS_UNLOAD requested.");
			break;
		case JdwpConstants.EventKind.FIELD_ACCESS:
			VM.sysWriteln("unregisterEvent FIELD_ACCESS requested.");
			break;
		case JdwpConstants.EventKind.FIELD_MODIFICATION:
			VM.sysWriteln("unregisterEvent FIELD_MODIFICATION requested.");
			break;
		case JdwpConstants.EventKind.METHOD_ENTRY:
			VM.sysWriteln("unregisterEvent METHOD_ENTRY requested.");
			break;
		case JdwpConstants.EventKind.METHOD_EXIT:
			VM.sysWriteln("unregisterEvent METHOD_EXIT requested.");
			break;
		default:
			VM.sysWriteln("Unhandled request to register JDWP event.");
			throw new RuntimeException(
					"Unhandled request to register JDWP event.");
		}
		VM.sysWriteln();
	};

	/**
	 * Clear all events of the given kind
	 * 
	 * @param kind the type of events to clear
	 */
	public static void clearEvents(byte kind) throws JdwpException{
		throw new RuntimeException("clearEvents");
	};
	
	 /**
	   * Redefines the given types. VM must support canRedefineClasses
	   * capability (may also require canAddMethod and/or
	   * canUnrestrictedlyRedefineClasses capabilities)
	   *
	   * @param types the classes to redefine
	   * @param bytecodes the new bytecode definitions for the classes
	   */
	  public static void redefineClasses(Class[] types, byte[][] bytecodes)
			throws JdwpException {
		  throw new RuntimeException("redefineClasses");
	  };

	  /**
	   * Sets the default stratum. VM must support the
	   * canSetDefaultStratum capability.
	   *
	   * @param stratum the new default stratum or empty string to
	   *        use the reference default
	   */
	  public static void setDefaultStratum(String stratum)
	    throws JdwpException{
			  throw new RuntimeException("setDefaultStratum");
		  };

	  /**
	   * Returns the source debug extension. VM must support the
	   * canGetSourceDebugExtension capability.
	   *
	   * @param klass the class for which to return information
	   * @returns the source debug extension
	   */
	  public static String getSourceDebugExtension(Class klass)
	    throws JdwpException{
		  throw new RuntimeException("getSourceDebugExtension");
	  }

	  /**
	   * Returns the bytecode for the given method. VM must support the
	   * canGetBytecodes capability.
	   *
	   * @param method the method for which to get bytecodes
	   * @returns the bytecodes
	   */
	  public static final byte[] getBytecodes(VMMethod method)
	    throws JdwpException{
		  VM_Method vmmethod  =  method.getVMMethod();
		  if(!( vmmethod instanceof VM_NormalMethod )) {
			  return ((VM_NormalMethod) vmmethod).getRawBytecodes();
		  }
		  throw new NativeMethodException(method.getId());
	  }

	  /**
	   * Returns monitor information about an object. VM must support
	   * the canGetMonitorInformation capability.
	   *
	   * @param obj the object
	   * @returns monitor information (owner, entry count, waiters)
	   */
	  public static MonitorInfo getMonitorInfo(Object obj)
	    throws JdwpException{
		  throw new RuntimeException("getMonitorInfo");
	  }

	  /**
	   * Returns a list of owned monitors. VM must support the
	   * canGetOwnedMonitorInfo capability.
	   *
	   * @param thread a thread
	   * @returns the list of monitors owned by this thread
	   */
	  public static Object[] getOwnedMonitors(Thread thread)
	    throws JdwpException{
		  throw new RuntimeException("getOwnedMonitors");
	  }

	  /**
	   * Returns the current contended monitor for a thread. VM must
	   * support canGetCurrentContendedMonitor capability.
	   *
	   * @param thread the thread
	   * @returns the contended monitor
	   */
	  public static Object getCurrentContendedMonitor(Thread thread)
	    throws JdwpException{
		  throw new RuntimeException("getCurrentContendedMonitor");
	  };

	  /**
	   * Pop all frames up to and including the given frame. VM must
	   * support canPopFrames capability. It is the responsibility
	   * of the VM to check if the thread is suspended. If it is not,
	   * the VM should throw ThreadNotSuspendedException.
	   *
	   * @param thread the thread
	   * @param frame the frame ID
	 * @throws JdwpException 
	   */
	  public static void popFrames(Thread thread, long frameId) 
	  	throws JdwpException {
		  if(0 != getSuspendCount(thread))
			  //throw new ThreadNotSuspendedException()
		  throw new RuntimeException("popFrames");
	  }
	  
	  /**
	   * Checks wether the given thread is rvm infrastrucure one and not an 
	   * application thread.
	   * 
	   * @param thread thread to be checked.
	   * @return true if the thread is normal non-jdwp,
	   * 		 non-debug, non-system, and non-null.
	   */
	  private static boolean isNonSystemThread(Thread thread) {
		if ( null == thread ){
			//VM.sysWriteln("isNonSystemThread   null");
			return true;
		}
		//VM.sysWrite("isNonSystemThread...for ", thread.getName());
		VM_Thread vmThread = java.lang.JikesRVMSupport.getThread(thread);
		final boolean rValue 
					= !(thread.getThreadGroup() == Jdwp.getDefault()
							.getJdwpThreadGroup()
						|| vmThread.isBootThread()	
						|| vmThread.isDebuggerThread()
						|| vmThread.isGCThread()
						|| vmThread.isSystemThread()
						|| vmThread.isIdleThread());
		//VM.sysWriteln("...", rValue);
		return rValue;
	};
	
	private static boolean isSystemThread(Thread thread) {
		return !isNonSystemThread(thread);
	}
	
	/**
	 * Notifies Event Manager about events occured before VM_INIT is fired.
	 * TODO should be a hash where buckets ar formed of packable events
	 * ONLY events of same types should be bulkly reported 
	 */
	private static void notifyAboutDefferedEvents() {
		final Event[] allEvents;
		synchronized (deferredEvents) {
			final int len = deferredEvents.size();
			VM.sysWriteln("notifyAboutDefferedEvents...count = ", len);
			allEvents = new Event[len];
		
			for (int i = 0 ; i < len ; i++){
				allEvents[i] = deferredEvents.get(i);
			}
			deferredEvents.clear();
		}
			// bulk notification
			Jdwp.notify(allEvents);
	}
}
