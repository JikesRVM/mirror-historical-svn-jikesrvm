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
package org.jikesrvm.scheduler;

import org.jikesrvm.ArchitectureSpecific.CodeArray;
import org.jikesrvm.ArchitectureSpecific.Registers;
import org.jikesrvm.ArchitectureSpecific.OSR_PostThreadSwitch;
import static org.jikesrvm.ArchitectureSpecific.StackframeLayoutConstants.STACK_SIZE_NORMAL;
import static org.jikesrvm.ArchitectureSpecific.StackframeLayoutConstants.INVISIBLE_METHOD_ID;
import static org.jikesrvm.ArchitectureSpecific.StackframeLayoutConstants.STACKFRAME_SENTINEL_FP;
import static org.jikesrvm.ArchitectureSpecific.StackframeLayoutConstants.STACK_SIZE_GUARD;
import org.jikesrvm.ArchitectureSpecific.ThreadLocalState;
import org.jikesrvm.ArchitectureSpecific.StackframeLayoutConstants;
import org.jikesrvm.ArchitectureSpecific.ArchConstants;
import org.jikesrvm.VM;
import org.jikesrvm.Configuration;
import org.jikesrvm.Services;
import org.jikesrvm.UnimplementedError;
import org.jikesrvm.adaptive.OSR_OnStackReplacementEvent;
import org.jikesrvm.adaptive.measurements.RuntimeMeasurements;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.osr.OSR_ObjectHolder;
import org.jikesrvm.adaptive.OSR_Listener;
import org.jikesrvm.jni.JNIEnvironment;
import org.jikesrvm.memorymanagers.mminterface.MM_Interface;
import org.jikesrvm.memorymanagers.mminterface.MM_ThreadContext;
import org.jikesrvm.memorymanagers.mminterface.CollectorThread;
import org.jikesrvm.memorymanagers.mminterface.ConcurrentCollectorThread;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.objectmodel.ThinLockConstants;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.runtime.Memory;
import org.jikesrvm.runtime.RuntimeEntrypoints;
import org.jikesrvm.runtime.Time;
import org.jikesrvm.runtime.BootRecord;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.BaselineNoRegisters;
import org.vmmagic.pragma.BaselineSaveLSRegisters;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.LogicallyUninterruptible;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.NoOptCompile;
import org.vmmagic.pragma.NonMoving;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.pragma.Untraced;
import org.vmmagic.pragma.NoCheckStore;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.Offset;
import static org.jikesrvm.runtime.SysCall.sysCall;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.opt.runtimesupport.OptCompiledMethod;
import org.jikesrvm.compilers.opt.runtimesupport.OptMachineCodeMap;
import org.jikesrvm.compilers.opt.runtimesupport.OptEncodedCallSiteTree;
import org.jikesrvm.classloader.MemberReference;
import org.jikesrvm.classloader.NormalMethod;

/**
 * A generic java thread's execution context.
 *
 * @see org.jikesrvm.scheduler.greenthreads.GreenThread
 * @see org.jikesrvm.memorymanagers.mminterface.CollectorThread
 * @see DebuggerThread
 * @see FinalizerThread
 * @see org.jikesrvm.adaptive.measurements.organizers.Organizer
 */
@Uninterruptible
@NonMoving
public class RVMThread extends MM_ThreadContext {
  /*
   *  debug and statistics
   */
  /** Trace execution */
  protected static final boolean trace = false;
  /** Trace thread termination */
  private static final boolean traceTermination = false;
  /** Trace adjustments to stack size */
  private static final boolean traceAdjustments = false;
  /** Generate statistics? */
  private static final boolean STATS = Lock.STATS;
  /** Number of wait operations */
  static int waitOperations;
  /** Number of timed wait operations */
  static int timedWaitOperations;
  /** Number of notify operations */
  static int notifyOperations;
  /** Number of notifyAll operations */
  static int notifyAllOperations;

  /**
   * The thread is modeled by a state machine the following constants describe
   * the state of the state machine. Invalid transitions will generate
   * IllegalThreadStateExceptions.
   */
  protected static enum State {
    /**
     * The thread is created but not yet scheduled to run. This state is the
     * same as {@link Thread.State#NEW}
     */
    NEW,
    /**
     * The thread is scheduled to run on a Processor. This state is the same
     * as {@link Thread.State#RUNNABLE}
     */
    RUNNABLE,
    /**
     * The thread is blocked by waiting for a monitor lock. This state is the
     * same as {@link Thread.State#BLOCKED}
     */
    BLOCKED,
    /**
     * The thread is waiting indefintely for a notify. This state maps to
     * {@link Thread.State#WAITING}
     */
    WAITING,
    /**
     * The thread is waiting for a notify or a time out. This state maps to
     * {@link Thread.State#TIMED_WAITING}
     */
    TIMED_WAITING,
    /**
     * The thread has exited. This state maps to {@link Thread.State#TERMINATED}
     */
    TERMINATED,
    /**
     * The thread is waiting for a notify or a time out. This state maps to
     * {@link Thread.State#TIMED_WAITING}
     */
    SLEEPING,
    /**
     * The thread is suspended awaiting a resume. This state maps to
     * {@link Thread.State#WAITING} which makes better sense than the JDK 1.5
     * convention
     */
    SUSPENDED,
    /**
     * The thread is parked for OSR awaiting an OSR unpark. This state maps to
     * {@link Thread.State#WAITING}
     */
    OSR_PARKED,
    /**
     * The thread is awaiting this thread to become RUNNABLE. This state maps to
     * {@link Thread.State#WAITING} matching JDK 1.5 convention
     */
    JOINING,
    /**
     * The thread is parked awaiting a unpark. This state maps to
     * {@link Thread.State#WAITING} matching JDK 1.5 convention
     */
    PARKED,
    /**
     * The thread is parked awaiting a unpark. This state maps to
     * {@link Thread.State#TIMED_WAITING} matching JDK 1.5 convention
     */
    TIMED_PARK,
    /**
     * This state is valid only for green threads. The thread is awaiting IO to
     * readable. This state maps to {@link Thread.State#WAITING}.
     */
    IO_WAITING,
    /**
     * This state is valid only for green threads. The thread is awaiting a
     * process to finish. This state maps to {@link Thread.State#WAITING}.
     */
    PROCESS_WAITING
  }

  /**
   * State of the thread. Either NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING,
   * TERMINATED, SLEEPING, SUSPENDED or PARKED
   */
  protected State state;

  /**
   * java.lang.Thread wrapper for this Thread. Not final so it may be
   * assigned during booting
   */
  private Thread thread;

  /** Name of the thread (can be changed during execution) */
  private String name;

  /**
   * The virtual machine terminates when the last non-daemon (user)
   * thread terminates.
   */
  protected boolean daemon;

  /**
   * Scheduling priority for this thread.
   * Note that: {@link java.lang.Thread#MIN_PRIORITY} <= priority
   * <= {@link java.lang.Thread#MAX_PRIORITY}.
   */
  private int priority;

  /**
   * Index of this thread in {@link threads}[].
   * This value must be non-zero because it is shifted
   * and used in {@link Object} lock ownership tests.
   */
  @Entrypoint
  private int threadSlot;

  /**
   * Thread is a system thread, that is one used by the system and as
   * such doesn't have a Runnable...
   */
  final boolean systemThread;

  /**
   * The boot thread, can't be final so as to allow initialization during boot
   * image writing.
   */
  @Entrypoint
  public static RVMThread bootThread;

  /**
   * Is the threading system initialized?
   */
  public static boolean threadingInitialized = false;
  
  /**
   * Number of timer ticks we've seen
   */
  public static long timerTicks;
  
  private long yieldpointsTaken;
  private long yieldpointsTakenFully;

  /**
   * Assertion checking while manipulating raw addresses --
   * see {@link VM#disableGC()}/{@link VM#enableGC()}.
   * A value of "true" means it's an error for this thread to call "new".
   * This is only used for assertion checking; we do not bother to set it when
   * {@link VM#VerifyAssertions} is false.
   */
  private boolean disallowAllocationsByThisThread;

  /**
   * Counts the depth of outstanding calls to {@link VM#disableGC()}.  If this
   * is set, then we should also have {@link #disallowAllocationsByThisThread}
   * set.  The converse also holds.
   */
  private int disableGCDepth = 0;

  /**
   * Execution stack for this thread.
   */
  @Entrypoint
  private byte[] stack;

  /** The {@link Address} of the guard area for {@link #stack}. */
  @Entrypoint
  public Address stackLimit;

  /* --------- BEGIN IA-specific fields. NOTE: NEED TO REFACTOR --------- */
  // On powerpc, these values are in dedicated registers,
  // we don't have registers to burn on IA32, so we indirect
  // through the PR register to get them instead.
  // these can all be moved to RVMThread
  /**
   * FP for current frame, saved in the prologue of every method
   */
  Address framePointer;
  /**
   * "hidden parameter" for interface invocation thru the IMT
   */
  int hiddenSignatureId;
  /**
   * "hidden parameter" from ArrayIndexOutOfBounds trap to C trap handler
   */
  int arrayIndexTrapParam;
  /* --------- END IA-specific fields. NOTE: NEED TO REFACTOR --------- */

  // More GC fields
  //
  // why is this here?  it came from Processor.  is this just to make counting scalable?
  // PNT: revisit these fields.
  /** count live objects during gc */
  public int large_live;
  /** count live objects during gc */
  public int small_live;
  /** used for instrumentation in allocators */
  public long totalBytesAllocated;
  /** used for instrumentation in allocators */
  public long totalObjectsAllocated;
  /** used for instrumentation in allocators */
  public long synchronizedObjectsAllocated;

  /**
   * Is the next taken yieldpoint in response to a request to perform OSR?
   */
  public boolean yieldToOSRRequested;

  /**
   * Is CBS enabled for 'call' yieldpoints?
   */
  public boolean yieldForCBSCall;

  /**
   * Is CBS enabled for 'method' yieldpoints?
   */
  public boolean yieldForCBSMethod;

  /**
   * Number of CBS samples to take in this window
   */
  public int numCBSCallSamples;

  /**
   * Number of call yieldpoints between CBS samples
   */
  public int countdownCBSCall;

  /**
   * round robin starting point for CBS samples
   */
  public int firstCBSCallSample;

  /**
   * Number of CBS samples to take in this window
   */
  public int numCBSMethodSamples;

  /**
   * Number of counter ticks between CBS samples
   */
  public int countdownCBSMethod;

  /**
   * round robin starting point for CBS samples
   */
  public int firstCBSMethodSample;
  
  /* --------- BEGIN PPC-specific fields. NOTE: NEED TO REFACTOR --------- */
  /**
   * flag indicating this processor needs to execute a memory synchronization sequence
   * Used for code patching on SMP PowerPCs.
   */
  public boolean codePatchSyncRequested;
  /* --------- END PPC-specific fields. NOTE: NEED TO REFACTOR --------- */

  /**
   * For builds using counter-based sampling.  This field holds a
   * processor-specific counter so that it can be updated efficiently
   * on SMP's.
   */
  public int thread_cbs_counter;
  
  /**
   * Should this thread yield at yieldpoints?
   * A value of:
   *    1 means "yes" (yieldpoints enabled)
   * <= 0 means "no"  (yieldpoints disabled)
   */
  private int yieldpointsEnabledCount;

  /**
   * Is a takeYieldpoint request pending on this thread?
   */
  boolean yieldpointRequestPending;
  
  /**
   * Is there a flush request for this thread?  This is handled via
   * a soft handshake.
   */
  public boolean flushRequested;

  /**
   * Is a soft handshake requested?  Logically, this field is protected by
   * the thread's monitor - but it is typically only mucked with when both
   * the thread's monitor and the softHandshakeDataLock are held.
   */
  public boolean softHandshakeRequested;
  
  /**
   * How many threads have not yet reached the soft handshake?
   * (protected by softHandshakeDataLock)
   */
  public static int softHandshakeLeft;
  
  /**
   * Lock that protects soft handshake fields.
   */
  public static HeavyCondLock softHandshakeDataLock;
  
  /**
   * Lock that prevents multiple (soft or hard) handshakes from proceeding
   * concurrently.
   */
  public static HeavyCondLock handshakeLock;
  
  /**
   * Place to save register state when this thread is not actually running.
   */
  @Entrypoint
  @Untraced
  public final Registers contextRegisters;

  /**
   * Place to save register state during hardware(C signal trap handler) or
   * software (RuntimeEntrypoints.athrow) trap handling.
   */
  @Entrypoint
  @Untraced
  private final Registers exceptionRegisters;

  /** Count of recursive uncaught exceptions, we need to bail out at some point */
  private int uncaughtExceptionCount = 0;
  
  /** A cached free lock.  Not a free list; this will only ever contain
   * one lock! */
  public Lock cachedFreeLock;

  /*
   * Wait/notify fields
   */

  /**
   * Place to save/restore this thread's monitor state during
   * {@link Object#wait} and {@link Object#notify}.
   */
  protected Object waitObject;

  /** Lock recursion count for this thread's monitor. */
  protected int    waitCount;

  /**
   * Should the thread suspend?
   */
  boolean shouldSuspend;
  
  /**
   * An integer token identifying the last suspend request
   */
  int shouldSuspendToken;
  
  /**
   * Is the thread suspended?
   */
  boolean isSuspended;
  
  /**
   * Should the thread block for GC?
   */
  boolean shouldBlockForGC;
  
  /**
   * Is the thread blocked for GC?
   */
  boolean isBlockedForGC;
  
  @Uninterruptible
  @NonMoving
  public static abstract class BlockAdapter {
    abstract boolean isBlocked(RVMThread t);
    abstract void setBlocked(RVMThread t,boolean value);
    abstract int requestBlock(RVMThread t);
    abstract boolean hasBlockRequest(RVMThread t);
    abstract boolean hasBlockRequest(RVMThread t,int token);
    abstract void clearBlockRequest(RVMThread t);
  }
  
  @Uninterruptible
  @NonMoving
  public static class SuspendBlockAdapter extends BlockAdapter {
    boolean isBlocked(RVMThread t) { return t.isSuspended; }
    void setBlocked(RVMThread t,boolean value) { t.isSuspended=value; }
    int requestBlock(RVMThread t) {
      if (t.isSuspended || t.shouldSuspend) {
	return t.shouldSuspendToken;
      } else {
	t.shouldSuspend=true;
	t.shouldSuspendToken++;
	return t.shouldSuspendToken;
      }
    }
    boolean hasBlockRequest(RVMThread t) { return t.shouldSuspend; }
    boolean hasBlockRequest(RVMThread t, int token) {
      return t.shouldSuspend && t.shouldSuspendToken==token;
    }
    void clearBlockRequest(RVMThread t) {
      t.shouldSuspend=false;
    }
  }
  
  public static final SuspendBlockAdapter suspendBlockAdapter=
    new SuspendBlockAdapter();
  
  @Uninterruptible
  @NonMoving
  public static class GCBlockAdapter extends BlockAdapter {
    boolean isBlocked(RVMThread t) { return t.isBlockedForGC; }
    void setBlocked(RVMThread t,boolean value) { t.isBlockedForGC=value; }
    int requestBlock(RVMThread t) {
      if (!t.isBlockedForGC) {
	t.shouldBlockForGC=true;
      }
      return 0;
    }
    boolean hasBlockRequest(RVMThread t) { return t.shouldBlockForGC; }
    boolean hasBlockRequest(RVMThread t,
			    int token) { return t.shouldBlockForGC; }
    void clearBlockRequest(RVMThread t) { t.shouldBlockForGC=false; }
  }
  
  public static final GCBlockAdapter gcBlockAdapter=
    new GCBlockAdapter();
  
  static final BlockAdapter[] blockAdapters=
    new BlockAdapter[]{ suspendBlockAdapter,
			gcBlockAdapter };

  /**
   * An enumeration that describes the different manners in which a thread
   * might be voluntarily waiting.
   */
  protected static enum Waiting {
    /** The thread is not waiting at all.  In fact it's running. */
    RUNNABLE,
      
    /** The thread is waiting without a timeout. */
    WAITING,
      
    /** The thread is waiting with a timeout. */
    TIMED_WAITING
  }
  
  /**
   * Accounting of whether or not a thread is waiting (in the Java thread
   * state sense), and if so, how it's waiting.
   * <p>
   * Invariant: the RVM runtime does not ever use this field for any purpose
   * other than updating it so that the java.lang.Thread knows the state.
   * Thus, if you get sloppy with this field, the worst case outcome is that
   * some Java program that queries the thread state will get something
   * other than what it may or may not have expected.
   */
  protected Waiting waiting;
  
  /**
   * Exception to throw in this thread at the earliest possible point.
   */
  Throwable asyncThrowable;
  
  /**
   * Has the thread been interrupted?
   */
  boolean hasInterrupt;
  
  /**
   * Should the next executed yieldpoint be taken?
   * Can be true for a variety of reasons. See RVMThread.yieldpoint
   * <p>
   * To support efficient sampling of only prologue/epilogues
   * we also encode some extra information into this field.
   *   0  means that the yieldpoint should not be taken.
   *   >0 means that the next yieldpoint of any type should be taken
   *   <0 means that the next prologue/epilogue yieldpoint should be taken
   * <p>
   * Note the following rules:
   * <ol>
   * <li>If takeYieldpoint is set to 0 or -1 it is perfectly safe to set it
   *     to 1; this will have almost no effect on the system.  Thus, setting
   *     takeYieldpoint to 1 can always be done without acquiring locks.</li>
   * <li>Setting takeYieldpoint to any value other than 1 should
   *     be done while holding the thread's monitor().</li>
   * <li>The exception to rule (2) above is that the yieldpoint itself sets
   *     takeYieldpoint to 0 without holding a lock - but this is done after
   *     it ensures that the yieldpoint is deferred by setting
   *     yieldpointRequestPending to true.
   * </ol>
   */
  @Entrypoint
  public int takeYieldpoint;
  
  /** How many times has the "timeslice" expired?  This is only used for
   * profiling and OSR (in particular base-to-opt OSR). */
  @Entrypoint
  public int timeSliceExpired;

  /** Is a running thread permitted to ignore the next park request */
  private boolean parkingPermit;
  
  /*
   * JNI fields
   */

  /**
   * Cached JNI environment for this thread
   */
  @Entrypoint
  @Untraced
  public JNIEnvironment jniEnv;

  /** Used by GC to determine collection success */
  private boolean physicalAllocationFailed;

  /** Is this thread performing emergency allocation, when the normal heap limits are ignored. */
  private boolean emergencyAllocation;

  /** Used by GC to determine collection success */
  private int collectionAttempt;

  /** The OOME to throw */
  private OutOfMemoryError outOfMemoryError;

  /*
   * Enumerate different types of yield points for sampling
   */
  public static final int PROLOGUE = 0;
  public static final int BACKEDGE = 1;
  public static final int EPILOGUE = 2;
  public static final int NATIVE_PROLOGUE = 3;
  public static final int NATIVE_EPILOGUE = 4;
  public static final int OSROPT = 5;

  /*
   * Fields used for on stack replacement
   */

  /**
   * Only used by OSR when VM.BuildForAdaptiveSystem. Declared as an
   * Object to cut link to adaptive system.  Ugh.
   */
  public final Object /* OSR_OnStackReplacementEvent */ onStackReplacementEvent;

  /**
   * The flag indicates whether this thread is waiting for on stack
   * replacement before being rescheduled.
   */
  //flags should be packaged or replaced by other solutions
  public boolean isWaitingForOsr = false;

  /**
   * Before call new instructions, we need a bridge to recover
   * register states from the stack frame.
   */
  public CodeArray bridgeInstructions = null;
  /** Foo frame pointer offset */
  public Offset fooFPOffset = Offset.zero();
  /** Thread switch frame pointer offset */
  public Offset tsFPOffset = Offset.zero();

  /**
   * Flag to synchronize with osr organizer, the trigger sets osr
   * requests the organizer clear the requests
   */
  public boolean requesting_osr = false;
  
  /**
   * Flag to indicate that the last OSR request is done.
   */
  public boolean osr_done = false;

  /**
   * Number of processors that the user wants us to use.  Only relevant
   * for collector threads and the such.
   */
  public static int numProcessors = 1;

  /**
   * Thread handle.  Currently stores pthread_t, which we assume to be
   * no larger than a pointer-sized word.
   */
  public Word pthread_id;
  
  /**
   * Scratch area for use for gpr <=> fpr transfers by PPC baseline compiler.
   * Used to transfer x87 to SSE registers on IA32
   */
  @SuppressWarnings({"unused", "CanBeFinal", "UnusedDeclaration"})
  //accessed via EntryPoints
  private double scratchStorage;

  /**
   * Current index of this thread in the threads array.  This may be changed
   * by another thread, but only while the acctLock is held.
   */
  private int threadIdx;

  /**
   * Is the system in the process of shutting down (has System.exit been called)
   */
  private static boolean systemShuttingDown = false;
  
  /**
   * Flag set by external signal to request debugger activation at next thread switch.
   * See also: RunBootImage.C
   */
  public static volatile boolean debugRequested;

  /** Number of times dump stack has been called recursively */
  protected static int inDumpStack = 0;

  /** In dump stack and dying */
  protected static boolean exitInProgress = false;

  /** Extra debug from traces */
  protected static final boolean traceDetails = false;

  /** Toggle display of frame pointer address in stack dump */
  private static final boolean SHOW_FP_IN_STACK_DUMP = true;

  /** Index of thread in which "VM.boot()" runs */
  public static final int PRIMORDIAL_THREAD_INDEX = 1;

  /** Maximum number of RVMThread's that we can support. */
  public static final int LOG_MAX_THREADS = 14;
  public static final int MAX_THREADS = 1 << LOG_MAX_THREADS;

  /**
   * thread array - all threads are stored in this array according to their
   * threadSlot.
   */
  public static RVMThread[] threadBySlot=new RVMThread[MAX_THREADS];
  
  /**
   * Per-thread monitors.  Note that this array is statically initialized.
   * It starts out all null.  When a new thread slot is allocated, a monitor
   * is added for that slot.
   * <p>
   * Question: what is the outcome, if any, of taking a yieldpoint while
   * holding this lock?
   * <ol>
   * <li>If there is a GC request we will wait on this condition variable
   *     and thus release it.  Someone else might then acquire the lock
   *     before realizing that there is a GC request and then do bad things.</li>
   * <li>The yieldpoint might acquire another thread's monitor.  Thus, two
   *     threads may get into lock inversion with each other.</li>
   * <li>???</li>
   * </ol>
   */
  private static NoYieldpointsCondLock[] monitorBySlot = new NoYieldpointsCondLock[MAX_THREADS];
  
  /**
   * Lock (mutex) used for creating and destroying threads as well as
   * thread accounting.
   */
  public static NoYieldpointsCondLock acctLock;
  
  /**
   * Lock used for generating debug output.
   */
  private static NoYieldpointsCondLock outputLock;
  
  /**
   * Threads that are about to terminate.
   */
  private static RVMThread[] aboutToTerminate = new RVMThread[MAX_THREADS];
  
  /**
   * Number of threads that are about to terminate.
   */
  private static int aboutToTerminateN;
  
  /**
   * Free thread slots
   */
  private static int[] freeSlots = new int[MAX_THREADS];
  
  /**
   * Number of free thread slots.
   */
  private static int freeSlotN;
  
  /**
   * When there are no thread slots on the free list, this is the next one
   * to use.
   */
  private static int nextSlot = 2;
  
  /**
   * Number of threads in the system (some of which may not be active).
   */
  public static int numThreads;

  /**
   * Packed and unordered array or active threads.  Only entries
   * in the range 0 to numThreads-1 (inclusive) are defined.  Note that
   * it should be possible to scan this array without locking and get
   * all of the threads - but only if you scan downward and place a
   * memory fence between loads.
   * <p>
   * Note further that threads remain in this array even after the Java
   * libraries no longer consider the thread to be active.
   */
  public static RVMThread[] threads = new RVMThread[MAX_THREADS];
  
  /**
   * Preallocated array for use in handshakes.  Protected by
   * handshakeLock.
   */
  public static RVMThread[] handshakeThreads = new RVMThread[MAX_THREADS];
    
  /**
   * Number of active threads in the system.
   */
  private static int numActiveThreads;
  
  /**
   * Number of active daemon threads.
   */
  private static int numActiveDaemons;
  
  /**
   * Get a NoYieldpointsCondLock for a given thread slot.
   */
  static NoYieldpointsCondLock monitorForSlot(int slot) {
    NoYieldpointsCondLock result=monitorBySlot[slot];
    if (VM.VerifyAssertions) VM._assert(result!=null);
    return result;
  }
  
  /**
   * Get the NoYieldpointsCondLock for this thread.
   */
  public NoYieldpointsCondLock monitor() {
    return monitorForSlot(threadSlot);
  }
  
  /**
   * Initialize the threading subsystem for the boot image.
   */
  @Interruptible
  public static void init() {
    // Enable us to dump a Java Stack from the C trap handler to aid in debugging things that
    // show up as recursive use of hardware exception registers (eg the long-standing lisp bug)
    BootRecord.the_boot_record.dumpStackAndDieOffset = Entrypoints.dumpStackAndDieMethod.getOffset();

    Lock.init();
  }
  
  /**
   * Boot the threading subsystem.
   */
  @Interruptible // except not really, since we don't enable yieldpoints yet
  public static void boot() {
    acctLock=new NoYieldpointsCondLock();
    outputLock=new NoYieldpointsCondLock();
    softHandshakeDataLock=new HeavyCondLock();
    handshakeLock=new HeavyCondLock();
    monitorBySlot[getCurrentThread().threadSlot] =
      new NoYieldpointsCondLock();
    
    sysCall.sysCreateThreadSpecificDataKeys();
    sysCall.sysStashVmThreadInPthread(getCurrentThread());

    threadingInitialized = true;
    
    TimerThread tt=new TimerThread();
    tt.makeDaemon(true);
    tt.start();
    
    if (VM.BuildForAdaptiveSystem) {
      OSR_ObjectHolder.boot();
    }
    
    CollectorThread.boot();
    ConcurrentCollectorThread.boot();

    for (int i=1;i<=numProcessors;++i) {
      RVMThread t=CollectorThread.createActiveCollectorThread();
      t.start();
    }
    
    for (int i=1;i<=numProcessors;++i) {
      RVMThread t=ConcurrentCollectorThread.createConcurrentCollectorThread();
      t.start();
    }
    
    FinalizerThread.boot();
    
    getCurrentThread().enableYieldpoints();
  }
  
  /**
   * Add this thread to the termination watchlist.  Called by
   * terminating threads before they finish terminating.
   */
  @NoCheckStore
  void addAboutToTerminate() {
    monitor().lock();
    isAboutToTerminate=true;
    monitor().broadcast();
    handleHandshakeRequest();
    monitor().unlock();

    softRendezvous();

    acctLock.lock();
    aboutToTerminate[aboutToTerminateN++]=this;
    acctLock.unlock();
  }
  
  /**
   * Method called from GC before processing list of threads.
   */
  @NoCheckStore
  public static void processAboutToTerminate() {
    acctLock.lock();
    for (int i=0;i<aboutToTerminateN;++i) {
      if (aboutToTerminate[i].execStatus == TERMINATED) {
	aboutToTerminate[i].releaseThreadSlot();
	aboutToTerminate[i--]=aboutToTerminate[--aboutToTerminateN];
      }
    }
    acctLock.unlock();
  }
  
  /**
   * Find a thread slot not in use by any other live thread and bind the
   * given thread to it.  The thread's threadSlot field is set accordingly.
   */
  @Interruptible
  void assignThreadSlot() {
    if (!VM.runningVM) {
      // primordial thread
      threadSlot=1;
      threadBySlot[1]=this;
      
      threads[0]=this;
      threadIdx=0;
      numThreads=1;
    } else {
      acctLock.lock();
      processAboutToTerminate();
      if (freeSlotN>0) {
	threadSlot=freeSlots[--freeSlotN];
      } else {
	if (nextSlot==threads.length) {
	  VM.sysFail("too many threads");
	}
	threadSlot=nextSlot++;
      }
      acctLock.unlock();
      
      // before we actually use this slot, ensure that there is a monitor
      // for it.  note that if the slot doesn't have a monitor, then we
      // "own" it since we allocated it above but haven't done anything
      // with it (it's not assigned to a thread, so nobody else can touch
      // it)
      if (monitorBySlot[threadSlot]==null) {
	monitorBySlot[threadSlot]=new NoYieldpointsCondLock();
      }
      
      Magic.sync(); /* make sure that nobody sees the thread in any
			  of the tables until the thread slot is inited */

      acctLock.lock();
      threadBySlot[threadSlot]=this;

      threadIdx=numThreads++;
      threads[threadIdx]=this;

      acctLock.unlock();
    }
  }
  
  /**
   * Release a thread's slot in the threads array.
   */
  @NoCheckStore
  void releaseThreadSlot() {
    acctLock.lock();
    RVMThread replacementThread=threads[numThreads-1];
    threads[threadIdx]=replacementThread;
    replacementThread.threadIdx=threadIdx;
    threadIdx=-1;
    Magic.sync(); /* make sure that if someone is processing the threads array
			without holding the acctLock (which is definitely legal) then
			they see the replacementThread moved to the new index before
			they see the numThreads decremented (otherwise they would
			miss replacementThread; but with the current arrangement at
			worst they will see it twice) */
    threads[--numThreads]=null;
    threadBySlot[threadSlot]=null;
    freeSlots[freeSlotN++]=threadSlot;
    acctLock.unlock();
  }
  
  /** Start the debugger thread.  Currently not implemented. */
  public static void startDebuggerThread() {
    // PNT: do stuff here
  }
  
  /**
   * @param stack stack in which to execute the thread
   */
  public RVMThread(byte[] stack, Thread thread, String name, boolean daemon, boolean system, int priority) {
    this.stack = stack;
    this.name = name;
    this.daemon = daemon;
    this.priority = priority;

    contextRegisters   = new Registers();
    exceptionRegisters = new Registers();

    if(VM.VerifyAssertions) VM._assert(stack != null);
    // put self in list of threads known to scheduler and garbage collector
    if (!VM.runningVM) {
      // create primordial thread (in boot image)
      threadSlot = Scheduler.assignThreadSlot(this);
      // Remember the boot thread
      if (VM.VerifyAssertions) VM._assert(bootThread == null);
      bootThread = this;
      this.systemThread = true;
      this.state = State.RUNNABLE;
      // assign final field
      onStackReplacementEvent = null;
    } else {
      // create a normal (ie. non-primordial) thread
      if (trace) Scheduler.trace("Thread create: ", name);
      if (trace) Scheduler.trace("daemon: ", daemon ? "true" : "false");
      if (trace) Scheduler.trace("Thread", "create");
      // set up wrapper Thread if one exists
      this.thread = thread;
      // Set thread type
      this.systemThread = system;

      this.state = State.NEW;

      stackLimit = Magic.objectAsAddress(stack).plus(STACK_SIZE_GUARD);

      // get instructions for method to be executed as thread startoff
      CodeArray instructions = Entrypoints.threadStartoffMethod.getCurrentEntryCodeArray();

      VM.disableGC();

      // initialize thread registers
      Address ip = Magic.objectAsAddress(instructions);
      Address sp = Magic.objectAsAddress(stack).plus(stack.length);

      // Initialize the a thread stack as if "startoff" method had been called
      // by an empty baseline-compiled "sentinel" frame with one local variable.
      Configuration.archHelper.initializeStack(contextRegisters, ip, sp);

      VM.enableGC();

      assignThreadSlot();

      // only do this at runtime because it will call Magic;
      // we set this explicitly for the boot thread as part of booting.
      jniEnv = JNIEnvironment.allocateEnvironment();

      if (VM.BuildForAdaptiveSystem) {
        onStackReplacementEvent = new OSR_OnStackReplacementEvent();
      } else {
        onStackReplacementEvent = null;
      }

      if (thread == null) {
        // create wrapper Thread if doesn't exist
        this.thread = java.lang.JikesRVMSupport.createThread(this, name);
      }
    }
  }

  /**
   * Called during booting to give the boot thread a java.lang.Thread
   */
  @Interruptible
  public final void setupBootThread() {
    thread = java.lang.JikesRVMSupport.createThread(this, "Jikes_RVM_Boot_Thread");
  }

  /**
   * String representation of thread
   */
  @Override
  @Interruptible
  public String toString() {
    return (name == null) ? "Thread-" + getIndex() : name;
  }

  /**
   * Get the current java.lang.Thread.
   */
  @Interruptible
  public final Thread getJavaLangThread() {
    return thread;
  }

  /**
   * Get current thread's JNI environment.
   */
  public final JNIEnvironment getJNIEnv() {
    return jniEnv;
  }

  /** Get the disable GC depth */
  public final int getDisableGCDepth() {
    return disableGCDepth;
  }

  /** Modify the disable GC depth */
  public final void setDisableGCDepth(int d) {
    disableGCDepth = d;
  }

  /** Are allocations allowed by this thread? */
  public final boolean getDisallowAllocationsByThisThread() {
    return disallowAllocationsByThisThread;
  }

  /** Disallow allocations by this thread */
  public final void setDisallowAllocationsByThisThread() {
    disallowAllocationsByThisThread = true;
  }

  /** Allow allocations by this thread */
  public final void clearDisallowAllocationsByThisThread() {
    disallowAllocationsByThisThread = false;
  }

  /**
   * Initialize JNI environment for system threads. Called by VM.finishBooting
   */
  @Interruptible
  public final void initializeJNIEnv() {
    jniEnv = JNIEnvironment.allocateEnvironment();
  }

  /**
   * Indicate whether the stack of this Thread contains any C frame
   * (used in RuntimeEntrypoints.deliverHardwareException for stack resize)
   * @return false during the prolog of the first Java to C transition
   *        true afterward
   */
  public final boolean hasNativeStackFrame() {
    return jniEnv != null && jniEnv.hasNativeStackFrame();
  }

  /**
   * Change the state of the thread and fail if we're not in the expected thread
   * state. This method is logically uninterruptible as we should never be in
   * the wrong state
   *
   * @param oldState the previous thread state
   * @param newState the new thread state
   */
  @LogicallyUninterruptible
  @Entrypoint
  protected final void changeThreadState(State oldState, State newState) {
    if (trace) {
      VM.sysWrite("Thread.changeThreadState: thread=", threadSlot, name);
      VM.sysWrite(" current=", java.lang.JikesRVMSupport.getEnumName(state));
      VM.sysWrite(" old=", java.lang.JikesRVMSupport.getEnumName(oldState));
      VM.sysWriteln(" new=", java.lang.JikesRVMSupport.getEnumName(newState));
    }
    if (state == oldState) {
      state = newState;
    } else {
      throw new IllegalThreadStateException("Illegal thread state change from " +
          oldState + " to " + newState + " when in state " + state + " in thread " + name);
    }
  }

  /*
   * Starting and ending threads
   */

  /**
   * Method to be executed when this thread starts running. Calls
   * java.lang.Thread.run but system threads can override directly.
   */
  @Interruptible
  @Entrypoint
  public synchronized void run() {
    try {
      synchronized(thread) {
        Throwable t = java.lang.JikesRVMSupport.getStillBorn(thread);
        if(t != null) {
          java.lang.JikesRVMSupport.setStillBorn(thread, null);
          throw t;
        }
      }
      thread.run();
    } catch(Throwable t) {
      try {
        Thread.UncaughtExceptionHandler handler;
        handler = thread.getUncaughtExceptionHandler();
        handler.uncaughtException(thread, t);
      } catch(Throwable ignore) {
      }
    }
  }

  /**
   * Begin execution of current thread by calling its "run" method. This method
   * is at the bottom of all created method's stacks.
   */
  @Interruptible
  @SuppressWarnings({"unused", "UnusedDeclaration"})
  // Called by back-door methods.
  private static void startoff() {
    sysCall.sysPthreadSetupSignalHandling();

    RVMThread currentThread = getCurrentThread();

    /* get pthread_id from the operating system and store into RVMThread
       field  */
    currentThread.pthread_id = sysCall.sysPthreadSelf();
    
    currentThread.enableYieldpoints();
    
    sysCall.sysStashVmThreadInPthread(currentThread);

    if (trace) {
      VM.sysWriteln("Thread.startoff(): about to call ", currentThread.toString(), ".run()");
    }

    try {
      currentThread.run();
    } finally {
      if (trace) {
        VM.sysWriteln("Thread.startoff(): finished ", currentThread.toString(), ".run()");
      }
      currentThread.terminate();
      if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
    }
  }

  /**
   * Put this thread on ready queue for subsequent execution on a future
   * timeslice.
   * Assumption: Thread.contextRegisters are ready to pick up execution
   *             ie. return to a yield or begin thread startup code
   */
  public abstract void schedule();
  /**
   * Update internal state of Thread and Scheduler to indicate that
   * a thread is about to start
   */
  protected abstract void registerThreadInternal();

  /**
   * Update internal state of Thread and Scheduler to indicate that
   * a thread is about to start
   */
  public final void registerThread() {
    changeThreadState(State.NEW, State.RUNNABLE);
    registerThreadInternal();
  }

  /**
   * Start execution of 'this' by putting it on the appropriate queue
   * of an unspecified virtual processor.
   */
  @Interruptible
  public final void start() {
    registerThread();
    schedule();
  }

  /**
   * Terminate execution of current thread by abandoning all references to it
   * and resuming execution in some other (ready) thread.
   */
  @Interruptible
  public final void terminate() {
    if (VM.VerifyAssertions) VM._assert(Scheduler.getCurrentThread() == this);
    boolean terminateSystem = false;
    if (trace) Scheduler.trace("Thread", "terminate");
    if (traceTermination) {
      VM.disableGC();
      VM.sysWriteln("[ BEGIN Verbosely dumping stack at time of thread termination");
      Scheduler.dumpStack();
      VM.sysWriteln("END Verbosely dumping stack at time of creating thread termination ]");
      VM.enableGC();
    }

    if (VM.BuildForAdaptiveSystem) {
      RuntimeMeasurements.monitorThreadExit();
    }

    // allow java.lang.Thread.exit() to remove this thread from ThreadGroup
    java.lang.JikesRVMSupport.threadDied(thread);

    if (VM.VerifyAssertions) {
      if (Lock.countLocksHeldByThread(getLockingId()) > 0) {
        VM.sysWriteln("Error, thread terminating holding a lock");
        Scheduler.dumpVirtualMachine();
      }
    }
    // begin critical section
    //
    Scheduler.threadCreationMutex.lock("thread termination");
    Processor.getCurrentProcessor().disableThreadSwitching("disabled for thread termination");

    //
    // if the thread terminated because of an exception, remove
    // the mark from the exception register object, or else the
    // garbage collector will attempt to relocate its ip field.
    exceptionRegisters.inuse = false;

    Scheduler.numActiveThreads -= 1;
    if (daemon) {
      Scheduler.numDaemons -= 1;
    }
    if ((Scheduler.numDaemons == Scheduler.numActiveThreads) &&
        (VM.mainThread != null) &&
        VM.mainThread.launched) {
      // no non-daemon thread remains and the main thread was launched
      terminateSystem = true;
    }
    if (terminateSystem) {
      if (systemShuttingDown == false) {
        systemShuttingDown = true;
      } else {
        terminateSystem = false;
      }
    }
    if (traceTermination) {
      VM.sysWriteln("Thread.terminate: myThread.daemon = ", daemon);
      VM.sysWriteln("  Scheduler.numActiveThreads = ", Scheduler.numActiveThreads);
      VM.sysWriteln("  Scheduler.numDaemons = ", Scheduler.numDaemons);
      VM.sysWriteln("  terminateSystem = ", terminateSystem);
    }
    // end critical section
    //
    Processor.getCurrentProcessor().enableThreadSwitching();
    Scheduler.threadCreationMutex.unlock();

    if (VM.VerifyAssertions) {
      if (VM.fullyBooted || !terminateSystem) {
        Processor.getCurrentProcessor().failIfThreadSwitchingDisabled();
      }
    }
    if (terminateSystem) {
      if (uncaughtExceptionCount > 0)
        /* Use System.exit so that any shutdown hooks are run.  */ {
        if (VM.TraceExceptionDelivery) {
          VM.sysWriteln("Calling sysExit due to uncaught exception.");
        }
        System.exit(VM.EXIT_STATUS_DYING_WITH_UNCAUGHT_EXCEPTION);
      } else if (thread instanceof MainThread) {
        MainThread mt = (MainThread) thread;
        if (!mt.launched) {
          /* Use System.exit so that any shutdown hooks are run.  It is
           * possible that shutdown hooks may be installed by static
           * initializers which were run by classes initialized before we
           * attempted to run the main thread.  (As of this writing, 24
           * January 2005, the Classpath libraries do not do such a thing, but
           * there is no reason why we should not support this.)   This was
           * discussed on jikesrvm-researchers
           * on 23 Jan 2005 and 24 Jan 2005. */
          System.exit(VM.EXIT_STATUS_MAIN_THREAD_COULD_NOT_LAUNCH);
        }
      }
      /* Use System.exit so that any shutdown hooks are run.  */
      System.exit(0);
      if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
    }

    VM.sysWriteln("making joinable...");

    synchronized (this) {
      isJoinable = true;
      notifyAll();
    }

    VM.sysWriteln("killing jnienv...");

    if (jniEnv != null) {
      // warning: this is synchronized!
      JNIEnvironment.deallocateEnvironment(jniEnv);
      jniEnv = null;
    }
    
    VM.sysWriteln("returning cached lock...");

    // returned cached free lock
    if (cachedFreeLock != null) {
      Lock.returnLock(cachedFreeLock);
      cachedFreeLock = null;
    }

    VM.sysWriteln("adding to aboutToTerminate...");

    addAboutToTerminate();

    VM.sysWriteln("acquireCount for my monitor: ",monitor().acquireCount);
    VM.sysWriteln("timer ticks: ",timerTicks);
    VM.sysWriteln("yieldpoints taken: ",yieldpointsTaken);
    VM.sysWriteln("yieldpoints taken fully: ",yieldpointsTakenFully);
    
    VM.sysWriteln("finishing thread termination...");

    finishThreadTermination();
  }
  
  /** Uninterruptible final portion of thread termination. */
  final void finishThreadTermination() {
    sysCall.sysTerminatePthread();
    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

  /*
   * Support for yieldpoints
   */

  /**
   * Yieldpoint taken in prologue.
   */
  @BaselineSaveLSRegisters
  //Save all non-volatile registers in prologue
  @NoOptCompile
  //We should also have a pragma that saves all non-volatiles in opt compiler,
  // OSR_BaselineExecStateExtractor.java, should then restore all non-volatiles before stack replacement
  //todo fix this -- related to SaveVolatile
  @Entrypoint
  public static void yieldpointFromPrologue() {
    Address fp = Magic.getFramePointer();
    yieldpoint(PROLOGUE, fp);
  }

  /**
   * Yieldpoint taken on backedge.
   */
  @BaselineSaveLSRegisters
  //Save all non-volatile registers in prologue
  @NoOptCompile
  // We should also have a pragma that saves all non-volatiles in opt compiler,
  // OSR_BaselineExecStateExtractor.java, should then restore all non-volatiles before stack replacement
  // TODO fix this -- related to SaveVolatile
  @Entrypoint
  public static void yieldpointFromBackedge() {
    Address fp = Magic.getFramePointer();
    yieldpoint(BACKEDGE, fp);
  }

  /**
   * Yieldpoint taken in epilogue.
   */
  @BaselineSaveLSRegisters
  //Save all non-volatile registers in prologue
  @NoOptCompile
  //We should also have a pragma that saves all non-volatiles in opt compiler,
  // OSR_BaselineExecStateExtractor.java, should then restore all non-volatiles before stack replacement
  // TODO fix this -- related to SaveVolatile
  @Entrypoint
  public static void yieldpointFromEpilogue() {
    Address fp = Magic.getFramePointer();
    yieldpoint(EPILOGUE, fp);
  }

  /*
   * Support for suspend/resume
   */

  /**
   * Thread model dependent part of thread suspension
   */
  protected abstract void suspendInternal();

  /**
   * Suspend execution of current thread until it is resumed.
   * Call only if caller has appropriate security clearance.
   */
  @LogicallyUninterruptible
  public final void suspend() {
    Throwable rethrow = null;
    changeThreadState(State.RUNNABLE, State.SUSPENDED);
    try {
      // let go of outer lock
      ObjectModel.genericUnlock(thread);
      suspendInternal();
    } catch (Throwable t) {
      rethrow = t;
    }
    // regain outer lock
    ObjectModel.genericLock(thread);
    if (rethrow != null) {
      RuntimeEntrypoints.athrow(rethrow);
    }
  }

  /**
   * Thread model dependent part of thread resumption
   */
  protected abstract void resumeInternal();

  /**
   * Resume execution of a thread that has been suspended.
   * Call only if caller has appropriate security clearance.
   */
  @Interruptible
  public final void resume() {
    changeThreadState(State.SUSPENDED, State.RUNNABLE);
    if (trace) Scheduler.trace("Thread", "resume() scheduleThread ", getIndex());
    resumeInternal();
  }

  /*
   * OSR support
   */

  /** Suspend the thread pending completion of OSR, unless OSR has already
   * completed. */
  public abstract void osrPark();
  /** Signal completion of OSR activity on this thread.  Resume it if it was
   * already parked, or prevent it from parking if it is about to park. */
  public abstract void osrUnpark();

  /*
   * Sleep support
   */

  /**
   * Thread model dependent sleep
   * @param millis
   * @param ns
   */
  @Interruptible
  protected abstract void sleepInternal(long millis, int ns) throws InterruptedException;
  /**
   * Suspend execution of current thread for specified number of seconds
   * (or fraction).
   */
  @Interruptible
  public static void sleep(long millis, int ns) throws InterruptedException {
    RVMThread myThread = Scheduler.getCurrentThread();
    myThread.changeThreadState(State.RUNNABLE, State.SLEEPING);
    try {
      myThread.sleepInternal(millis, ns);
    } catch (InterruptedException ie) {
      if (myThread.state != State.RUNNABLE)
        myThread.changeThreadState(State.SLEEPING, State.RUNNABLE);
      myThread.clearInterrupted();
      throw(ie);
    }
    myThread.changeThreadState(State.SLEEPING, State.RUNNABLE);
  }

  /*
   * Wait and notify support
   */

  /**
   * Support for Java {@link java.lang.Object#wait()} synchronization primitive.
   *
   * @param o the object synchronized on
   * @param millis the number of milliseconds to wait for notification
   */
  @Interruptible
  protected abstract Throwable waitInternal(Object o, long millis);

  /**
   * Support for Java {@link java.lang.Object#wait()} synchronization primitive.
   *
   * @param o the object synchronized on
   */
  @Interruptible
  protected abstract Throwable waitInternal(Object o);

  /**
   * Support for Java {@link java.lang.Object#wait()} synchronization primitive.
   *
   * @param o the object synchronized on
   */
  @Interruptible
  /* only loses control at expected points -- I think -dave */
  public static void wait(Object o) {
    getCurrentThread().waitImpl(o,false,0);
  }

  /**
   * Support for Java {@link java.lang.Object#wait()} synchronization primitive.
   *
   * @param o the object synchronized on
   * @param millis the number of milliseconds to wait for notification
   */
  @LogicallyUninterruptible
  /* only loses control at expected points -- I think -dave */
  public static void wait(Object o, long millis) {
    long currentNanos = sysCall.sysNanoTime();
    getCurrentThread().waitImpl(o,true,currentNanos+millis*1000*1000);
  }

  /**
   * Support for RTSJ- and pthread-style absolute wait.
   *
   * @param o the object synchronized on
   * @param whenNanos the absolute time in nanoseconds when we should wake up
   */
  @LogicallyUninterruptible
  /* only loses control at expected points -- I think -dave */
  public static void waitAbsoluteNanos(Object o, long whenNanos) {
    getCurrentThread().waitImpl(o,true,whenNanos);
  }

  @LogicallyUninterruptible
  private static void raiseIllegalMonitorStateException(String msg, Object o) {
    throw new IllegalMonitorStateException(msg + o);
  }
  /**
   * Support for Java {@link java.lang.Object#notify()} synchronization primitive.
   *
   * @param o the object synchronized on
   */
  public static void notify(Object o) {
    if (STATS) notifyOperations++;
    Lock l = ObjectModel.getHeavyLock(o, false);
    if (l == null) return;
    if (l.getOwnerId() != getCurrentThread().getLockingId()) {
      raiseIllegalMonitorStateException("notifying", o);
    }
    l.mutex.lock();
    RVMThread toAwaken = l.waiting.dequeue();
    l.mutex.unlock();
    if (toAwaken!=null) {
      toAwaken.monitor().lockedBroadcast();
    }
  }

  /**
   * Support for Java synchronization primitive.
   *
   * @param o the object synchronized on
   * @see java.lang.Object#notifyAll
   */
  public static void notifyAll(Object o) {
    if (STATS) notifyAllOperations++;
    Lock l = ObjectModel.getHeavyLock(o, false);
    if (l == null) return;
    if (l.getOwnerId() != getCurrentThread().getLockingId()) {
      raiseIllegalMonitorStateException("notifyAll", o);
    }
    for (;;) {
      l.mutex.lock();
      RVMThread toAwaken = l.waiting.dequeue();
      l.mutex.unlock();
      if (toAwaken==null) break;
      toAwaken.monitor().lockedBroadcast();
    }
  }

  public void stop(Throwable cause) {
    monitor().lock();
    asyncThrowable=cause;
    takeYieldpoint = 1;
    monitor().broadcast();
    monitor().unlock();
  }

  /*
   * Park and unpark support
   */
  
  @Interruptible
  public final void park(boolean isAbsolute, long time) throws Throwable {
    boolean hasTimeout;
    long whenWakeupNanos;
    hasTimeout=(time!=0);
    if (isAbsolute) {
      whenWakeupNanos=time;
    } else {
      whenWakeupNanos=sysCall.sysNanoTime()+time;
    }
    Throwable throwThis=null;
    monitor().lock();
    waiting = hasTimeout ? Waiting.TIMED_WAITING : Waiting.WAITING;
    while (!parkingPermit &&
	   !hasInterrupt &&
	   asyncThrowable==null &&
	   (!hasTimeout || sysCall.sysNanoTime() < whenWakeupNanos)) {
      if (hasTimeout) {
	monitor().timedWaitAbsoluteNicely(whenWakeupNanos);
      } else {
	monitor().waitNicely();
      }
    }
    waiting = Waiting.RUNNABLE;
    parkingPermit=false;
    if (asyncThrowable!=null) {
      throwThis=asyncThrowable;
      asyncThrowable=null;
    }
    monitor().unlock();
    if (throwThis!=null) {
      throw throwThis;
    }
  }

  @Interruptible
  public final void unpark() {
    monitor().lock();
    parkingPermit=true;
    monitor().broadcast();
    monitor().unlock();
  }

  /**
   * Get this thread's index in {@link threads}[].
   */
  @LogicallyUninterruptible
  public final int getIndex() {
    if (VM.VerifyAssertions) VM._assert((execStatus == TERMINATED) || threadBySlot[threadSlot] == this);
    return threadSlot;
  }

  /**
   * Get this thread's id for use in lock ownership tests.
   * This is just the thread's index as returned by {@link #getIndex()},
   * shifted appropriately so it can be directly used in the ownership tests.
   */
  public final int getLockingId() {
    if (VM.VerifyAssertions) VM._assert(threadBySlot[threadSlot] == this);
    return threadSlot << ThinLockConstants.TL_THREAD_ID_SHIFT;
  }
  
  public final int getThreadSlot() {
    return threadSlot;
  }
  
  @Uninterruptible
  public static class SoftHandshakeVisitor {
    /**
     * Set whatever flags need to be set to signal that the given thread
     * should perform some action when it acknowledges the soft handshake.
     * If not interested in this thread, return false; otherwise return
     * true.  Returning true will cause a soft handshake request to be
     * put through.
     * <p>
     * This method is called with the thread's monitor() held, but while
     * the thread may still be running.  This method is not called on
     * mutators that have indicated that they are about to terminate.
     */
    public boolean checkAndSignal(RVMThread t) { return true; }
    
    /**
     * Called when it is determined that the thread is stuck in native.
     * While this method is being called, the thread cannot return to
     * running Java code.  As such, it is safe to perform actions "on the
     * thread's behalf".
     */
    public void notifyStuckInNative(RVMThread t) {}
  }
  
  @NoCheckStore
  public static int snapshotHandshakeThreads() {
    // figure out which threads to consider
    acctLock.lock(); /* get a consistent view of which threads are live. */
    processAboutToTerminate(); /* community service */

    int numToHandshake=0;
    for (int i=0;i<numThreads;++i) {
      RVMThread t=threads[i];
      if (t!=RVMThread.getCurrentThread() && !t.ignoreHandshakesAndGC()) {
	handshakeThreads[numToHandshake++]=t;
      }
    }
    acctLock.unlock();
    return numToHandshake;
  }

  /** Tell each thread to take a yieldpoint and wait until all of them have
   * done so at least once.  Additionally, call the visitor on each thread
   * when making the yieldpoint request; the purpose of the visitor is to
   * set any additional fields as needed to make specific requests to the
   * threads that yield.  Note that the visitor's <code>visit()</code> method
   * is called with both the thread's monitor held, and the
   * <code>softHandshakeDataLock</code> held.
   * <p>
   * Currently we only use this mechanism for code patch isync requests on
   * PPC, but this mechanism is powerful enough to be used by sliding-views
   * style concurrent GC. */
  @NoCheckStore
  public static void softHandshake(SoftHandshakeVisitor v) {
    handshakeLock.lockNicely(); /* prevent multiple (soft or hard) handshakes
				   from proceeding concurrently */

    int numToHandshake=snapshotHandshakeThreads();
    
    if (VM.VerifyAssertions) VM._assert(softHandshakeLeft==0);

    // in turn, check if each thread needs a handshake, and if so,
    // request one
    for (int i=0;i<numToHandshake;++i) {
      RVMThread t=handshakeThreads[i];
      handshakeThreads[i]=null; // help GC
      t.monitor().lock();
      boolean waitForThisThread=false;
      if (!t.isAboutToTerminate && v.checkAndSignal(t)) {
	// CAS the execStatus field
	t.setBlockedExecStatus();
	
	// Note that at this point if the thread tries to either enter or
	// exit Java code, it will be diverted into either
	// enterNativeBlocked() or checkBlock(), both of which cannot do
	// anything until they acquire the monitor() lock, which we now
	// hold.  Thus, the code below can, at its leisure, examine the
	// thread's state and make its decision about what to do, fully
	// confident that the thread's state is blocked from changing.
	
	if (t.isInJava()) {
	  // the thread is currently executing Java code, so we must ensure
	  // that it either:
	  // 1) takes the next yieldpoint and rendezvous with this soft
	  //    handshake request (see yieldpoint), or
	  // 2) performs the rendezvous when leaving Java code
	  //    (see enterNativeBlocked, checkBlock, and addAboutToTerminate)
	  // either way, we will wait for it to get there before exiting
	  // this call, since the caller expects that after softHandshake()
	  // returns, no thread will be running Java code without having
	  // acknowledged.
	  t.softHandshakeRequested = true;
	  t.takeYieldpoint = 1;
	  waitForThisThread=true;
	} else {
	  // the thread is not in Java code (it may be blocked or it may be
	  // in native), so we don't have to wait for it since it will
	  // do the Right Thing before returning to Java code.  essentially,
	  // the thread cannot go back to running Java without doing whatever
	  // was requested because:
	  // A) we've set the execStatus to blocked, and
	  // B) we're holding its lock.
	  v.notifyStuckInNative(t);
	}
      }
      t.monitor().unlock();

      // NOTE: at this point the thread may already decrement the
      // softHandshakeLeft counter, causing it to potentially go negative.
      // this is unlikely and completely harmless.

      if (waitForThisThread) {
	softHandshakeDataLock.lock();
	softHandshakeLeft++;
	softHandshakeDataLock.unlock();
      }
    }

    // wait for all threads to reach the handshake
    softHandshakeDataLock.lock();
    if (VM.VerifyAssertions) VM._assert(softHandshakeLeft>=0);
    while (softHandshakeLeft>0) {
      // wait and tell the world that we're off in native land.  this way
      // if someone tries to block us at this point (suspend() or GC),
      // they'll know not to wait for us.
      softHandshakeDataLock.waitNicely();
    }
    if (VM.VerifyAssertions) VM._assert(softHandshakeLeft==0);
    softHandshakeDataLock.unlock();

    handshakeLock.unlock();
  }
  
  /**
   * Check and clear the need for a soft handshake rendezvous.
   */
  public final boolean softRendezvousCheckAndClear() {
    boolean result = false;
    monitor().lock();
    if (softHandshakeRequested) {
      softHandshakeRequested = false;
      result = true;
    }
    monitor().unlock();
    return result;
  }
  
  /**
   * Commit the soft handshake rendezvous.
   */
  public final void softRendezvousCommit() {
    softHandshakeDataLock.lock();
    softHandshakeLeft--;
    if (softHandshakeLeft==0) {
      softHandshakeDataLock.broadcast();
    }
    softHandshakeDataLock.unlock();
  }
  
  /** Rendezvous with a soft handshake request.  Can only be called when the
   * thread's monitor is held. */
  public final void softRendezvous() {
    if (softRendezvousCheckAndClear()) softRendezvousCommit();
  }
  
  /** Handle requests that required a soft handshake.  May be called after
   * we acknowledged the soft handshake.  Thus - this is for actions in
   * which it is sufficient for the thread to acknowledge that it plans
   * to act upon the request in the immediate future, rather than that
   * the thread acts upon the request prior to acknowledging. */
  void handleHandshakeRequest() {
    // Process request for code-patch memory sync operation
    if (VM.BuildForPowerPC && codePatchSyncRequested) {
      codePatchSyncRequested = false;
      // Q: Is this sufficient? Ask Steve why we don't need to sync icache/dcache. --dave
      // A: Yes, this is sufficient.  We (Filip and Dave) talked about it and agree that remote processors only need to execute isync.  --Filip
      // make sure not get stale data
      Magic.isync();
    }
    
    if (flushRequested) {
      MM_Interface.flushMutatorContext();
    }
  }
  
  /**
   * Process a taken yieldpoint.
   */
  public static void yieldpoint(int whereFrom, Address yieldpointServiceMethodFP) {
    boolean cbsOverrun = false;
    RVMThread t = getCurrentThread();
    
    t.yieldpointsTaken++;
    
    // If thread is in critical section we can't do anything right now, defer until later
    // we do this without acquiring locks, since part of the point of disabling
    // yieldpoints is to ensure that locks are not "magically" acquired
    // through unexpected yieldpoints.  As well, this makes code running with
    // yieldpoints disabled more predictable.  Note furthermore that the only
    // race here is setting takeYieldpoint to 0.  But this is perfectly safe,
    // since we are guaranteeing that a yieldpoint will run after we emerge from
    // the no-yieldpoints code.  At worst, setting takeYieldpoint to 0 will be
    // lost (because some other thread sets it to non-0), but in that case we'll
    // just come back here and reset it to 0 again.
    if (!t.yieldpointsEnabled()) {
      t.yieldpointRequestPending = true;
      t.takeYieldpoint = 0;
      return;
    }
    
    t.yieldpointsTakenFully++;

    Throwable throwThis = null;
    
    t.monitor().lock();

    int takeYieldpointVal = t.takeYieldpoint;
    if (takeYieldpointVal != 0) {
      t.takeYieldpoint = 0;
      
      // do two things: check if we should be blocking, and act upon
      // handshake requests.  This also has the effect of reasserting that
      // we are in fact IN_JAVA (as opposed to IN_JAVA_TO_BLOCK).
      t.checkBlock();

      // Process timer interrupt event
      if (t.timeSliceExpired != 0) {
	t.timeSliceExpired = 0;

	if (t.yieldForCBSCall || t.yieldForCBSMethod) {
	  /*
	   * CBS Sampling is still active from previous quantum.
	   * Note that fact, but leave all the other CBS parameters alone.
	   */
	  cbsOverrun = true;
	} else {
	  if (VM.CBSCallSamplesPerTick > 0) {
	    t.yieldForCBSCall = true;
	    t.takeYieldpoint = -1;
	    t.firstCBSCallSample++;
	    t.firstCBSCallSample = t.firstCBSCallSample % VM.CBSCallSampleStride;
	    t.countdownCBSCall = t.firstCBSCallSample;
	    t.numCBSCallSamples = VM.CBSCallSamplesPerTick;
	  }

	  if (VM.CBSMethodSamplesPerTick > 0) {
	    t.yieldForCBSMethod = true;
	    t.takeYieldpoint = -1;
	    t.firstCBSMethodSample++;
	    t.firstCBSMethodSample = t.firstCBSMethodSample % VM.CBSMethodSampleStride;
	    t.countdownCBSMethod = t.firstCBSMethodSample;
	    t.numCBSMethodSamples = VM.CBSMethodSamplesPerTick;
	  }
	}

	if (VM.BuildForAdaptiveSystem) {
	  RuntimeMeasurements.takeTimerSample(whereFrom, yieldpointServiceMethodFP);
	}
	if (VM.BuildForAdaptiveSystem) {
	  OSR_Listener.checkForOSRPromotion(whereFrom, yieldpointServiceMethodFP);
	}
      }

      if (t.yieldForCBSCall) {
	if (!(whereFrom == BACKEDGE || whereFrom == OSROPT)) {
	  if (--t.countdownCBSCall <= 0) {
	    if (VM.BuildForAdaptiveSystem) {
	      // take CBS sample
	      RuntimeMeasurements.takeCBSCallSample(whereFrom, yieldpointServiceMethodFP);
	    }
	    t.countdownCBSCall = VM.CBSCallSampleStride;
	    t.numCBSCallSamples--;
	    if (t.numCBSCallSamples <= 0) {
	      t.yieldForCBSCall = false;
	    }
	  }
	}
	if (t.yieldForCBSCall) {
	  t.takeYieldpoint = -1;
	}
      }

      if (t.yieldForCBSMethod) {
	if (--t.countdownCBSMethod <= 0) {
	  if (VM.BuildForAdaptiveSystem) {
	    // take CBS sample
	    RuntimeMeasurements.takeCBSMethodSample(whereFrom, yieldpointServiceMethodFP);
	  }
	  t.countdownCBSMethod = VM.CBSMethodSampleStride;
	  t.numCBSMethodSamples--;
	  if (t.numCBSMethodSamples <= 0) {
	    t.yieldForCBSMethod = false;
	  }
	}
	if (t.yieldForCBSMethod) {
	  t.takeYieldpoint = 1;
	}
      }

      if (VM.BuildForAdaptiveSystem && t.yieldToOSRRequested) {
	t.yieldToOSRRequested = false;
	OSR_Listener.handleOSRFromOpt(yieldpointServiceMethodFP);
      }

      // what is the reason for this?  and what was the reason for doing
      // a thread switch following the suspension in the OSR trigger code?
      // ... it seems that at least part of the point here is that if a
      // thread switch was desired for other reasons, then we need to ensure
      // that between when this runs and when the glue code runs there will
      // be no interleaved GC; obviously if we did this before the thread
      // switch then there would be the possibility of interleaved GC.
      if (VM.BuildForAdaptiveSystem && t.isWaitingForOsr) {
	OSR_PostThreadSwitch.postProcess(t);
      }
      
      if (t.asyncThrowable != null) {
	throwThis=t.asyncThrowable;
	t.asyncThrowable=null;
      }
    }
    t.monitor().unlock();
    
    if (throwThis!=null) {
      throwFromUninterruptible(throwThis);
    }
  }
  
  @UninterruptibleNoWarn
  private static void throwFromUninterruptible(Throwable e) {
    RuntimeEntrypoints.athrow(e);
  }

  /**
   * Change the size of the currently executing thread's stack.
   * @param newSize    new size (in bytes)
   * @param exceptionRegisters register state at which stack overflow trap
   * was encountered (null --> normal method call, not a trap)
   */
  @Interruptible
  public static void resizeCurrentStack(int newSize, Registers exceptionRegisters) {
    if (traceAdjustments) VM.sysWrite("Thread: resizeCurrentStack\n");
    if (MM_Interface.gcInProgress()) {
      VM.sysFail("system error: resizing stack while GC is in progress");
    }
    byte[] newStack = MM_Interface.newStack(newSize, false);
    Processor.getCurrentProcessor().disableThreadSwitching("disabled for stack resizing");
    transferExecutionToNewStack(newStack, exceptionRegisters);
    Processor.getCurrentProcessor().enableThreadSwitching();
    if (traceAdjustments) {
      RVMThread t = Scheduler.getCurrentThread();
      VM.sysWrite("Thread: resized stack ", t.getIndex());
      VM.sysWrite(" to ", t.stack.length / 1024);
      VM.sysWrite("k\n");
    }
  }

  @NoInline
  @BaselineNoRegisters
  //this method does not do a normal return and hence does not execute epilogue --> non-volatiles not restored!
  private static void transferExecutionToNewStack(byte[] newStack, Registers exceptionRegisters) {
    // prevent opt compiler from inlining a method that contains a magic
    // (returnToNewStack) that it does not implement.

    RVMThread myThread = getCurrentThread();
    byte[] myStack = myThread.stack;

    // initialize new stack with live portion of stack we're
    // currently running on
    //
    //  lo-mem                                        hi-mem
    //                           |<---myDepth----|
    //                +----------+---------------+
    //                |   empty  |     live      |
    //                +----------+---------------+
    //                 ^myStack   ^myFP           ^myTop
    //
    //       +-------------------+---------------+
    //       |       empty       |     live      |
    //       +-------------------+---------------+
    //        ^newStack           ^newFP          ^newTop
    //
    Address myTop = Magic.objectAsAddress(myStack).plus(myStack.length);
    Address newTop = Magic.objectAsAddress(newStack).plus(newStack.length);

    Address myFP = Magic.getFramePointer();
    Offset myDepth = myTop.diff(myFP);
    Address newFP = newTop.minus(myDepth);

    // The frame pointer addresses the top of the frame on powerpc and
    // the bottom
    // on intel.  if we copy the stack up to the current
    // frame pointer in here, the
    // copy will miss the header of the intel frame.  Thus we make another
    // call
    // to force the copy.  A more explicit way would be to up to the
    // frame pointer
    // and the header for intel.
    Offset delta = copyStack(newStack);

    // fix up registers and save areas so they refer
    // to "newStack" rather than "myStack"
    //
    if (exceptionRegisters != null) {
      adjustRegisters(exceptionRegisters, delta);
    }
    adjustStack(newStack, newFP, delta);

    // install new stack
    //
    myThread.stack = newStack;
    myThread.stackLimit = Magic.objectAsAddress(newStack).plus(STACK_SIZE_GUARD);

    // return to caller, resuming execution on new stack
    // (original stack now abandoned)
    //
    if (VM.BuildForPowerPC) {
      Magic.returnToNewStack(Magic.getCallerFramePointer(newFP));
    } else if (VM.BuildForIA32) {
      Magic.returnToNewStack(newFP);
    }

    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

  /**
   * This (suspended) thread's stack has been moved.
   * Fixup register and memory references to reflect its new position.
   * @param delta displacement to be applied to all interior references
   */
  public final void fixupMovedStack(Offset delta) {
    if (traceAdjustments) VM.sysWrite("Thread: fixupMovedStack\n");

    if (!contextRegisters.getInnermostFramePointer().isZero()) {
      adjustRegisters(contextRegisters, delta);
    }
    if ((exceptionRegisters.inuse) &&
        (exceptionRegisters.getInnermostFramePointer().NE(Address.zero()))) {
      adjustRegisters(exceptionRegisters, delta);
    }
    if (!contextRegisters.getInnermostFramePointer().isZero()) {
      adjustStack(stack, contextRegisters.getInnermostFramePointer(), delta);
    }
    stackLimit = stackLimit.plus(delta);
  }

  /**
   * A thread's stack has been moved or resized.
   * Adjust registers to reflect new position.
   *
   * @param registers registers to be adjusted
   * @param delta     displacement to be applied
   */
  private static void adjustRegisters(Registers registers, Offset delta) {
    if (traceAdjustments) VM.sysWrite("Thread: adjustRegisters\n");

    // adjust FP
    //
    Address newFP = registers.getInnermostFramePointer().plus(delta);
    Address ip = registers.getInnermostInstructionAddress();
    registers.setInnermost(ip, newFP);
    if (traceAdjustments) {
      VM.sysWrite(" fp=");
      VM.sysWrite(registers.getInnermostFramePointer());
    }

    // additional architecture specific adjustments
    //  (1) frames from all compilers on IA32 need to update ESP
    int compiledMethodId = Magic.getCompiledMethodID(registers.getInnermostFramePointer());
    if (compiledMethodId != INVISIBLE_METHOD_ID) {
      if (VM.BuildForIA32) {
        Configuration.archHelper.adjustESP(registers, delta, traceAdjustments);
      }
      if (traceAdjustments) {
        CompiledMethod compiledMethod = CompiledMethods.getCompiledMethod(compiledMethodId);
        VM.sysWrite(" method=");
        VM.sysWrite(compiledMethod.getMethod());
        VM.sysWrite("\n");
      }
    }
  }

  /**
   * A thread's stack has been moved or resized.
   * Adjust internal pointers to reflect new position.
   *
   * @param stack stack to be adjusted
   * @param fp    pointer to its innermost frame
   * @param delta displacement to be applied to all its interior references
   */
  private static void adjustStack(byte[] stack, Address fp, Offset delta) {
    if (traceAdjustments) VM.sysWrite("Thread: adjustStack\n");

    while (Magic.getCallerFramePointer(fp).NE(STACKFRAME_SENTINEL_FP)) {
      // adjust FP save area
      //
      Magic.setCallerFramePointer(fp, Magic.getCallerFramePointer(fp).plus(delta));
      if (traceAdjustments) {
        VM.sysWrite(" fp=", fp.toWord());
      }

      // advance to next frame
      //
      fp = Magic.getCallerFramePointer(fp);
    }
  }

  /**
   * Initialize a new stack with the live portion of the stack
   * we're currently running on.
   *
   * <pre>
   *  lo-mem                                        hi-mem
   *                           |<---myDepth----|
   *                 +----------+---------------+
   *                 |   empty  |     live      |
   *                 +----------+---------------+
   *                  ^myStack   ^myFP           ^myTop
   *
   *       +-------------------+---------------+
   *       |       empty       |     live      |
   *       +-------------------+---------------+
   *        ^newStack           ^newFP          ^newTop
   *  </pre>
   */
  private static Offset copyStack(byte[] newStack) {
    RVMThread myThread = getCurrentThread();
    byte[] myStack = myThread.stack;

    Address myTop = Magic.objectAsAddress(myStack).plus(myStack.length);
    Address newTop = Magic.objectAsAddress(newStack).plus(newStack.length);
    Address myFP = Magic.getFramePointer();
    Offset myDepth = myTop.diff(myFP);
    Address newFP = newTop.minus(myDepth);

    // before copying, make sure new stack isn't too small
    //
    if (VM.VerifyAssertions) {
      VM._assert(newFP.GE(Magic.objectAsAddress(newStack).plus(STACK_SIZE_GUARD)));
    }

    Memory.memcopy(newFP, myFP, myDepth.toWord().toExtent());

    return newFP.diff(myFP);
  }

  /**
   * Set the "isDaemon" status of this thread.
   * Although a java.lang.Thread can only have setDaemon invoked on it
   * before it is started, Threads can become daemons at any time.
   * Note: making the last non daemon a daemon will terminate the VM.
   *
   * Note: This method might need to be uninterruptible so it is final,
   * which is why it isn't called setDaemon.
   *
   * Public so that java.lang.Thread can use it.
   */
  public final void makeDaemon(boolean on) {
    if (daemon == on) {
      // nothing to do
    } else {
      daemon = on;
      if (state == State.NEW) {
        // thread will start as a daemon
      } else {
        Scheduler.threadCreationMutex.lock("daemon creation mutex");
        Scheduler.numDaemons += on ? 1 : -1;
        Scheduler.threadCreationMutex.unlock();

        if (Scheduler.numDaemons == Scheduler.numActiveThreads) {
          if (VM.TraceThreads) {
            Scheduler.trace("Thread", "last non Daemon demonized");
          }
          VM.sysExit(0);
          if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
        }
      }
    }
  }

  /**
   * Dump information for all threads, via {@link VM#sysWrite(String)}.  Each
   * thread's info is newline-terminated.
   *
   * @param verbosity Ignored.
   */
  public static void dumpAll(int verbosity) {
    for (int i = 0; i < numThreads; i++) {
      RVMThread t = threads[i];
      if (t == null) continue;
      VM.sysWrite("Thread ");
      VM.sysWriteInt(t.threadSlot);
      VM.sysWrite(":  ");
      VM.sysWriteHex(Magic.objectAsAddress(t));
      VM.sysWrite("   ");
      t.dump(verbosity);
      // Compensate for t.dump() not newline-terminating info.
      VM.sysWriteln();
    }
  }

  /** @return The value of {@link #isBootThread} */
  public final boolean isBootThread() {
    return this == bootThread;
  }

  /** @return Is this the MainThread ? */
  private boolean isMainThread() {
    return thread instanceof MainThread;
  }

  /**
   * Is this the GC thread?
   * @return false
   */
  public boolean isGCThread() {
    return false;
  }

  /**
   * Is this the debugger thread?
   * @return false
   */
  public boolean isDebuggerThread() {
    return false;
  }

  /** Is this a system thread? */
  public final boolean isSystemThread() {
    return systemThread;
  }


  /** Returns the value of {@link #daemon}. */
  public final boolean isDaemonThread() {
    return daemon;
  }
  
  /** Should this thread run concurrently with STW GC and ignore
   * handshakes? */
  public boolean ignoreHandshakesAndGC() { return false; }

  /** Is the thread started and not terminated */
  public final boolean isAlive() {
    monitor().lock();
    boolean result =
      execStatus != NEW && execStatus != TERMINATED && !isAboutToTerminate;
    monitor().unlock();
    return result;
  }

  /**
   * Sets the name of the thread
   * @param name the new name for the thread
   * @see java.lang.Thread#setName(String)
   */
  public final void setName(String name) {
    this.name = name;
  }
  /**
   * Gets the name of the thread
   * @see java.lang.Thread#getName()
   */
  public final String getName() {
    return name;
  }

  /**
   * Does the currently running Thread hold the lock on an obj?
   * @param obj the object to check
   * @return whether the thread holds the lock
   * @see java.lang.Thread#holdsLock(Object)
   */
  public final boolean holdsLock(Object obj) {
    RVMThread mine = getCurrentThread();
    return ObjectModel.holdsLock(obj, mine);
  }

  /**
   * Was this thread interrupted?
   * @return whether the thread has been interrupted
   * @see java.lang.Thread#isInterrupted()
   */
  public final boolean isInterrupted() {
    return hasInterrupt;
  }
  /**
   * Clear the interrupted status of this thread
   * @see java.lang.Thread#interrupted()
   */
  public final void clearInterrupted() {
    hasInterrupt = false;
  }
  /**
   * Interrupt this thread
   * @see java.lang.Thread#interrupt()
   */
  @Interruptible
  public final void interrupt() {
    monitor().lock();
    hasInterrupt = true;
    monitor().broadcast();
    monitor().unlock();
  }
  /**
   * Get the priority of the thread
   * @return the thread's priority
   * @see java.lang.Thread#getPriority()
   */
  public final int getPriority() {
    return priority;
  }
  /**
   * Set the priority of the thread
   * @param priority
   * @see java.lang.Thread#getPriority()
   */
  public final void setPriority(int priority) {
    this.priority = priority;
  }
  /**
   * Get the state of the thread in a manner compatible with the Java API
   * @return thread state
   * @see java.lang.Thread#getState()
   */
  @Interruptible
  public final Thread.State getState() {
    switch (state) {
    case NEW:
      return Thread.State.NEW;
    case RUNNABLE:
      return Thread.State.RUNNABLE;
    case BLOCKED:
      return Thread.State.BLOCKED;
    case WAITING:
    case SUSPENDED:
    case OSR_PARKED:
    case JOINING:
    case PARKED:
    case IO_WAITING:
    case PROCESS_WAITING:
      return Thread.State.WAITING;
    case TIMED_WAITING:
    case TIMED_PARK:
    case SLEEPING:
      return Thread.State.TIMED_WAITING;
    case TERMINATED:
      return Thread.State.TERMINATED;
    }
    VM.sysFail("Unknown thread state " + state);
    return null;
  }
  /**
   * Wait for the thread to die or for the timeout to occur
   * @param ms milliseconds to wait
   * @param ns nanoseconds to wait
   */
  @Interruptible
  public final void join(long ms, int ns) throws InterruptedException {
    RVMThread myThread = getCurrentThread();
    if (VM.VerifyAssertions) VM._assert(myThread != this);
    synchronized(this) {
      if (ms == 0 && ns == 0) {
        while (isAlive()) {
          wait(this);
        }
      } else {
        long startNano = Time.nanoTime();
        long whenWakeup = startNano + ms*1000L*1000L + ns;
        if (isAlive()) {
          do {
            waitAbsoluteNanos(this, whenWakeup);
          } while (isAlive() && Time.nanoTime() < whenWakeup);
        }
      }
    }
  }

  /**
   * Count the stack frames of this thread
   */
  @Interruptible
  public final int countStackFrames() {
    if (!isSuspended) {
      throw new IllegalThreadStateException("Thread.countStackFrames called on non-suspended thread");
    }
    throw new UnimplementedError();
  }

  /**
   * @return the length of the stack
   */
  public final int getStackLength() {
    return stack.length;
  }

  /**
   * @return the stack
   */
  public final byte[] getStack() {
    return stack;
  }

  /**
   * @return the thread's exception registers
   */
  public final Registers getExceptionRegisters() {
    return exceptionRegisters;
  }

  /**
   * @return the thread's context registers (saved registers when thread is suspended
   *         by green-thread scheduler).
   */
  public final Registers getContextRegisters() {
    return contextRegisters;
  }

  /** Set the initial attempt. */
  public final void reportCollectionAttempt() {
    collectionAttempt++;
  }

  /** Set the initial attempt. */
  public final int getCollectionAttempt() {
    return collectionAttempt;
  }

  /** Resets the attempts. */
  public final void resetCollectionAttempts() {
    collectionAttempt = 0;
  }

  /** Get the physical allocation failed flag. */
  public final boolean physicalAllocationFailed() {
    return physicalAllocationFailed;
  }

  /** Set the physical allocation failed flag. */
  public final void setPhysicalAllocationFailed() {
    physicalAllocationFailed = true;
  }

  /** Clear the physical allocation failed flag. */
  public final void clearPhysicalAllocationFailed() {
    physicalAllocationFailed = false;
  }

  /** Set the emergency allocation flag. */
  public final void setEmergencyAllocation() {
    emergencyAllocation = true;
  }

  /** Clear the emergency allocation flag. */
  public final void clearEmergencyAllocation() {
    emergencyAllocation = false;
  }

  /** Read the emergency allocation flag. */
  public final boolean emergencyAllocation() {
    return emergencyAllocation;
  }

  /**
   * Returns the outstanding OutOfMemoryError.
   */
  public final OutOfMemoryError getOutOfMemoryError() {
    return outOfMemoryError;
  }
  /**
   * Sets the outstanding OutOfMemoryError.
   */
  public final void setOutOfMemoryError(OutOfMemoryError oome) {
    outOfMemoryError = oome;
  }

  /**
   * Get the thread to use for building stack traces.
   * NB overridden by {@link org.jikesrvm.memorymanagers.mminterface.CollectorThread}
   */
  @Uninterruptible
  public RVMThread getThreadForStackTrace() {
    return this;
  }

  /**
   * Clears the outstanding OutOfMemoryError.
   */
  public final void clearOutOfMemoryError() {
    /*
     * SEE RVM-141
     * To avoid problems in GCTrace configuration, only clear the OOM if it is non-NULL.
     */
    if (outOfMemoryError != null) {
      outOfMemoryError = null;
    }
  }

  @Interruptible
  public final void handleUncaughtException(Throwable exceptionObject) {
    uncaughtExceptionCount++;

    if (exceptionObject instanceof OutOfMemoryError) {
      /* Say allocation from this thread is emergency allocation */
      setEmergencyAllocation();
    }
    handlePossibleRecursiveException();
    VM.enableGC();
    if (thread == null) {
      VM.sysWrite("Exception in the primordial thread \"", toString(), "\" while booting: ");
    } else {
      // This is output like that of the Sun JDK.
      VM.sysWrite("Exception in thread \"", getName(), "\": ");
    }
    if (VM.fullyBooted) {
      exceptionObject.printStackTrace();
    }
    getCurrentThread().terminate();
    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

  /** Handle the case of exception handling triggering new exceptions. */
  private void handlePossibleRecursiveException() {
    if (uncaughtExceptionCount > 1 &&
        uncaughtExceptionCount <=
        VM.maxSystemTroubleRecursionDepth + VM.maxSystemTroubleRecursionDepthBeforeWeStopVMSysWrite) {
      VM.sysWrite("We got an uncaught exception while (recursively) handling ");
      VM.sysWrite(uncaughtExceptionCount - 1);
      VM.sysWrite(" uncaught exception");
      if (uncaughtExceptionCount - 1 != 1) {
        VM.sysWrite("s");
      }
      VM.sysWriteln(".");
    }
    if (uncaughtExceptionCount > VM.maxSystemTroubleRecursionDepth) {
      Scheduler.dumpVirtualMachine();
      VM.dieAbruptlyRecursiveSystemTrouble();
      if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
    }
  }

  /**
   * Dump this thread's identifying information, for debugging, via
   * {@link VM#sysWrite(String)}.
   * We do not use any spacing or newline characters.  Callers are responsible
   * for space-separating or newline-terminating output.
   */
  public void dump() {
    dump(0);
  }

  /**
   * Dump this thread's identifying information, for debugging, via
   * {@link VM#sysWrite(String)}.
   * We pad to a minimum of leftJustify characters. We do not use any spacing
   * characters.  Callers are responsible for space-separating or
   * newline-terminating output.
   *
   * @param leftJustify minium number of characters emitted, with any
   * extra characters being spaces.
   */
  public void dumpWithPadding(int leftJustify) {
    char[] buf = Services.grabDumpBuffer();
    int len = dump(buf);
    VM.sysWrite(buf, len);
    for (int i = leftJustify - len; i > 0; i--) {
      VM.sysWrite(" ");
    }
    Services.releaseDumpBuffer();
  }

  /**
   * Dump this thread's identifying information, for debugging, via
   * {@link VM#sysWrite(String)}.
   * We do not use any spacing or newline characters.  Callers are responsible
   * for space-separating or newline-terminating output.
   *
   *  This function avoids write barriers and allocation.
   *
   * @param verbosity Ignored.
   */
  public void dump(int verbosity) {
    char[] buf = Services.grabDumpBuffer();
    int len = dump(buf);
    VM.sysWrite(buf, len);
    Services.releaseDumpBuffer();
  }

  /**
   *  Dump this thread's info, for debugging.
   *  Copy the info about it into a destination char
   *  array.  We do not use any spacing or newline characters.
   *
   *  This function may be called during GC; it avoids write barriers and
   *  allocation.
   *
   *  For this reason, we do not throw an
   *  <code>IndexOutOfBoundsException</code>.
   *
   * @param dest char array to copy the source info into.
   * @param offset Offset into <code>dest</code> where we start copying
   *
   * @return 1 plus the index of the last character written.  If we were to
   *         write zero characters (which we won't) then we would return
   *         <code>offset</code>.  This is intended to represent the first
   *         unused position in the array <code>dest</code>.  However, it also
   *         serves as a pseudo-overflow check:  It may have the value
   *         <code>dest.length</code>, if the array <code>dest</code> was
   *         completely filled by the call, or it may have a value greater
   *         than <code>dest.length</code>, if the info needs more than
   *         <code>dest.length - offset</code> characters of space.
   *
   *         -1 if <code>offset</code> is negative.
   */
  public int dump(char[] dest, int offset) {
    offset = Services.sprintf(dest, offset, getIndex());   // id
    if (daemon) {
      offset = Services.sprintf(dest, offset, "-daemon");     // daemon thread?
    }
    if (isBootThread()) {
      offset = Services.sprintf(dest, offset, "-Boot");    // Boot (Primordial) thread
    }
    if (isMainThread()) {
      offset = Services.sprintf(dest, offset, "-main");    // Main Thread
    }
    if (isGCThread()) {
      offset = Services.sprintf(dest, offset, "-collector");  // gc thread?
    }
    offset = Services.sprintf(dest, offset, "-");
    offset = Services.sprintf(dest, offset, execStatus);
    offset = Services.sprintf(dest, offset, "-");
    offset = Services.sprintf(dest, offset, java.lang.JikesRVMSupport.getEnumName(waiting));
    if (hasInterrupt || asyncThrowable!=null) {
      offset = Services.sprintf(dest, offset, "-interrupted");
    }
    return offset;
  }

  /**
   *  Dump this thread's info, for debugging.
   *  Copy the info about it into a destination char
   *  array.  We do not use any spacing or newline characters.
   *
   *  This is identical to calling {@link #dump(char[],int)} with an
   *  <code>offset</code> of zero.
   */
  public int dump(char[] dest) {
    return dump(dest, 0);
  }
  /** Dump statistics gather on operations */
  static void dumpStats() {
    VM.sysWrite("FatLocks: ");
    VM.sysWrite(waitOperations);
    VM.sysWrite(" wait operations\n");
    VM.sysWrite("FatLocks: ");
    VM.sysWrite(timedWaitOperations);
    VM.sysWrite(" timed wait operations\n");
    VM.sysWrite("FatLocks: ");
    VM.sysWrite(notifyOperations);
    VM.sysWrite(" notify operations\n");
    VM.sysWrite("FatLocks: ");
    VM.sysWrite(notifyAllOperations);
  }

  /**
   * Print out message in format "[j] (cez#td) who: what", where:
   *    j  = java thread id
   *    z* = RVMThread.getCurrentThread().yieldpointsEnabledCount
   *         (0 means yieldpoints are enabled outside of the call to debug)
   *    t* = numActiveThreads
   *    d* = numActiveDaemons
   *
   * * parenthetical values, printed only if traceDetails = true)
   *
   * We serialize against a mutex to avoid intermingling debug output from multiple threads.
   */
  public static void trace(String who, String what) {
    outputLock.lock();
    VM.sysWrite("[");
    RVMThread t = getCurrentThread();
    t.dump();
    VM.sysWrite("] ");
    if (traceDetails) {
      VM.sysWrite("(");
      VM.sysWriteInt(numActiveDaemons);
      VM.sysWrite("/");
      VM.sysWriteInt(numActiveThreads);
      VM.sysWrite(") ");
    }
    VM.sysWrite(who);
    VM.sysWrite(": ");
    VM.sysWrite(what);
    VM.sysWrite("\n");
    outputLock.unlock();
  }

  /**
   * Print out message in format "p[j] (cez#td) who: what howmany", where:
   *    p  = processor id
   *    j  = java thread id
   *    c* = java thread id of the owner of threadCreationMutex (if any)
   *    e* = java thread id of the owner of threadExecutionMutex (if any)
   *    t* = numActiveThreads
   *    d* = numActiveDaemons
   *
   * * parenthetical values, printed only if traceDetails = true)
   *
   * We serialize against a mutex to avoid intermingling debug output from multiple threads.
   */
  public static void trace(String who, String what, int howmany) {
    _trace(who, what, howmany, false);
  }

  // same as trace, but prints integer value in hex
  //
  public static void traceHex(String who, String what, int howmany) {
    _trace(who, what, howmany, true);
  }

  public static void trace(String who, String what, Address addr) {
    outputLock.lock();
    VM.sysWrite("[");
    getCurrentThread().dump();
    VM.sysWrite("] ");
    if (traceDetails) {
      VM.sysWrite("(");
      VM.sysWriteInt(numActiveDaemons);
      VM.sysWrite("/");
      VM.sysWriteInt(numActiveThreads);
      VM.sysWrite(") ");
    }
    VM.sysWrite(who);
    VM.sysWrite(": ");
    VM.sysWrite(what);
    VM.sysWrite(" ");
    VM.sysWriteHex(addr);
    VM.sysWrite("\n");
    outputLock.unlock();
  }

  private static void _trace(String who, String what, int howmany, boolean hex) {
    outputLock.lock();
    VM.sysWrite("[");
    //VM.sysWriteInt(RVMThread.getCurrentThread().getIndex());
    getCurrentThread().dump();
    VM.sysWrite("] ");
    if (traceDetails) {
      VM.sysWrite("(");
      VM.sysWriteInt(numActiveDaemons);
      VM.sysWrite("/");
      VM.sysWriteInt(numActiveThreads);
      VM.sysWrite(") ");
    }
    VM.sysWrite(who);
    VM.sysWrite(": ");
    VM.sysWrite(what);
    VM.sysWrite(" ");
    if (hex) {
      VM.sysWriteHex(howmany);
    } else {
      VM.sysWriteInt(howmany);
    }
    VM.sysWrite("\n");
    outputLock.unlock();
  }

  /**
   * Print interesting scheduler information, starting with a stack traceback.
   * Note: the system could be in a fragile state when this method
   * is called, so we try to rely on as little runtime functionality
   * as possible (eg. use no bytecodes that require RuntimeEntrypoints support).
   */
  public static void traceback(String message) {
    if (VM.runningVM) {
      outputLock.lock();
    }
    VM.sysWriteln(message);
    tracebackWithoutLock();
    if (VM.runningVM) {
      outputLock.unlock();
    }
  }

  public static void traceback(String message, int number) {
    if (VM.runningVM) {
      outputLock.lock();
    }
    VM.sysWriteln(message, number);
    tracebackWithoutLock();
    if (VM.runningVM) {
      outputLock.unlock();
    }
  }

  static void tracebackWithoutLock() {
    if (VM.runningVM) {
      dumpStack(Magic.getCallerFramePointer(Magic.getFramePointer()));
    } else {
      dumpStack();
    }
  }

  /**
   * Dump stack of calling thread, starting at callers frame
   */
  @LogicallyUninterruptible
  public static void dumpStack() {
    if (VM.runningVM) {
      dumpStack(Magic.getFramePointer());
    } else {
      StackTraceElement[] elements =
        (new Throwable("--traceback from Jikes RVM's RVMThread class--")).getStackTrace();
      for (StackTraceElement element: elements) {
        System.err.println(element.toString());
      }
    }
  }

  /**
   * Dump state of a (stopped) thread's stack.
   * @param fp address of starting frame. first frame output
   *           is the calling frame of passed frame
   */
  public static void dumpStack(Address fp) {
    if (VM.VerifyAssertions) {
      VM._assert(VM.runningVM);
    }

    Address ip = Magic.getReturnAddress(fp);
    fp = Magic.getCallerFramePointer(fp);
    dumpStack(ip, fp);

  }

  /**
   * Dump state of a (stopped) thread's stack.
   * @param ip instruction pointer for first frame to dump
   * @param fp frame pointer for first frame to dump
   */
  public static void dumpStack(Address ip, Address fp) {
    ++inDumpStack;
    if (inDumpStack > 1 &&
        inDumpStack <= VM.maxSystemTroubleRecursionDepth + VM.maxSystemTroubleRecursionDepthBeforeWeStopVMSysWrite) {
      VM.sysWrite("RVMThread.dumpStack(): in a recursive call, ");
      VM.sysWrite(inDumpStack);
      VM.sysWriteln(" deep.");
    }
    if (inDumpStack > VM.maxSystemTroubleRecursionDepth) {
      VM.dieAbruptlyRecursiveSystemTrouble();
      if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
    }

    VM.sysWriteln();
    if (!isAddressValidFramePointer(fp)) {
      VM.sysWrite("Bogus looking frame pointer: ", fp);
      VM.sysWriteln(" not dumping stack");
    } else {
      try {
        VM.sysWriteln("-- Stack --");
        while (Magic.getCallerFramePointer(fp).NE(StackframeLayoutConstants.STACKFRAME_SENTINEL_FP)) {

          // if code is outside of RVM heap, assume it to be native code,
          // skip to next frame
          if (!MM_Interface.addressInVM(ip)) {
            showMethod("native frame", fp);
            ip = Magic.getReturnAddress(fp);
            fp = Magic.getCallerFramePointer(fp);
          } else {

            int compiledMethodId = Magic.getCompiledMethodID(fp);
            if (compiledMethodId == StackframeLayoutConstants.INVISIBLE_METHOD_ID) {
              showMethod("invisible method", fp);
            } else {
              // normal java frame(s)
              CompiledMethod compiledMethod = CompiledMethods.getCompiledMethod(compiledMethodId);
              if (compiledMethod == null) {
                showMethod(compiledMethodId, fp);
              } else if (compiledMethod.getCompilerType() == CompiledMethod.TRAP) {
                showMethod("hardware trap", fp);
              } else {
                RVMMethod method = compiledMethod.getMethod();
                Offset instructionOffset = compiledMethod.getInstructionOffset(ip);
                int lineNumber = compiledMethod.findLineNumberForInstruction(instructionOffset);
                boolean frameShown = false;
                if (VM.BuildForOptCompiler && compiledMethod.getCompilerType() == CompiledMethod.OPT) {
                  OptCompiledMethod optInfo = (OptCompiledMethod) compiledMethod;
                  // Opt stack frames may contain multiple inlined methods.
                  OptMachineCodeMap map = optInfo.getMCMap();
                  int iei = map.getInlineEncodingForMCOffset(instructionOffset);
                  if (iei >= 0) {
                    int[] inlineEncoding = map.inlineEncoding;
                    int bci = map.getBytecodeIndexForMCOffset(instructionOffset);
                    for (; iei >= 0; iei = OptEncodedCallSiteTree.getParent(iei, inlineEncoding)) {
                      int mid = OptEncodedCallSiteTree.getMethodID(iei, inlineEncoding);
                      method = MemberReference.getMemberRef(mid).asMethodReference().getResolvedMember();
                      lineNumber = ((NormalMethod)method).getLineNumberForBCIndex(bci);
                      showMethod(method, lineNumber, fp);
                      if (iei > 0) {
                        bci = OptEncodedCallSiteTree.getByteCodeOffset(iei, inlineEncoding);
                      }
                    }
                    frameShown=true;
                  }
                }
                if(!frameShown) {
                  showMethod(method, lineNumber, fp);
                }
              }
            }
            ip = Magic.getReturnAddress(fp);
            fp = Magic.getCallerFramePointer(fp);
          }
          if (!isAddressValidFramePointer(fp)) {
            VM.sysWrite("Bogus looking frame pointer: ", fp);
            VM.sysWriteln(" end of stack dump");
            break;
          }
        } // end while
      } catch (Throwable t) {
        VM.sysWriteln("Something bad killed the stack dump. The last frame pointer was: ", fp);
      }
    }
    --inDumpStack;
  }

  /**
   * Return true if the supplied address could be a valid frame pointer.
   * To check for validity we make sure the frame pointer is in one of the
   * spaces;
   * <ul>
   *   <li>LOS (For regular threads)</li>
   *   <li>Immortal (For threads allocated in immortal space such as collectors)</li>
   *   <li>Boot (For the boot thread)</li>
   * </ul>
   *
   * <p>or it is {@link StackframeLayoutConstants#STACKFRAME_SENTINEL_FP}.
   * The STACKFRAME_SENTINEL_FP is possible when the thread has been created but has yet to be
   * scheduled.</p>
   *
   * @param address the address.
   * @return true if the address could be a frame pointer, false otherwise.
   */
  private static boolean isAddressValidFramePointer(final Address address) {
    if (address.EQ(Address.zero()))
      return false; // Avoid hitting assertion failure in MMTk
    else
      return address.EQ(StackframeLayoutConstants.STACKFRAME_SENTINEL_FP) ||
             MM_Interface.mightBeFP(address);
  }

  private static void showPrologue(Address fp) {
    VM.sysWrite("   at ");
    if (SHOW_FP_IN_STACK_DUMP) {
      VM.sysWrite("[");
      VM.sysWrite(fp);
      VM.sysWrite(", ");
      VM.sysWrite(Magic.getReturnAddress(fp));
      VM.sysWrite("] ");
    }
  }

  /**
   * Show a method where getCompiledMethod returns null
   *
   * @param compiledMethodId
   * @param fp
   */
  private static void showMethod(int compiledMethodId, Address fp) {
    showPrologue(fp);
    VM.sysWrite("<unprintable normal Java frame: CompiledMethods.getCompiledMethod(",
                compiledMethodId,
                ") returned null>\n");
  }

  /**
   * Show a method that we can't show (ie just a text description of the
   * stack frame
   *
   * @param name
   * @param fp
   */
  private static void showMethod(String name, Address fp) {
    showPrologue(fp);
    VM.sysWrite("<");
    VM.sysWrite(name);
    VM.sysWrite(">\n");
  }

  /**
   * Helper function for {@link #dumpStack(Address,Address)}. Print a stack
   * frame showing the method.
   */
  private static void showMethod(RVMMethod method, int lineNumber, Address fp) {
    showPrologue(fp);
    if (method == null) {
      VM.sysWrite("<unknown method>");
    } else {
      VM.sysWrite(method.getDeclaringClass().getDescriptor());
      VM.sysWrite(" ");
      VM.sysWrite(method.getName());
      VM.sysWrite(method.getDescriptor());
    }
    if (lineNumber > 0) {
      VM.sysWrite(" at line ");
      VM.sysWriteInt(lineNumber);
    }
    VM.sysWrite("\n");
  }

  /**
   * Dump state of a (stopped) thread's stack and exit the virtual machine.
   * @param fp address of starting frame
   * Returned: doesn't return.
   * This method is called from RunBootImage.C when something goes horrifically
   * wrong with exception handling and we want to die with useful diagnostics.
   */
  @Entrypoint
  public static void dumpStackAndDie(Address fp) {
    if (!exitInProgress) {
      // This is the first time I've been called, attempt to exit "cleanly"
      exitInProgress = true;
      dumpStack(fp);
      VM.sysExit(VM.EXIT_STATUS_DUMP_STACK_AND_DIE);
    } else {
      // Another failure occurred while attempting to exit cleanly.
      // Get out quick and dirty to avoid hanging.
      sysCall.sysExit(VM.EXIT_STATUS_RECURSIVELY_SHUTTING_DOWN);
    }
  }
  
  /**
   * Is it safe to start forcing garbage collects for stress testing?
   */
  public static boolean safeToForceGCs() {
    return gcEnabled();
  }

  /**
   * Is it safe to start forcing garbage collects for stress testing?
   */
  public static boolean gcEnabled() {
    return threadingInitialized && getCurrentThread().yieldpointsEnabled();
  }

  /**
   * Set up the initial thread and processors as part of boot image writing
   * @return the boot thread
   */
  @Interruptible
  public static RVMThread setupBootThread() {
    byte[] stack = new byte[ArchConstants.STACK_SIZE_BOOT];
    if (VM.VerifyAssertions) VM._assert(bootThread == null);
    bootThread = new RVMThread(stack, "Jikes_RBoot_Thread");
    numActiveThreads++;
    numActiveDaemons++;
    return bootThread;
  }

  /**
   * Dump state of virtual machine.
   */
  public static void dumpVirtualMachine() {
    getCurrentThread().disableYieldpoints();
    
    VM.sysWrite("\n-- Threads --\n");
    for (int i = 0; i < numThreads; ++i) {
      RVMThread t=threads[i];
      if (t != null) {
        t.dumpWithPadding(30);
        VM.sysWrite("\n");
      }
    }
    VM.sysWrite("\n");

    VM.sysWrite("\n-- Locks in use --\n");
    Lock.dumpLocks();

    VM.sysWriteln("Dumping stack of active thread\n");
    dumpStack();

    VM.sysWriteln("Attempting to dump the stack of all other live threads");
    VM.sysWriteln("This is somewhat risky since if the thread is running we're going to be quite confused");
    for (int i = 0; i < numThreads; ++i) {
      RVMThread thr = threads[i];
      if (thr != null && thr != RVMThread.getCurrentThread() && thr.isAlive()) {
        thr.dump();
        if (thr.contextRegisters != null)
          dumpStack(thr.contextRegisters.getInnermostFramePointer());
      }
    }
    
    getCurrentThread().enableYieldpoints();
  }

}
