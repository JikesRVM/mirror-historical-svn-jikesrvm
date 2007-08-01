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

import gnu.classpath.jdwp.event.Event;
import gnu.classpath.jdwp.event.EventRequest;
import gnu.classpath.jdwp.event.ClassPrepareEvent;
import gnu.classpath.jdwp.event.ThreadStartEvent;
import gnu.classpath.jdwp.event.VmDeathEvent;
import gnu.classpath.jdwp.event.VmInitEvent;
import gnu.classpath.jdwp.exception.InvalidMethodException;
import gnu.classpath.jdwp.exception.JdwpException;
import gnu.classpath.jdwp.util.MethodResult;
import gnu.classpath.jdwp.util.MonitorInfo;
import gnu.classpath.jdwp.value.Value;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import org.jikesrvm.VM;
import org.jikesrvm.VM_Callbacks;
import org.jikesrvm.classloader.VM_Class;
import org.jikesrvm.classloader.VM_Method;
import org.jikesrvm.classloader.VM_NormalMethod;
import org.jikesrvm.classloader.VM_Type;
import org.jikesrvm.scheduler.VM_Thread;
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
	public static final boolean canRedefineClasses = false;
	public static final boolean canAddMethod = false;
	public static final boolean canUnrestrictedlyRedefineClasses = false;
	public static final boolean canPopFrames = false;
	public static final boolean canUseInstanceFilters = false;
	public static final boolean canGetSourceDebugExtension = false;
	public static final boolean canRequestVMDeathEvent = true;
	public static final boolean canSetDefaultStratum = false;
	
	private static boolean startedResolutionNotification = false;
	private static VM_LinkedList<VM_Class> deferredResolutionNotifications
		= new  VM_LinkedList<VM_Class>() ; 
	private static boolean vmInitialized = false;
	private static VM_LinkedList<Event> deferredEvents
		= new  VM_LinkedList<Event>() ; 
	private static boolean  classResolutionEventRegistered = false;
	
	//private static Thread mainThread;

	//public static final void setMainThread(Thread mainThreadParam) {
		//mainThread = mainThreadParam;
	//}
	
	static {
		initialize();
	}
	
	public static final   void initialize () {
		//registerEvent(new EventRequest(0,EventRequest.EVENT_CLASS_PREPARE,0))
	}
	
   /**
	 * Returns all threads that the jdwp backend should be aware of. 
	 */
	 public static final Thread[] getAllThreads() {
		 VM.sysWriteln("    getAllThreads()");
		// Our JDWP thread group 
		ThreadGroup jdwpGroup = Jdwp.getDefault().getJdwpThreadGroup();

		// Find the root ThreadGroup
		ThreadGroup group = jdwpGroup;
		ThreadGroup parent = group.getParent();
		while (parent != null) {
			group = parent;
			parent = group.getParent();
		}

		// Get all the threads in the system
		int num = group.activeCount();
		Thread[] threads = new Thread[num];
		group.enumerate(threads);
		
		// Count those that shouldn't be reported to jdwp backend.
		Thread[] nonSysThreads;
		int nonSysThreadsCount = 0;
		for (Thread thread : threads) {
			if (isNonSystemThread(thread)) 
				nonSysThreadsCount++;
			 else 
				continue;
		}
		
		// Execlude those that shouldn't be reported to jdwp backend.
		nonSysThreads = new Thread[nonSysThreadsCount];
		int nonSysThreadsIndex = 0;
		for (Thread thread : threads) 
			if (isNonSystemThread(thread))
				nonSysThreads[nonSysThreadsIndex++] = thread;
		return nonSysThreads;
	};
	
	/**
	 * Suspend a thread
	 * 
	 * @param thread the thread to suspend
	 * TODO check need to skip other system threads 
	 */
	public static final void suspendThread(Thread thread) {
		VM.sysWriteln("suspendThread: ThreadName ", thread.getName());
		if (VM.VerifyAssertions) 
			VM._assert(isNonSystemThread(thread));
		if (isNonSystemThread(thread)) {
			VM.sysWriteln("suspendThread: skip..system thread",
						  thread.getName()); 
			return;
		}
		thread.suspend();
	}

	/**
	 * Suspend all non system threads
	 */
	public static final void suspendAllThreads() throws JdwpException {
		VM.sysWriteln("    suspendAll");
		// Our JDWP thread group -- don't suspend any of those threads
		Thread current = Thread.currentThread();
		ThreadGroup jdwpGroup = Jdwp.getDefault().getJdwpThreadGroup();

		// Find the root ThreadGroup
		ThreadGroup group = jdwpGroup;
		ThreadGroup parent = group.getParent();
		while (parent != null) {
			group = parent;
			parent = group.getParent();
		}

		// Get all the threads in the system
		int num = group.activeCount();
		Thread[] threads = new Thread[num];
		group.enumerate(threads);

		for (Thread thread : threads ){
			if (thread != null) {
				if (thread == current || !isNonSystemThread(thread)) {
					// Don't suspend the current thread or any System thread
					continue;
				} else {
					VM.sysWriteln("    suspendAll: ThreadName ",
							      thread.getName());
					suspendThread(thread);
				}
			}
		}
		// Now suspend the current thread
		if (isNonSystemThread(current))
			suspendThread(current);
	}

	/**
	 * Resume a thread. A thread must be resumed as many times as it has
	 * been suspended.
	 * 
	 * @param thread the thread to resume
	 */
	public static final void resumeThread(Thread thread) {
		VM.sysWriteln("resumeThread: ThreadName ", thread.getName());
		if (VM.VerifyAssertions) 
			VM._assert(isNonSystemThread(thread));
		if (isNonSystemThread(thread)) {
			VM.sysWriteln("resumeThread: skip..system thread ",
						  thread.getName()); 
			return;
		}
		thread.resume();
	}

	/**
	 * Resume all threads. This simply decrements the thread's suspend
	 * count. It can not be used to force the application to run.
	 */
	public static final void resumeAllThreads() throws JdwpException {
		VM.sysWriteln("resumeAllThreads");
		// Our JDWP thread group -- don't resume
		Thread current = Thread.currentThread();
		ThreadGroup jdwpGroup = current.getThreadGroup();

		// Find the root ThreadGroup
		ThreadGroup group = jdwpGroup;
		ThreadGroup parent = group.getParent();
		while (parent != null) {
			group = parent;
			parent = group.getParent();
		}

		// Get all the threads in the system
		int num = group.activeCount();
		Thread[] threads = new Thread[num];
		group.enumerate(threads);

		for (Thread thread : threads) {
			if (thread != null) {
				if (thread == current || !isNonSystemThread(thread)) {
					// Don't resume the current thread or any system thread
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
	public static final int getSuspendCount(Thread thread) throws JdwpException {
		VM.sysWriteln("    getSuspendCount for ", thread.getName());
		if (VM.VerifyAssertions)
			VM._assert(isNonSystemThread(thread));
		VM_Thread vmThread = java.lang.JikesRVMSupport.getThread(thread);
		return vmThread.getSuspendCount();
	};
	
	/**
	 * Returns the status of a thread
	 * 
	 * @param thread
	 *            the thread for which to get status
	 * @return integer status of the thread
	 * @see JdwpConstants.ThreadStatus TODO:fix per new thread model and TODO:
	 *      more accurate state monitor waiting sleeping
	 */
	public static final int getThreadStatus(Thread thread) throws JdwpException {
		VM.sysWriteln("    getThreadStatus for ", thread.getName());
		if (VM.VerifyAssertions)
			VM._assert(isNonSystemThread(thread));
		int status = JdwpConstants.ThreadStatus.RUNNING;
		
		VM_Thread vmThread = java.lang.JikesRVMSupport.getThread(thread);
		if (!vmThread.isAlive())
			status = JdwpConstants.ThreadStatus.ZOMBIE;
			//So it's Alive
		else if ( !thread.isInterrupted() )
			status = JdwpConstants.ThreadStatus.RUNNING;
			//So it's Alive and interrupted
		
		if ( vmThread.isWaitingForOsr )
			status = JdwpConstants.ThreadStatus.WAIT;
		
		return status;
	}

	/**
	 * Returns a count of the number of loaded classes in the VM
	 */
	public static final int getAllLoadedClassesCount() throws JdwpException{
		VM.sysWriteln("    getAllLoadedClassesCount" );
		return java.lang.JikesRVMSupport.getAllLoadedClasses().length;
	};

	/**
	 * Returns an iterator over all the loaded classes in the VM
	 */
	public static final Collection getAllLoadedClasses() throws JdwpException{
		 VM.sysWriteln("    getAllLoadedClasses" );
		 Class<?>[] classes = java.lang.JikesRVMSupport.getAllLoadedClasses();
		 return Arrays.asList(classes);
	};

	/**
	 * Returns the status of the given class
	 * 
	 * @param clazz the class whose status is desired
	 * @return a flag containing the class's status
	 * @see JdwpConstants.ClassStatus
	 * TODO distinguish linkingg phases Verification,preparation,resolution
	 */
	public static final int getClassStatus(Class clazz) throws JdwpException{
		VM.sysWriteln("    getClassStatus for class : ",clazz.getName());
		VM_Type vmType = java.lang.JikesRVMSupport.getTypeForClass(clazz);
		
		if(((VM_Class) vmType).isLoaded())
			return JdwpConstants.ClassStatus.VERIFIED; //not necessarily;
		
		if (vmType.isResolved())
			return JdwpConstants.ClassStatus.PREPARED;
		
		if( vmType.isInstantiated())
			return JdwpConstants.ClassStatus.INITIALIZED;
			
		return JdwpConstants.ClassStatus.ERROR;
	};

	/**
	 * Returns all of the methods defined in the given class. This includes
	 * all methods, constructors, and class initializers.
	 * 
	 * @param klass the class whose methods are desired
	 * @return an array of virtual machine methods
	 */
	public static final VMMethod[] getAllClassMethods(Class clazz)
			throws JdwpException{
		VM.sysWriteln("    getAllClassMethods for class : ",clazz.getName());
		VM_Type vmType = java.lang.JikesRVMSupport.getTypeForClass(clazz);
		VM_Class vmClass = (VM_Class) vmType;
		
		// Calculate length.
		int len = 0;
		len += ( null == vmClass.getClassInitializerMethod() ) ? 0 : 1;
		len += vmClass.getConstructorMethods().length;
		len += vmClass.getStaticMethods().length;
		len += vmClass.getDeclaredMethods().length;
		
		VMMethod[] vmMethods = new VMMethod[len];
		
		int index = 0;
		if ( null == vmClass.getClassInitializerMethod()) {
			VM_Method rvmMethod =  vmClass.getClassInitializerMethod();
			vmMethods[index++] = new VMMethod (clazz, rvmMethod.getId());
		}
		
		for (VM_Method rvmMethod : vmClass.getConstructorMethods()) {
			vmMethods[index++] = new VMMethod(clazz, rvmMethod.getId());
		}
		
		for (VM_Method rvmMethod : vmClass.getStaticMethods() ) {
			vmMethods[index++] = new VMMethod(clazz, rvmMethod.getId());
		}
		
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
	public static final VMMethod getClassMethod(final Class clazz, final long id)
			throws JdwpException{
		VM.sysWriteln("getClassMethod for ", clazz.getName());
		VM_Type vmType = java.lang.JikesRVMSupport.getTypeForClass(clazz);
		VM_Class vmClass = (VM_Class) vmType;
		if (null == vmClass.getMethodRef((int) id))
			throw new InvalidMethodException(id);
		return new VMMethod(clazz, id);
	};

	/**
	 * Returns the thread's call stack
	 * 
	 * @param   thread thread for which to get call stack
	 * @param   start  index of first frame to return
	 * @param   length number of frames to return (-1 for all frames)
	 * @return a list of frames
	 */
	public static native ArrayList getFrames(final Thread thread, final int start,
			final int length) throws JdwpException;

	/**
	 * Returns the frame for a given thread with the frame ID in
	 * the buffer
	 *
	 * TODO I don't like this.
	 *
	 * @param  thread  the frame's thread
	 * @param  bb buffer containing the frame's ID
	 * @return the desired frame
	 */
	public static native VMFrame getFrame (Thread thread, long frameID)
	  throws JdwpException;

	/**
	 * Returns the number of frames in the thread's stack
	 * 
	 * @param thread the thread for which to get a frame count
	 * @return the number of frames in the thread's stack
	 */
	public static native int getFrameCount(Thread thread) throws JdwpException;



	/**
	 * Returns a list of all classes which this class loader has been
	 * requested to load
	 * 
	 * @param cl the class loader
	 * @return a list of all visible classes 
	 * TODO fix
	 */
	public static final ArrayList getLoadRequests(ClassLoader cl)
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
	  public static native MethodResult executeMethod(Object obj, Thread thread,
			Class clazz, VMMethod method, Value[] values, int options)
			throws JdwpException;

	/**
	 * "Returns the name of source file in which a reference type was declared"
	 * 
	 * @param clazz the class for which to return a source file
	 * @return a string containing the source file name; "no path information
	 *         for the file is included" TODO validate name format TODO suspect
	 *         probles during boot bec of toString
	 */
	public static final String getSourceFile(Class clazz) throws JdwpException {
		VM.sysWriteln("    getSourceFile for ", clazz.getName());
		VM_Type vmType = java.lang.JikesRVMSupport.getTypeForClass(clazz);
		VM_Class vmClass = (VM_Class) vmType;
		return vmClass.getSourceName().toString();
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
	public static final void registerEvent(final EventRequest request) throws JdwpException {
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
			class StartupCallback implements VM_Callbacks.StartupMonitor {
				public void notifyStartup() {
					VM.sysWriteln("VM_INIT fired");
					// check wheter initialized or not.
					if (Jdwp.isDebugging) {
						vmInitialized = true;
						Jdwp.notify(new VmInitEvent(Thread.currentThread()));
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
			class ExitCallback implements VM_Callbacks.ExitMonitor {
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
		case JdwpConstants.EventKind.CLASS_LOAD:{
			class LoadedCallback implements VM_Callbacks.ClassLoadedMonitor {
				public void notifyClassLoaded(VM_Class vmClass) {
					Class<?> clazz = JikesRVMSupport.createClass(vmClass);
					VM.sysWriteln("CLASS_LOAD fired...class ",clazz.getName());
					
					if (Jdwp.isDebugging) {
						VM.sysWriteln("    ",clazz.getName());
						Event event 
							= new ClassPrepareEvent(Thread.currentThread(),
									clazz, JdwpConstants.ClassStatus.VERIFIED);
						if (vmInitialized)
							Jdwp.notify(event);
						else
							deferredEvents.add(event);
					}
				}
			}
			//TODO Add filters to the request
			// Lorg/jikesrvm/
			
			VM.sysWriteln("CLASS_LOAD requested.");
			//Notify about already loaded classes.
			try {
				Iterator iter = getAllLoadedClasses().iterator();
				while (iter.hasNext()) {
					Class<?> clazz = (Class<?>) iter.next();
					if (Jdwp.isDebugging ) {
						Event event 
							= new ClassPrepareEvent(Thread.currentThread(),
										clazz, getClassStatus(clazz));
						VM.sysWriteln("    ", clazz.getName());
						if ( vmInitialized )
							Jdwp.notify(event);
						else
							deferredEvents.add(event);
					}
				}
			} catch (JdwpException e) {
				e.printStackTrace();
			}
			VM_Callbacks.addClassLoadedMonitor(new LoadedCallback());
			break;
		}
		
		/*
		 * it's OK to notify about any prepared classes.
		 * matching filters are applied by the packet processor. 
		 */
		case JdwpConstants.EventKind.CLASS_PREPARE: {
			final class ResolvedCallback implements VM_Callbacks.ClassResolvedMonitor {
				public void notifyClassResolved(VM_Class vmClass) {
					Class<?> clazz = JikesRVMSupport.createClass(vmClass);
					VM.sysWriteln("CLASS_PREPARE fired...class ",
								  clazz.getName());
//					deffer notifications from current thread or other till
					// current notification finishes.
					if ( startedResolutionNotification ) {
						VM.sysWriteln("notification deferred");
						deferredResolutionNotifications.add(vmClass);
						return;
					}
					startedResolutionNotification = true;
					if (Jdwp.isDebugging
						&& !ResolvedCallback.class.getName().equals(clazz.getName())
						&& !ClassPrepareEvent.class.getName().equals(clazz.getName())) {
						Event event 
							= new ClassPrepareEvent(Thread.currentThread(),
										clazz,
										JdwpConstants.ClassStatus.PREPARED);
						if (vmInitialized)
							Jdwp.notify( event );
						else
							deferredEvents.add(event);
					}else {
						VM.sysWriteln("CLASS_PREPARE  skip");
					}
					
					// Now, notify deffered notifications.
					Iterator<VM_Class> iter =
							deferredResolutionNotifications.iterator();
					while (iter.hasNext()) {
						VM_Class vmClass1 = (VM_Class) iter.next();
						clazz = JikesRVMSupport.createClass(vmClass1);
						if (Jdwp.isDebugging
								&& !ResolvedCallback.class.getName().equals(
										clazz.getName())
								&& !ClassPrepareEvent.class.getName().equals(
										clazz.getName())) {
							Event event
								= new ClassPrepareEvent(Thread
											.currentThread(), clazz,
											JdwpConstants.ClassStatus.PREPARED);
							if (vmInitialized)
								Jdwp.notify(event);
							else
								deferredEvents.add(event);

						}
					}
					deferredResolutionNotifications.clear();
					startedResolutionNotification = false;
				}
			}
			//TODO add filters to the request
			
			if (classResolutionEventRegistered)
				break;
			ResolvedCallback resolvedCallBack = new ResolvedCallback();
			VM_Callbacks.addClassResolvedMonitor(resolvedCallBack);
			
			classResolutionEventRegistered = true;
			
//			Notify about already loaded classes.
			VM.sysWriteln("Iterating over already loaded classes ..count ",
						  getAllLoadedClassesCount());
			Iterator iter = getAllLoadedClasses().iterator();
			while ( iter.hasNext() ) {
				Class<?> clazz = (Class<?>) iter.next();
				VM_Class vmClass 
					= (VM_Class) JikesRVMSupport.getTypeForClass(clazz);
				resolvedCallBack.notifyClassResolved(vmClass);				
			}
			break;
		}

		case JdwpConstants.EventKind.FRAME_POP:
			VM.sysWriteln("FRAME_POP requested.");
			break;

		case JdwpConstants.EventKind.EXCEPTION:
			VM.sysWriteln("EXCEPTION requested.");
			break;

		case JdwpConstants.EventKind.USER_DEFINED:
			VM.sysWriteln("USER_DEFINED requested.");
			break;
		/*
		 * Only non system threads are reported. 
		 */
		case JdwpConstants.EventKind.THREAD_START:
			VM.sysWriteln("THREAD_START requested.");
			class ThreadStartMonitor implements VM_Callbacks.ThreadStartMonitor {
				public void notifyThreadStart(VM_Thread vmThread) {
					Thread thread = vmThread.getJavaLangThread();
					VM.sysWriteln("THREAD_START fired: ",thread.getName());
					if (Jdwp.isDebugging
					    && isNonSystemThread(thread)) {
						Event event = new ThreadStartEvent(thread);
						if (vmInitialized)
							Jdwp.notify(event);
						else
							deferredEvents.add(event);
					} else {
						VM.sysWriteln("THREAD_START skipping");
					}
				}
			}
			VM_Callbacks.addThreadStartedMonitor(new ThreadStartMonitor());
			break;
			
		/*
		 * Only non system threads are reported. 
		 */
		case JdwpConstants.EventKind.THREAD_END:
			VM.sysWriteln("THREAD_END requested.");
			class ThreadEndMonitor implements VM_Callbacks.ThreadEndMonitor {
				public void notifyThreadEnd(VM_Thread vmThread) {
					Thread thread = vmThread.getJavaLangThread();
					VM.sysWriteln("THREAD_END fired : ", thread.getName());
					if (Jdwp.isDebugging 
					    && isNonSystemThread(thread)) {
						Event event = new ThreadStartEvent(thread);
						if(vmInitialized)
							Jdwp.notify(event);
						else
							deferredEvents.add(event);
					} else {
						VM.sysWriteln("THREAD_START skipping");
					}
				}
			}
			VM_Callbacks.addThreadendMonitor(new ThreadEndMonitor());
			break;
		case JdwpConstants.EventKind.CLASS_UNLOAD:
			VM.sysWriteln("CLASS_UNLOAD requested.");
			break;
		case JdwpConstants.EventKind.FIELD_ACCESS:
			VM.sysWriteln("FIELD_ACCESS requested.");
			break;
		case JdwpConstants.EventKind.FIELD_MODIFICATION:
			VM.sysWriteln("FIELD_MODIFICATION requested.");
			break;
		case JdwpConstants.EventKind.METHOD_ENTRY:
			VM.sysWriteln("METHOD_ENTRY requested.");
			break;
		case JdwpConstants.EventKind.METHOD_EXIT:
			VM.sysWriteln("METHOD_EXIT requested.");
			break;
		default:
			VM.sysWriteln("Unhandled request to register JDWP event.");
			throw new RuntimeException(
					"Unhandled request to register JDWP event.");
		}
		VM.sysWriteln();
	}

	/**
	 * Unregisters the given request
	 * 
	 * @param request
	 *            the request to unregister
	 */
	public static native void unregisterEvent(EventRequest request)
			throws JdwpException;

	/**
	 * Clear all events of the given kind
	 * 
	 * @param kind  the type of events to clear
	 */
	public static native void clearEvents(byte kind) throws JdwpException;
	 /**
	   * Redefines the given types. VM must support canRedefineClasses
	   * capability (may also require canAddMethod and/or
	   * canUnrestrictedlyRedefineClasses capabilities)
	   *
	   * @param types the classes to redefine
	   * @param bytecodes the new bytecode definitions for the classes
	   */
	  public static native void redefineClasses(Class[] types, byte[][] bytecodes)
	    throws JdwpException;

	  /**
	   * Sets the default stratum. VM must support the
	   * canSetDefaultStratum capability.
	   *
	   * @param stratum the new default stratum or empty string to
	   *        use the reference default
	   */
	  public static native void setDefaultStratum(String stratum)
	    throws JdwpException;

	  /**
	   * Returns the source debug extension. VM must support the
	   * canGetSourceDebugExtension capability.
	   *
	   * @param klass the class for which to return information
	   * @returns the source debug extension
	   */
	  public static native String getSourceDebugExtension(Class klass)
	    throws JdwpException;

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
		  if( vmmethod instanceof VM_NormalMethod ) {
			  return ((VM_NormalMethod) vmmethod).getRawBytecodes();
		  }
		  else return new byte[0];
	  }

	  /**
	   * Returns monitor information about an object. VM must support
	   * the canGetMonitorInformation capability.
	   *
	   * @param obj the object
	   * @returns monitor information (owner, entry count, waiters)
	   */
	  public static native MonitorInfo getMonitorInfo(Object obj)
	    throws JdwpException;

	  /**
	   * Returns a list of owned monitors. VM must support the
	   * canGetOwnedMonitorInfo capability.
	   *
	   * @param thread a thread
	   * @returns the list of monitors owned by this thread
	   */
	  public static native Object[] getOwnedMonitors(Thread thread)
	    throws JdwpException;

	  /**
	   * Returns the current contended monitor for a thread. VM must
	   * support canGetCurrentContendedMonitor capability.
	   *
	   * @param thread the thread
	   * @returns the contended monitor
	   */
	  public static native Object getCurrentContendedMonitor(Thread thread)
	    throws JdwpException;

	  /**
	   * Pop all frames up to and including the given frame. VM must
	   * support canPopFrames capability. It is the responsibility
	   * of the VM to check if the thread is suspended. If it is not,
	   * the VM should throw ThreadNotSuspendedException.
	   *
	   * @param thread the thread
	   * @param frame the frame ID
	   */
	  public static native void popFrames(Thread thread, long frameId);
	  
	  /**
	   * Checks wheter the given thread is a rvm infrastrucure thread 
	   * not an application thread.
	   * @param thread thread to be checked.
	   * @return true if the thread is normal non-jdwp,
	   * 		 non-debug, and non-system.
	   */
	  private static final boolean isNonSystemThread(Thread thread) {
		if ( null == thread ){
			VM.sysWriteln("isNonSystemThread   null");
			return false;
		}
		VM.sysWrite("isNonSystemThread...for ", thread.getName());
		VM_Thread vmThread = java.lang.JikesRVMSupport.getThread(thread);
		boolean rValue = !(thread.getThreadGroup() == Jdwp.getDefault()
				.getJdwpThreadGroup()
				|| vmThread.isBootThread() || vmThread.isDebuggerThread()
				|| vmThread.isGCThread() || vmThread.isSystemThread());
		VM.sysWriteln("...", rValue);
		return rValue;
	};
	
	/**
	 * Notifies Event Manager about events occured before VM_INIT is fired.
	 */
	private static final void notifyAboutDefferedEvents() {
		final Iterator<Event> iter = deferredEvents.iterator();
		while (iter.hasNext()) {
			final Event element = (Event) iter.next();
			Jdwp.notify(element);
		}
	}
}
