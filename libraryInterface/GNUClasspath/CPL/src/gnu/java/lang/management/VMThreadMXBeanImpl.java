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

import org.jikesrvm.VM_UnimplementedError;

import org.jikesrvm.scheduler.VM_Scheduler;

/**
 * Implementation of the threading bean for JikesRVM.
 *
 * @author Andrew John Hughes (gnu_andrew@member.fsf.org)
 */
final class VMThreadMXBeanImpl {

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
   * Returns the identifiers of all threads.
   *
   * @return an array of thread identifiers.
   */
  static long[] getAllThreadIds() {
    int num = VM_Scheduler.threads.length;
    long[] ids = new long[num];
    for (int a = 0; a < num; ++a) 
      ids[a] = VM_Scheduler.threads[a].getJavaLangThread().getId();
    return ids;
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
    throw new VM_UnimplementedError();
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

}
