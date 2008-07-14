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
package org.jikesrvm.adaptive.util;

import org.jikesrvm.VM;
import org.jikesrvm.adaptive.controller.VM_Controller;
import org.jikesrvm.adaptive.controller.VM_ControllerPlan;
import org.jikesrvm.adaptive.controller.VM_HotMethodEvent;
import org.jikesrvm.classloader.VM_Method;
import org.jikesrvm.classloader.VM_NormalMethod;
import org.jikesrvm.compilers.common.VM_CompiledMethod;
import org.jikesrvm.compilers.opt.driver.CompilationPlan;
import org.jikesrvm.compilers.opt.runtimesupport.VM_OptCompiledMethod;
import org.jikesrvm.scheduler.VM_Processor;
import org.jikesrvm.tuningfork.VM_Engine;

import com.ibm.tuningfork.tracegen.types.EventAttribute;
import com.ibm.tuningfork.tracegen.types.EventType;
import com.ibm.tuningfork.tracegen.types.ScalarType;

/**
 * This class provides logging functionality for the Adaptive Optimization System
 * by generating TuningFork events.
 *
 * The current logging levels are:
 *   0  Do no logging
 *   1  Do minimal logging at startup and VM exit.
 *      If at all possible, do not log anything during program execution.
 *      This logging level is supposed to produce minimal performance pertubation.
 *   2  Log interesting AOS events and controller actions
 *   3  Exhaustively log pretty much everything that is going on
 */
public class VM_AOSLogging {

  /** The singleton instance of the logger */
  public static final VM_AOSLogging logger = new VM_AOSLogging();

  /**
   * Record that the AOS logging has been booted.
   * Needed to allow fast exit from reporting to ensure
   * that when no class is specified to be run but "-help" is specified,
   * don't want null pointer exception to occur!
   */
  private boolean booted = false;

  /**
   * TuningFork TraceEngine
   */
  private VM_Engine engine;

  /**
   * Tracing level (local shadow of VM_Controller.options.LOGGING_LEVEL)
   */
  private int level;

  private EventType compilerSpeedup;
  private EventType compilationRate;
  private EventType methodMapping;
  private EventType methodCompiled;
  private EventType methodSampled;


  /**
   * Return whether AOS logging has booted.
   * @return whether AOS logging has booted
   */
  public boolean booted() {
    return booted;
  }

  /**
   * Called from VM_ControllerThread.run to initialize the logging subsystem
   */
  public void boot() {
    engine = VM_Engine.engine;
    level = VM_Controller.options.LOGGING_LEVEL;

    /* Define properties */
    engine.addProperty("COMPILER_NAME:0", "JNI Compiler");
    engine.addProperty("COMPILER_NAME:1", "Baseline Compiler");
    engine.addProperty("COMPILER_NAME:2", "Optimizing Compiler");

    /* Attributes used across a number of EventTypes */
    EventAttribute compiler = new EventAttribute("compiler", "Numeric id of compiler", ScalarType.INT);
    EventAttribute optLevel = new EventAttribute("opt level", "Optimization level used for this compilation", ScalarType.INT);
    EventAttribute mid = new EventAttribute("mid", "Method Id", ScalarType.INT);
    EventAttribute cmid = new EventAttribute("cmid", "Compiled Method Id", ScalarType.INT);

    /* Define EventTypes */
    compilerSpeedup = engine.defineEvent("Compiler Speedup", "Speedup of given compiler over baseline compiler",
                                         new EventAttribute[] { compiler, optLevel, new EventAttribute("Speedup", "Speedup expected of this compiler", ScalarType.DOUBLE) });
    compilationRate = engine.defineEvent("Compilation Rate", "Compilation time of given compiler compared to baseline compiler",
                                         new EventAttribute[] { compiler, optLevel, new EventAttribute("Rate", "Compilation Rate expected of this compiler", ScalarType.DOUBLE) });
    methodMapping = engine.defineEvent("Method Mapping", "Mapping of method id to source level name of method",
                                       new EventAttribute[] { mid,
                                                              new EventAttribute("class", "Declaring class of the method", ScalarType.STRING),
                                                              new EventAttribute("name", "Method name", ScalarType.STRING),
                                                              new EventAttribute("descriptor", "Method descriptor", ScalarType.STRING) });
    methodCompiled = engine.defineEvent("Method Compiled", "A method has been dynamically compiled",
                                        new EventAttribute[] { mid, cmid, compiler, optLevel });
    methodSampled = engine.defineEvent("Method Sampled", "Method samples have been reported to the controller",
                                       new EventAttribute[] { cmid, new EventAttribute("samples", "Samples attributed to the method", ScalarType.DOUBLE) });


    booted = true;
  }

  ////////////////////////////////////////////////////////////////
  // Logging level 1
  ////////////////////////////////////////////////////////////////

  /**
   * Call this method to dump statistics related to decaying
   * @param decayCount the number of decay events
   */
  public void decayStatistics(int decayCount) {
//    if (!booted) return; // fast exit
//    if (level >= 1) {
//      synchronized (log) {
//        log.print(getTime() + " Decay Organizer Statistics: \n\t" + " Num of Decay events: " + decayCount + "\n");
//      }
//    }
  }

  /**
   * Dumps lots of controller stats to the log file
   */
  public void printControllerStats() {
    if (level >= 1) {
      VM.sysWriteln("TODO: convert printControllerStats to TF");
//      int awoken = VM_ControllerMemory.getNumAwoken();
//      int didNothing = VM_ControllerMemory.getNumDidNothing();
//      int numMethodsConsidered = VM_ControllerMemory.getNumMethodsConsidered();
//      int numMethodsScheduledForRecomp = VM_ControllerMemory.getNumMethodsScheduledForRecomp();
//      int numOpt0 = VM_ControllerMemory.getNumOpt0();
//      int numOpt1 = VM_ControllerMemory.getNumOpt1();
//      int numOpt2 = VM_ControllerMemory.getNumOpt2();
//      int numOpt3 = VM_ControllerMemory.getNumOpt3();
//
//      synchronized (log) {
//        log.print(getTime() +
//                  "\n  Num times Controller thread is awoken: " +
//                  awoken +
//                  "\n  Num times did nothing: " +
//                  didNothing +
//                  " (" +
//                  ((int) ((float) didNothing / (float) awoken * 100)) +
//                  "%)\n  Num methods baseline compiled: " +
//                  VM_ControllerMemory.getNumBase() +
//                  "\n  Num methods considered for recompilation: " +
//                  numMethodsConsidered +
//                  "\n  Num methods chosen to recompile: " +
//                  numMethodsScheduledForRecomp +
//                  " (" +
//                  ((int) ((float) numMethodsScheduledForRecomp / numMethodsConsidered * 100)) +
//                  "%)\n  Opt Levels Chosen: " +
//                  "\n\t Opt Level 0: " +
//                  numOpt0 +
//                  " (" +
//                  ((int) ((float) numOpt0 / numMethodsScheduledForRecomp * 100)) +
//                  "%)\n\t Opt Level 1: " +
//                  numOpt1 +
//                  " (" +
//                  ((int) ((float) numOpt1 / numMethodsScheduledForRecomp * 100)) +
//                  "%)\n" +
//                  "\t Opt Level 2: " +
//                  numOpt2 +
//                  " (" +
//                  ((int) ((float) numOpt2 / numMethodsScheduledForRecomp * 100)) +
//                  "%)\n" +
//                  "\t Opt Level 3: " +
//                  numOpt3 +
//                  " (" +
//                  ((int) ((float) numOpt3 / numMethodsScheduledForRecomp * 100)) +
//                  "%)\n\n");
//
//        // Let the controller memory summarize itself to the log file
//        VM_ControllerMemory.printFinalMethodStats(log);
//      }
      }
  }

  /**
   * This method reports the basic speedup rate for a compiler
   * @param compiler the compiler you are reporting about
   * @param rate the speedup rate
   */
  public void reportSpeedupRate(int compiler, int optLevel, double rate) {
    if (level >= 1) {
      VM_Processor.getCurrentFeedlet().addEvent(compilerSpeedup, compiler, optLevel, rate);
    }
  }

  /**
   * This method reports the basic compilation rate for a compiler
   * @param compiler the compiler you are reporting about
   * @param rate the compilation rate (bytecodes per millisecond)
   */
  public void reportCompilationRate(int compiler, int optLevel, double rate) {
    if (level >= 1) {
      VM_Processor.getCurrentFeedlet().addEvent(compilationRate, compiler, optLevel, rate);
    }
  }

  public void methodCompiled(int compiler, VM_NormalMethod method, VM_CompiledMethod compiledMethod) {
    if (level >= 1) {
      VM_Processor.getCurrentFeedlet().addEvent(methodMapping,
                                                new int[] { method.getId() },
                                                null,
                                                null,
                                                new String[] { method.getDeclaringClass().getClassForType().getName(),
                                                               method.getName().toString(),
                                                               method.getDescriptor().toString()});
      if (compiledMethod instanceof VM_OptCompiledMethod) {
        VM_OptCompiledMethod optMeth = (VM_OptCompiledMethod)compiledMethod;
        VM_Processor.getCurrentFeedlet().addEvent(methodCompiled, method.getId(), compiledMethod.getId(), compiler, optMeth.getOptLevel());
      } else {
        VM_Processor.getCurrentFeedlet().addEvent(methodCompiled, method.getId(), compiledMethod.getId(), compiler, -1);
      }
    }
  }

  ////////////////////////////////////////////////////////////////
  // Logging level 2
  ////////////////////////////////////////////////////////////////

  /**
   * This method logs the scheduling of a recompilation,
   * i.e., it being inserted in the compilation queue.
   * @param plan the Compilation plan being executed.
   * @param priority a number from 0.0 to 1.0 encoding the plan's priority.
   */
  public void recompilationScheduled(CompilationPlan plan, double priority) {
    if (level >= 2) {
//      synchronized (log) {
//        log.println(getTime() + " Scheduling level " + plan.options.getOptLevel() + " recompilation of " + plan
//            .method + " (plan has priority " + priority + ")");
//      }
    }
  }

  /**
   * This method logs the beginning of an adaptively selected recompilation
   * @param plan the Compilation plan being executed.
   */
  public void recompilationStarted(CompilationPlan plan) {
    if (level >= 2) {
//      synchronized (log) {
//        log.println(getTime() + " Recompiling (at level " + plan.options.getOptLevel() + ") " + plan.method);
//      }
    }
  }

  /**
   * This method logs the successful completion of an adaptively
   * selected recompilation
   * @param plan the Compilation plan being executed.
   */
  public void recompilationCompleted(CompilationPlan plan) {
    if (level >= 2) {
//      synchronized (log) {
//        log.println(getTime() + "  Recompiled (at level " + plan.options.getOptLevel() + ") " + plan.method);
//      }
    }
  }

  /**
   * This method logs the abortion of an adaptively selected recompilation
   * @param plan the Compilation plan being executed.
   */
  public void recompilationAborted(CompilationPlan plan) {
//    if (level >= 2) {
//      synchronized (log) {
//        log.println(getTime() + " Failed recompiling (at level " + plan.options.getOptLevel() + " " + plan.method);
//      }
//    }
  }

  /**
   * This method logs the actual compilation time for the given compiled method.
   * @param cm the compiled method
   * @param expectedCompilationTime the model-derived expected compilation time
   */
  public void recordCompileTime(VM_CompiledMethod cm, double expectedCompilationTime) {
    if (level >= 2) {
//      synchronized (log) {
//        double compTime = cm.getCompilationTime();
//        log.println(getTime() +
//                    " Compiled " +
//                    cm.getMethod() +
//                    " with " +
//                    cm.getCompilerName() +
//                    " in " +
//                    compTime +
//                    " ms" +
//                    ", model estimated: " +
//                    expectedCompilationTime +
//                    " ms" +
//                    ", rate: " +
//                    (((VM_NormalMethod) cm.getMethod()).getBytecodeLength() / compTime));
//      }
    }
  }

  /**
   * this method logs the event when the controller discovers a method that has
   * been recompiled and the previous version is still regarded as hot,
   * i.e., still on the stack and signficant.
   */
  public void oldVersionStillHot(VM_HotMethodEvent hme) {
    if (level >= 2) {
//      synchronized (log) {
//        log.println(getTime() + " Found a method with an old version still hot " + hme);
//      }
    }
  }

  /**
   * This method logs when the decay organizer runs.
   */
  public void decayingCounters() {
    if (level >= 2) {
//      synchronized (log) {
//        log.println(getTime() + " Decaying clock and decayable objects");
//      }
    }
  }

  /**
   * This Method logs when the organizer thread has reached its
   * sampling threshold
   */
  public void organizerThresholdReached() {
    if (level >= 2) {
//      synchronized (log) {
//        log.println(getTime() + " OrganizerThread reached sample size threshold\n");
//      }
    }
  }

  /**
   * This method logs that the controller is notified of a
   * candidate to be recompiled due to hotness;
   * i.e., the method has been inserted in the controller queue.
   * @param hotMethod   method to be recompiled, and
   * @param numSamples  number of samples attributed to the method
   */
  public void controllerNotifiedForHotness(VM_CompiledMethod hotMethod, double numSamples) {
    if (level >= 2) {
      VM_Processor.getCurrentFeedlet().addEvent(methodSampled, hotMethod.getId(), numSamples);
    }
  }

  ////////////////////////////////////////////////////////////////
  // Logging level 3
  ////////////////////////////////////////////////////////////////

  /**
   * This method logs a controller cost estimate for doing nothing.
   * @param method the method of interest
   * @param optLevel the opt level being estimated, -1 = baseline
   * @param cost  the computed cost for this method and level
   */
  public void recordControllerEstimateCostDoNothing(VM_Method method, int optLevel, double cost) {
    if (level >= 3) {
//      synchronized (log) {
//        log.print(getTime() + "  Estimated cost of doing nothing (leaving at ");
//        if (optLevel == -1) {
//          log.print("baseline");
//        } else {
//          log.print("O" + optLevel);
//        }
//        log.println(") to " + method + " is " + cost);
//      }
    }
  }

  /**
   * This method logs a controller cost estimate.
   * @param method the method of interest
   * @param choiceDesc a String describing the choice point
   * @param compilationTime the computed compilation cost for this method and level
   * @param futureTime the computed future time, including cost and execution
   */
  public void recordControllerEstimateCostOpt(VM_Method method, String choiceDesc, double compilationTime,
                                              double futureTime) {
    if (level >= 3) {
//      synchronized (log) {
//        log.println(getTime() +
//                    "  Estimated cost of OPT compiling " +
//                    method +
//                    " at " +
//                    choiceDesc +
//                    " is " +
//                    compilationTime +
//                    ", total future time is " +
//                    futureTime);
//      }
    }
  }

  /**
   * Records lots of details about the online computation of a compilation rate
   * @param compiler compiler of interest
   * @param method the method
   * @param BCLength the number of bytecodes
   * @param totalBCLength cumulative number of bytecodes
   * @param MCLength size of machine code
   * @param totalMCLength cumulative size of machine code
   * @param compTime compilation time for this method
   * @param totalCompTime cumulative compilation time for this method
   * @param totalLogOfRates running sum of the natural logs of the rates
   * @param totalLogValueMethods number of methods used in the log of rates
   * @param totalMethods total number of methods
   */
  public void recordUpdatedCompilationRates(byte compiler, VM_Method method, int BCLength, int totalBCLength,
                                            int MCLength, int totalMCLength, double compTime,
                                            double totalCompTime, double totalLogOfRates,
                                            int totalLogValueMethods, int totalMethods) {

    if (level >= 3) {
//      synchronized (log) {
//        boolean backBranch = false;
//        if (method instanceof VM_NormalMethod) {
//          backBranch = ((VM_NormalMethod) method).hasBackwardsBranch();
//        }
//        log.println(getTime() +
//                    "  Updated compilation rates for " +
//                    VM_RuntimeCompiler.getCompilerName(compiler) +
//                    "compiler");
//        log.println("\tmethod compiled: " + method);
//        log.println("\tbyte code length: " + BCLength + ", Total: " + totalBCLength);
//        log.println("\tmachine code length: " + MCLength + ", Total: " + totalMCLength);
//        log.println("\tbackwards branch: " + (backBranch ? "yes" : "no"));
//        log.println("\tcompilation time: " + compTime + ", Total: " + totalCompTime);
//        log.println("\tRate for this method: " + BCLength / compTime + ", Total of Logs: " + totalLogOfRates);
//        log.println("\tTotal Methods: " + totalMethods);
//        log.println("\tNew Rate: " + Math.exp(totalLogOfRates / totalLogValueMethods));
//      }
    }
  }

  public void compileAllMethodsCompleted() {
    if (level >= 2) {
//      synchronized (log) {
//        log.println(VM_Controller.controllerClock + "  Compiled all methods finished. ");
//      }
    }
  }

  ////////////////////////////////////////////////////////////////
  // OSR-related code
  ////////////////////////////////////////////////////////////////

  /**
   * This method logs the successful completion of an adaptively
   * selected recompilation
   * @param plan the Compilation plan being executed.
   */
  public void recordOSRRecompilationDecision(VM_ControllerPlan plan) {
    CompilationPlan cplan = plan.getCompPlan();
    if (level >= 1) {
//      synchronized (log) {
//        log.println(getTime() + " recompile with OSR " + "( at level " + cplan.options.getOptLevel() + " ) " + cplan
//            .method);
//      }
    }
  }

  public void onStackReplacementStarted(CompilationPlan plan) {
    if (level >= 1) {
//      synchronized (log) {
//        log.println(getTime() + " OSR starts " + "( at level " + plan.options.getOptLevel() + " ) " + plan.method);
//      }
    }
  }

  public void onStackReplacementCompleted(CompilationPlan plan) {
    if (level >= 1) {
//      synchronized (log) {
//        log.println(getTime() + " OSR ends " + "( at level " + plan.options.getOptLevel() + " ) " + plan.method);
//      }
    }
  }

  public void onStackReplacementAborted(CompilationPlan plan) {
    if (level >= 1) {
//      synchronized (log) {
//        log.println(getTime() + " OSR failed " + "( at level " + plan.options.getOptLevel() + " ) " + plan.method);
//      }
    }
  }

  public void logOsrEvent(String s) {
    if (level >= 1) {
//      synchronized (log) {
//        log.println(getTime() + " " + s);
//      }
    }
  }

  public void deOptimizationStarted() {
    if (level >= 1) {
//      synchronized (log) {
//        log.println(getTime() + " Deoptimization starts ");
//      }
      }
  }

  public void deOptimizationCompleted() {
    if (level >= 1) {
//      synchronized (log) {
//        log.println(getTime() + " Deoptimization ends.");
//      }
    }
  }

  public void deOptimizationAborted() {
    if (level >= 1) {
//      synchronized (log) {
//        log.println(getTime() + " Deoptimization aborted.");
//      }
    }
  }

  public void debug(String s) {
    if (level >= 2) {
//      synchronized (log) {
//        log.println(getTime() + s);
//      }
    }
  }

  public void onstackreplacementStarted(CompilationPlan plan) {
//    synchronized (log) {
//      log.println(getTime() + " OSR starts " + "( at level " + plan.options.getOptLevel() + " ) " + plan.method);
//    }
  }

  public void onstackreplacementCompleted(CompilationPlan plan) {
//    synchronized (log) {
//      log.println(getTime() + " OSR ends " + "( at level " + plan.options.getOptLevel() + " ) " + plan.method);
//    }
  }

  public void onstackreplacementAborted(CompilationPlan plan) {
//    synchronized (log) {
//      log.println(getTime() + " OSR failed " + "( at level " + plan.options.getOptLevel() + " ) " + plan.method);
//    }
  }
}
