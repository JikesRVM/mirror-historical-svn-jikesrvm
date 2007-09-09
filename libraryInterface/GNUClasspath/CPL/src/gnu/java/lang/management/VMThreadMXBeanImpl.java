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
package gnu.java.lang.management;

import java.lang.management.ThreadInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import java.util.HashMap;
import java.util.Map;

import org.jikesrvm.VM_UnimplementedError;

import org.jikesrvm.scheduler.VM_Scheduler;
import org.jikesrvm.scheduler.VM_Thread;
import org.jikesrvm.scheduler.greenthreads.VM_GreenThread;

/**
 * Implementation of the threading bean for JikesRVM.
 *
 * @author Andrew John Hughes (gnu_andrew@member.fsf.org)
 */
final class VMThreadMXBeanImpl {

  /**
   * Map of thread ids to VM_Thread instances.
   */
  private static Map<Long,VM_Thread> idMap;

  /**
   * Returns the ids of deadlocked threads occurring due
   * to either monitor or ownable synchronizer ownership.
   * Only called if ownable synchronizer monitoring is
   * supported.
   *
   * @return an array of thread identifiers.
   */
  static long[] findDeadlockedThreads() {
    throw new VM_UnimplementedError();
  }

  /**
   * Returns the ids of deadlocked threads occurring due
   * to monitor ownership.
   *
   * @return an array of thread identifiers.
   */
  static long[] findMonitorDeadlockedThreads() {
    throw new VM_UnimplementedError();
  }

  /**
   * Returns the identifiers of all threads.  This
   * method also serves a dual purpose of updating
   * the id map.
   *
   * @return an array of thread identifiers.
   */
  static long[] getAllThreadIds() {
    idMap = new HashMap<Long,VM_Thread>();
    for (int a = VM_Scheduler.PRIMORDIAL_THREAD_INDEX;
	 a < VM_Scheduler.threads.length; ++a)    
      {
	VM_Thread thread = VM_Scheduler.threads[a];
	if (thread != null)
	  idMap.put(thread.getJavaLangThread().getId(), thread);
      }
    long[] lids = new long[idMap.size()];
    int a = 0;
    for (Long id : idMap.keySet())
      lids[a++] = id;
    System.out.println(java.util.Arrays.toString(lids));
    return lids;
  }

  /**
   * Returns the number of nanoseconds of CPU time
   * the current thread has used, if supported.
   *
   * @return the number of nanoseconds.
   */
  static long getCurrentThreadCpuTime() {
    return getThreadCpuTime(VM_Scheduler.getCurrentThread().getJavaLangThread().getId());
  }

  /**
   * Returns the number of nanoseconds of user time
   * the current thread has used, if supported.
   *
   * @return the number of nanoseconds.
   */
  static long getCurrentThreadUserTime() {
    return getThreadUserTime(VM_Scheduler.getCurrentThread().getJavaLangThread().getId());
  }

  /**
   * Returns the number of live daemon threads.
   *
   * @return the number of live daemon threads.
   */
  static int getDaemonThreadCount() {
    return VM_Scheduler.getNumDaemons();
  }

  /**
   * Fills out the information on ownable synchronizers
   * in the given {@link java.lang.management.ThreadInfo}
   * object if supported.
   *
   * @param info the object to fill in.
   */
  static void getLockInfo(ThreadInfo info) {
    throw new VM_UnimplementedError();
  }

  /**
   * Fills out the information on monitor usage
   * in the given {@link java.lang.management.ThreadInfo}
   * object if supported.
   *
   * @param info the object to fill in.
   */
  static void getMonitorInfo(ThreadInfo info) {
    throw new VM_UnimplementedError();
  }

  /**
   * Returns the current peak number of live threads.
   *
   * @return the current peak.
   */
  static int getPeakThreadCount() {
    throw new VM_UnimplementedError();
  }

  /**
   * Returns the current number of live threads.
   *
   * @return the current number of live threads.
   */
  static int getThreadCount() {
    return VM_Scheduler.getNumActiveThreads();
  }

  /**
   * Returns the number of nanoseconds of CPU time
   * the given thread has used, if supported.
   *
   * @param id the id of the thread to probe.
   * @return the number of nanoseconds.
   */
  static long getThreadCpuTime(long id) {
    throw new VM_UnimplementedError();
  }

  /**
   * Returns a {@link java.lang.management.ThreadInfo}
   * object for the given thread id with a stack trace to
   * the given depth (0 for empty, Integer.MAX_VALUE for
   * full).
   *
   * @param id the id of the thread whose info should be returned.
   * @param maxDepth the depth of the stack trace.
   * @return a {@link java.lang.management.ThreadInfo} instance.
   */
  static ThreadInfo getThreadInfoForId(long id, int maxDepth) {
    VM_Thread thread = getThreadForId(id);
    Constructor<ThreadInfo> cons = null;
    try {
      cons = ThreadInfo.class.getDeclaredConstructor(Long.TYPE, String.class,
						     Thread.State.class, Long.TYPE,
						     Long.TYPE, String.class,
						     Long.TYPE, String.class,
						     Long.TYPE, Long.TYPE,
						     Boolean.TYPE,Boolean.TYPE,
						     StackTraceElement[].class);
      cons.setAccessible(true);
      return cons.newInstance(id, thread.getName(), thread.getState(),
			      thread.getBlockedCount(), thread.getBlockedTime(),
			      null, -1, null, thread.getWaitingCount(),
			      thread.getWaitingTime(),
			      (thread instanceof VM_GreenThread ?
			       ((VM_GreenThread) thread).isInNative() : false),
			      thread.isSuspended(), null);			       
    }
    catch (NoSuchMethodException e) {
      throw (Error) new InternalError("Couldn't get ThreadInfo constructor").initCause(e);
    }
    catch (InstantiationException e) {
      throw (Error) new InternalError("Couldn't create ThreadInfo").initCause(e);
    }
    catch (IllegalAccessException e) {
      throw (Error) new InternalError("Couldn't access ThreadInfo").initCause(e);
    }
    catch (InvocationTargetException e) {
      throw (Error) new InternalError("ThreadInfo's constructor threw an exception").initCause(e);
    }
  }
    
  /**
   * Returns the number of nanoseconds of user time
   * the given thread has used, if supported.
   *
   * @param id the id of the thread to probe.
   * @return the number of nanoseconds.
   */
  static long getThreadUserTime(long id) {
    throw new VM_UnimplementedError();
  }

  /**
   * Returns the number of threads started.
   *
   * @return the number of started threads.
   */
  static long getTotalStartedThreadCount() {
    throw new VM_UnimplementedError();
  }

  /**
   * Resets the peak thread count.
   */
  static void resetPeakThreadCount() {
    throw new VM_UnimplementedError();
  }

  /**
   * Returns the VM_Thread instance for the given
   * thread id.
   *
   * @param id the id of the thread to find.
   * @return the VM_Thread.
   */
  private static VM_Thread getThreadForId(long id) {
    getAllThreadIds(); // Update the map
    VM_Thread thread = idMap.get(id);
    if (thread == null)
      throw new IllegalArgumentException("Invalid id: " + id);
    return thread;
  }

}
