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

#include "sys.h"
#include <stdlib.h>

#ifdef RVM_FOR_HARMONY
#include <apr_thread_proc.h>
#else
#include <errno.h>
#include <pthread.h>
#include <setjmp.h>
#include <string.h>
#include <sys/sysinfo.h>
#include <sys/ucontext.h>
#endif

#ifdef _AIX
#include <sys/systemcfg.h>
#endif

#ifdef RVM_FOR_HARMONY
/** keys for managing thread termination */
static hythread_tls_key_t TerminateJmpBufKey;
static hythread_tls_key_t VmThreadKey;
static hythread_tls_key_t IsVmThreadKey;
#else
/** keys for managing thread termination */
static pthread_key_t TerminateJmpBufKey;
static pthread_key_t VmThreadKey;
static pthread_key_t IsVmThreadKey;
static pthread_mutex_t DeathLock = PTHREAD_MUTEX_INITIALIZER;
#endif // RVM_FOR_HARMONY

static bool systemExiting = false;

static void* sysNativeThreadStartup(void *args);

/** Exit with a return code. */
EXTERNAL void sysExit(int value)
{
  SYS_START();
  TRACE_PRINTF(SysErrorFile, "%s: sysExit %d\n", Me, value);
  // alignment checking: report info before exiting, then turn off checking
#ifdef RVM_WITH_ALIGNMENT_CHECKING
  if (numEnableAlignCheckingCalls > 0) {
    sysReportAlignmentChecking();
    sysDisableAlignmentChecking();
  }
#endif // RVM_WITH_ALIGNMENT_CHECKING

#ifndef RVM_FOR_HARMONY
  fflush(SysErrorFile);
  fflush(SysTraceFile);
  fflush(stdout);
#endif
  systemExiting = true;
#ifndef RVM_FOR_HARMONY
  pthread_mutex_lock(&DeathLock);
  exit(value);
#else
  hyexit_shutdown_and_exit(value);
#endif
}

/**
 * How many physical cpu's are present and actually online?
 * Assume 1 if no other good ansewr.
 * @return number of cpu's
 *
 * Note: this function is only called once.  If it were called more often
 * than that, we would want to use a static variable to indicate that we'd
 * already printed the WARNING messages and were not about to print any more.
 */
EXTERNAL int sysNumProcessors()
{
  int numCpus = -1;  /* -1 means failure. */
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysNumProcessors\n", Me);  
#ifdef RVM_FOR_HARMONY
  numCpus = hysysinfo_get_number_CPUs();
#else
#ifdef __GNU_LIBRARY__      // get_nprocs is part of the GNU C library.
  /* get_nprocs_conf will give us a how many processors the operating
     system configured.  The number of processors actually online is what
     we want.  */
  // numCpus = get_nprocs_conf();
  errno = 0;
  numCpus = get_nprocs();
  // It is not clear if get_nprocs can ever return failure; assume it might.
  if (numCpus < 1) {
    CONSOLE_PRINTF(SysTraceFile, "%s: WARNING: get_nprocs() returned %d (errno=%d)\n",
                   Me, numCpus, errno);
    /* Continue on.  Try to get a better answer by some other method, not
       that it's likely, but this should not be a fatal error. */
  }
#endif

#if defined(CTL_HW) && defined(HW_NCPU)
  if (numCpus < 1) {
    int mib[2];
    size_t len;
    mib[0] = CTL_HW;
    mib[1] = HW_NCPU;
    len = sizeof(numCpus);
    errno = 0;
    if (sysctl(mib, 2, &numCpus, &len, NULL, 0) < 0) {
      CONSOLE_PRINTF(SysTraceFile, "%s: WARNING: sysctl(CTL_HW,HW_NCPU) failed;"
                                   " errno = %d\n", Me, errno);
      numCpus = -1;       // failed so far...
    };
  }
#endif

#if defined(_SC_NPROCESSORS_ONLN)
  if (numCpus < 0) {
    /* This alternative is probably the same as
     *  _system_configuration.ncpus.  This one says how many CPUs are
     *  actually on line.  It seems to be supported on AIX, at least; I
     *  yanked this out of sysVirtualProcessorBind.
     */
    numCpus = sysconf(_SC_NPROCESSORS_ONLN); // does not set errno
    if (numCpus < 0) {
      CONSOLE_PRINTF(SysTraceFile, "%s: WARNING: sysconf(_SC_NPROCESSORS_ONLN)"
                                    " failed\n", Me);
    }
  }
#endif

#ifdef _AIX
  if (numCpus < 0) {
    numCpus = _system_configuration.ncpus;
    if (numCpus < 0) {
      fprintf(SysTraceFile, "%s: WARNING: _system_configuration.ncpus"
              " has the insane value %d\n" , Me, numCpus);
    }
  }
#endif
#endif // RVM_FOR_HARMONY

  if (numCpus < 0) {
    TRACE_PRINTF(SysTraceFile, "%s: WARNING: Can not figure out how many CPUs"
                               " are online; assuming 1\n");
    numCpus = 1;            // Default
  }

  TRACE_PRINTF(SysTraceFile, "%s: sysNumProcessors: returning %d\n", Me, numCpus);
  return numCpus;
}

/**
 * Create a native thread
 * Taken:    register values to use for thread startup
 * Returned: virtual processor's OS handle
 */
EXTERNAL Address sysNativeThreadCreate(Address tr, Address ip, Address fp)
{
  Address        *sysNativeThreadArguments;
#ifndef RVM_FOR_HARMONY
  pthread_attr_t sysNativeThreadAttributes;
  pthread_t      sysNativeThreadHandle;
#else
  hythread_t     sysNativeThreadHandle;
#endif
  int            rc;
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysNativeThreadCreate %p %p %p\n", Me, tr, ip, fp);

  // create arguments
  // @TODO reclaim this memory
  sysNativeThreadArguments = (Address *)malloc(sizeof(Address[3]));
  sysNativeThreadArguments[0] = tr;
  sysNativeThreadArguments[1] = ip;
  sysNativeThreadArguments[2] = fp;

#ifndef RVM_FOR_HARMONY
  // create attributes
  if ((rc = pthread_attr_init(&sysNativeThreadAttributes))) {
    CONSOLE_PRINTF(SysErrorFile, "%s: pthread_attr_init failed (rc=%d)\n", Me, rc);
    sysExit(EXIT_STATUS_SYSCALL_TROUBLE);
  }
  // force 1:1 pthread to kernel thread mapping (on AIX 4.3)
  pthread_attr_setscope(&sysNativeThreadAttributes, PTHREAD_SCOPE_SYSTEM);
#endif
  // create native thread
#ifdef RVM_FOR_HARMONY
  rc = hythread_create(&sysNativeThreadHandle, 0, HYTHREAD_PRIORITY_NORMAL, 0,
                       (hythread_entrypoint_t)sysNativeThreadStartup,
                       sysNativeThreadArguments);
#else
  rc = pthread_create(&sysNativeThreadHandle,
                      &sysNativeThreadAttributes,
                      sysNativeThreadStartup,
                      sysNativeThreadArguments);
#endif
  if (rc)
  {
    CONSOLE_PRINTF(SysErrorFile, "%s: pthread_create failed (rc=%d)\n", Me, rc);
    sysExit(EXIT_STATUS_SYSCALL_TROUBLE);
  }

#ifdef RVM_FOR_HARMONY
  rc = hythread_detach(sysNativeThreadHandle);
#else
  rc = pthread_detach(sysNativeThreadHandle);
#endif
  if (rc)
  {
    CONSOLE_PRINTF(SysErrorFile, "%s: pthread_detach failed (rc=%d)\n", Me, rc);
    sysExit(EXIT_STATUS_SYSCALL_TROUBLE);
  }
  TRACE_PRINTF(SysTraceFile, "%s: pthread_create 0x%08x\n", Me, (Address) sysNativeThreadHandle);

  return (Address)sysNativeThreadHandle;
}

static void* sysNativeThreadStartup(void *args)
{
  /* install a stack for hardwareTrapHandler() to run on */
  stack_t stack;
  char *stackBuf;
  Address tr, ip, fp;
  jmp_buf *jb;

  SYS_START();
  memset (&stack, 0, sizeof stack);
  stackBuf = (char*)malloc(sizeof(char[SIGSTKSZ]));
  stack.ss_sp = stackBuf;
  stack.ss_flags = 0;
  stack.ss_size = SIGSTKSZ;
  if (sigaltstack (&stack, 0)) {
    CONSOLE_PRINTF(SysErrorFile, "sigaltstack failed (errno=%d)\n",errno);
    exit(1);
  }

  tr = ((Address *)args)[0];

  jb = (jmp_buf*)malloc(sizeof(jmp_buf));
  if (setjmp(*jb)) {
    // this is where we come to terminate the thread
    free(jb);
    *(int*)(tr + RVMThread_execStatus_offset) = RVMThread_TERMINATED;
	
    stack.ss_flags = SS_DISABLE;
    sigaltstack(&stack, 0);
    free(stackBuf);
  } else {
    pthread_setspecific(TerminateJmpBufKey, jb);
	
    ip = ((Address *)args)[1];
    fp = ((Address *)args)[2];
	
    TRACE_PRINTF(SysTraceFile, "%s: sysNativeThreadStartup: pr=%p ip=%p fp=%p\n", Me, tr, ip, fp);
    // branch to vm code
#ifndef RVM_FOR_POWERPC
    {
      *(Address *) (tr + Thread_framePointer_offset) = fp;
      Address sp = fp + Constants_STACKFRAME_BODY_OFFSET;
      bootThread((void*)ip, (void*)tr, (void*)sp);
    }
#else
    bootThread((int)(Word)getJTOC(), tr, ip, fp);
#endif
    // not reached
    CONSOLE_PRINTF(SysTraceFile, "%s: sysNativeThreadStartup: failed\n", Me);
    return 0;
  }
}

EXTERNAL int sysNativeThreadBindSupported()
{
  int result=0;
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysNativeThreadBindSupported");
#ifdef RVM_FOR_AIX
  result=1;
#endif
#ifdef RVM_FOR_LINUX
  result=1;
#endif
  return result;
}

/**
 * Bind execution of current thread to specified physical cpu.
 * Taken:    physical cpu id (0, 1, 2, ...)
 * Returned: nothing
 */
EXTERNAL void sysNativeThreadBind(int UNUSED cpuId)
{
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysNativeThreadBind");
#ifndef RVM_FOR_HARMONY
  // bindprocessor() seems to be only on AIX
#ifdef RVM_FOR_AIX
  int rc = bindprocessor(BINDTHREAD, thread_self(), cpuId);
  fprintf(SysTraceFile, "%s: bindprocessor pthread %d (kernel thread %d) %s to cpu %d\n", Me, pthread_self(), thread_self(), (rc ? "NOT bound" : "bound"), cpuId);

  if (rc) {
    CONSOLE_PRINTF(SysErrorFile, "%s: bindprocessor failed (errno=%d): ", Me, errno);
    perror(NULL);
    sysExit(EXIT_STATUS_SYSCALL_TROUBLE);
  }
#endif

#ifdef RVM_FOR_LINUX
  cpu_set_t cpuset;
  CPU_ZERO(&cpuset);
  CPU_SET(cpuId, &cpuset);

  pthread_setaffinity_np(pthread_self(), sizeof(cpuset), &cpuset);
#endif
#endif
}

/** Create keys for thread-specific data. */
EXTERNAL void sysCreateThreadSpecificDataKeys(void)
{
  int rc;
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysNativeThreadSpecificDataKeys");

  // Create a key for thread-specific data so we can associate
  // the id of the Processor object with the pthread it is running on.
  rc = pthread_key_create(&VmThreadKey, 0);
  if (rc != 0) {
    CONSOLE_PRINTF(SysErrorFile, "%s: pthread_key_create(&VmThreadKey,0) failed (err=%d)\n", Me, rc);
    sysExit(EXIT_STATUS_SYSCALL_TROUBLE);
  }
  rc = pthread_key_create(&IsVmThreadKey, 0);
  if (rc != 0) {
    CONSOLE_PRINTF(SysErrorFile, "%s: pthread_key_create(&IsVmThreadKey,0) failed (err=%d)\n", Me, rc);
    sysExit(EXIT_STATUS_SYSCALL_TROUBLE);
  }
  rc = pthread_key_create(&TerminateJmpBufKey, 0);
  if (rc != 0) {
    CONSOLE_PRINTF(SysErrorFile, "%s: pthread_key_create(&TerminateJmpBufKey,0) failed (err=%d)\n", Me, rc);
    sysExit(EXIT_STATUS_SYSCALL_TROUBLE);
  }
  TRACE_PRINTF(SysErrorFile, "%s: vm processor key=%u\n", Me, VmThreadKey);
}

/**
 * sysPthreadSelf() just returns the thread ID of the current thread.
 *
 * This happens to be only called once, at thread startup time, but
 * please don't rely on that fact.
 */
EXTERNAL int sysPthreadSelf()
{
  int thread;
  SYS_START();
#ifdef RVM_FOR_HARMONY
  thread = (int)hythread_self();
#else
  thread = (int)pthread_self();
#endif
  TRACE_PRINTF(SysTraceFile, "%s: sysPthreadSelf: thread %d\n", Me, thread);
  return thread;
}

/**
 * Perform some initialization related to per-thread signal handling
 * for that thread. (Block SIGCONT, set up a special signal handling
 * stack for the thread.)  This is only called once, at thread startup
 * time.
 */
EXTERNAL void sysPthreadSetupSignalHandling()
{
  int rc;                     // retval from subfunction.
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysPthreadSetupSignalHandling %d\n", Me);
#ifndef RVM_FOR_HARMONY
#ifndef RVM_FOR_AIX
  /*
   * Provide space for this pthread to process exceptions.  This is
   * needed on Linux because multiple pthreads can handle signals
   * concurrently, since the masking of signals during handling applies
   * on a per-pthread basis.
   */
  stack_t stack;

  memset (&stack, 0, sizeof stack);
  stack.ss_sp = (char*)malloc(sizeof(char[SIGSTKSZ]));

  stack.ss_size = SIGSTKSZ;
  if (sigaltstack (&stack, 0)) {
    /* Only fails with EINVAL, ENOMEM, EPERM */
    CONSOLE_PRINTF (SysErrorFile, "sigaltstack failed (errno=%d): ", errno);
    perror(NULL);
    sysExit(EXIT_STATUS_IMPOSSIBLE_LIBRARY_FUNCTION_ERROR);
  }
#endif

  /*
   * Block the CONT signal.  This makes SIGCONT reach this
   * pthread only when this pthread performs a sigwait().
   */
  sigset_t input_set, output_set;
  sigemptyset(&input_set);
  sigaddset(&input_set, SIGCONT);

#ifdef RVM_FOR_AIX
  rc = sigthreadmask(SIG_BLOCK, &input_set, &output_set);
  /* like pthread_sigmask, sigthreadmask can only return EINVAL, EFAULT, and
   * EPERM.  Again, these are all good reasons to complain and croak. */
#else
  rc = pthread_sigmask(SIG_BLOCK, &input_set, &output_set);
  /* pthread_sigmask can only return the following errors.  Either of them
   * indicates serious trouble and is grounds for aborting the process:
   * EINVAL EFAULT.  */
#endif
  if (rc) {
    CONSOLE_PRINTF (SysErrorFile, "pthread_sigmask or sigthreadmask failed (errno=%d):", errno);
    perror(NULL);
    sysExit(EXIT_STATUS_IMPOSSIBLE_LIBRARY_FUNCTION_ERROR);
  }
#endif // RVM_FOR_HARMONY -- TODO
}

EXTERNAL void sysPthreadExit()
{
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: pthread %d exits\n", Me,
#ifdef RVM_FOR_HARMONY
               hythread_self());
#else
               pthread_self());
#endif
#ifdef RVM_FOR_HARMONY
  hythread_exit(NULL);
#else
  pthread_exit(NULL);
#endif
}

/**
 * Yield execution of current thread back to o/s.
 */
EXTERNAL void sysSchedYield()
{
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysSchedYield\n", Me);
#ifdef RVM_FOR_HARMONY
  hythread_yield();
#else
  /*  According to the Linux manpage, sched_yield()'s presence can be
   *  tested for by using the #define _POSIX_PRIORITY_SCHEDULING, and if
   *  that is not present to use the sysconf feature, searching against
   *  _SC_PRIORITY_SCHEDULING.  However, I don't really trust it, since
   *  the AIX 5.1 include files include this definition:
   *      ./unistd.h:#undef _POSIX_PRIORITY_SCHEDULING
   *  so my trust that it is implemented properly is scanty.  --augart
   */
  sched_yield();
#endif // RVM_FOR_HARMONY -- TODO
}

EXTERNAL Address sysPthreadMutexCreate()
{
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysPthreadMutexCreate\n", Me);
#ifdef RVM_FOR_HARMONY
  hythread_monitor_t mutex;
  hythread_monitor_init(&mutex, HYTHREAD_MONITOR_SYSTEM);
#else
  pthread_mutex_t *mutex = (pthread_mutex_t *)malloc(sizeof(pthread_mutex_t));
  pthread_mutex_init(mutex,NULL);
#endif
  return (Address)mutex;
}

EXTERNAL void sysPthreadMutexDestroy(Address _mutex)
{
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysPthreadMutexDestroy\n", Me);
  pthread_mutex_t *mutex=(pthread_mutex_t*)_mutex;
  pthread_mutex_destroy(mutex);
  free(mutex);
}

EXTERNAL void sysPthreadMutexLock(Address _mutex)
{
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysPthreadMutexLock\n", Me);
  pthread_mutex_lock((pthread_mutex_t*)_mutex);
}

EXTERNAL void sysPthreadMutexUnlock(Address _mutex)
{
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysPthreadMutexUnlock\n", Me);
  pthread_mutex_unlock((pthread_mutex_t*)_mutex);
}

EXTERNAL Address sysPthreadCondCreate()
{
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysPthreadCondCreate\n", Me);
  pthread_cond_t *cond = (pthread_cond_t *)malloc(sizeof(pthread_cond_t));
  pthread_cond_init(cond,NULL);
  return (Address)cond;
}

EXTERNAL void sysPthreadCondDestroy(Address _cond)
{
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysPthreadCondDestroy\n", Me);
  pthread_cond_t *cond=(pthread_cond_t*)_cond;
  pthread_cond_destroy(cond);
  free(cond);
}

EXTERNAL void sysPthreadCondTimedWait(Word cond,Word mutex,	long long whenWakeupNanos)
{
  timespec ts;
  SYS_START();
  ts.tv_sec = (time_t)(whenWakeupNanos / 1000000000LL);
  ts.tv_nsec = (long)(whenWakeupNanos % 1000000000LL);
  TRACE_PRINTF(SysTraceFile, "starting wait at %lld until %lld (%ld, %ld)\n",
               sysNanoTime(),whenWakeupNanos,ts.tv_sec,ts.tv_nsec);
  int res=pthread_cond_timedwait((pthread_cond_t*)cond,
                        		   (pthread_mutex_t*)mutex,
                                 &ts);
  TRACE_PRINTF(SysTraceFile, "returned from wait at %lld instead of %lld with res = %d\n",
               sysNanoTime(),whenWakeupNanos,res);
}

EXTERNAL void sysPthreadCondWait(Word cond,Word mutex)
{
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysPthreadCondWait\n", Me);
  pthread_cond_wait((pthread_cond_t*)cond, (pthread_mutex_t*)mutex);
}

EXTERNAL void sysPthreadCondBroadcast(Word cond)
{
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysPthreadCondBroadcast\n", Me);
  pthread_cond_broadcast((pthread_cond_t*)cond);
}

/**
 * Stash address of the Processor object in the thread-specific data
 * for the current pthread.  This allows us to get a handle on the
 * Processor (and its associated state) from arbitrary native code.
 */
EXTERNAL int sysStashVmThreadInPthread(Address vmProcessor)
{
  SYS_START();
  TRACE_PRINTF(SysErrorFile, "%s: sysStashVmProcessorInPthread %p\n", Me, vmProcessor);
#ifndef RVM_FOR_HARMONY
  int rc = pthread_setspecific(VmThreadKey, (void*) vmProcessor);
  int rc2 = pthread_setspecific(IsVmThreadKey, (void*) 1);
  if (rc != 0 || rc2 != 0) {
    CONSOLE_PRINTF(SysErrorFile, "%s: pthread_setspecific() failed (err=%d,%d)\n", Me,
				rc, rc2);
    sysExit(EXIT_STATUS_SYSCALL_TROUBLE);
  }
#endif // RVM_FOR_HARMONY -- TODO
  return 0;
}

EXTERNAL void sysTerminatePthread()
{
  jmp_buf *jb;
  SYS_START();
  TRACE_PRINTF(SysErrorFile, "%s: sysTerminatePthread\n", Me);
#ifdef RVM_FOR_POWERPC
  asm("sync");
#endif
  jb = (jmp_buf*)pthread_getspecific(TerminateJmpBufKey);
  if (jb==NULL) {
    jb=&primordial_jb;
  }
  longjmp(*jb,1);
}

/**
 * Routine to sleep for a number of nanoseconds (howLongNanos).  This is
 * ridiculous on regular Linux, where we actually only sleep in increments of
 * 1/HZ (1/100 of a second on x86).  Luckily, Linux will round up.
 *
 * This is just used internally in the scheduler, but we might as well make
 * the function work properly even if it gets used for other purposes.
 *
 * We don't return anything, since we don't need to right now.  Just try to
 * sleep; if interrupted, return.
 */
EXTERNAL void sysNanosleep(long long howLongNanos)
{
  SYS_START();
  TRACE_PRINTF(SysTraceFile, "%s: sysNanosleep %lld\n", Me, howLongNanos);
#ifdef RVM_FOR_HARMONY
  hythread_sleep(howLongNanos/1000);
#else
  struct timespec req;
  const long long nanosPerSec = 1000LL * 1000 * 1000;
  req.tv_sec = howLongNanos / nanosPerSec;
  req.tv_nsec = howLongNanos % nanosPerSec;
  int ret = nanosleep(&req, (struct timespec *) NULL);
  if (ret < 0) {
    if (errno == EINTR)
       /* EINTR is expected, since we do use signals internally. */
      return;
    CONSOLE_PRINTF(SysErrorFile, "%s: nanosleep(<tv_sec=%ld,tv_nsec=%ld>) failed:"
                   " %s (errno=%d)\n"
                   "  That should never happen; please report it as a bug.\n",
                   Me, req.tv_sec, req.tv_nsec,
                   strerror( errno ), errno);
  }
#endif
}
