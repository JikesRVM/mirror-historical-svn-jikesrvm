/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$
package com.ibm.JikesRVM;

import com.ibm.JikesRVM.classloader.VM_NativeMethod;
import com.ibm.JikesRVM.classloader.VM_NormalMethod;

/**
 * A place to put code common to all runtime compilers.
 * This includes instrumentation code to get equivalent data for
 * each of the runtime compilers.
 * <p>
 * We collect the following data for each compiler
 * <ol>
 * <li>
 *   total number of methods complied by the compiler
 * <li>
 *   total compilation time in nanoseconds as computed by calls to VM_Time.now()
 *   This is accumulated as a double to avoid rounding errors. 
 * <li>
 *   total number of bytes of bytecodes compiled by the compiler
 *   (under the assumption that there is no padding in the bytecode
 *   array and thus VM_Method.getBytecodes().length is the number bytes
 *   of bytecode for a method)
 * <li>
 *   total number of machine code insructions generated by the compiler
 *   (under the assumption that there is no (excessive) padding in the
 *   machine code array and thus VM_Method.getInstructions().length
 *   is a close enough approximation of the number of machinecodes generated)
 * </ol>
 *   Note that even if 3. & 4. are inflated due to padding, the numbers will 
 *   still be an accurate measure of the space costs of the compile-only 
 *   approach.
 *
 * @author Dave Grove
 * @author Mike Hind
 */
public class VM_RuntimeCompilerInfrastructure
  implements VM_Constants, VM_Callbacks.ExitMonitor {

  // Use these to encode the compiler for record()
  public static final byte JNI_COMPILER      = 0;
  public static final byte BASELINE_COMPILER = 1;
  public static final byte OPT_COMPILER      = 2;

  // Data accumulators
  private static final String name[]         = {"JNI\t","Base\t","Opt\t"};   // Output names
  private static int total_methods[]         = {0,0,0};                // (1)
  private static double total_time[]         = {0.0, 0.0,0.0};         // (2)
  private static int total_bcodeLen[]        = {0, 0,0};               // (3)
  private static int total_mcodeLen[]        = {0, 0,0};               // (4)

  /**
   * To be called when the VM is about to exit.
   * @param value the exit value
   */
  public void notifyExit(int value) {
    report(false);
  }

  /**
   * This method records the time and sizes (bytecode and machine code) for
   * a compilation
   * @param compiler the compiler used
   * @param method the resulting VM_Method
   * @param compiledMethod the resulting compiled method
   * @param timer the timer hold the time used for compilation
   */
  public static void record(byte compiler, VM_NormalMethod method, 
			    VM_CompiledMethod compiledMethod) {
    total_methods[compiler]++;
    total_bcodeLen[compiler] += method.getBytecodeLength();
    total_mcodeLen[compiler] += compiledMethod.getInstructions().length;
    total_time[compiler] += compiledMethod.getCompilationTime();
  }

  /**
   * This method records the time and sizes (bytecode and machine code) for
   * a compilation
   * @param compiler the compiler used
   * @param method the resulting VM_Method
   * @param compiledMethod the resulting compiled method
   * @param timer the timer hold the time used for compilation
   */
  public static void record(byte compiler, VM_NativeMethod method, 
			    VM_CompiledMethod compiledMethod) {
    total_methods[compiler]++;
    total_bcodeLen[compiler] += 1; // lie!
    total_mcodeLen[compiler] += compiledMethod.getInstructions().length;
    total_time[compiler] += compiledMethod.getCompilationTime();
  }

  /**
   * This method produces a summary report of compilation activities
   * @param explain Explains the metrics used in the report
   */
  public static void report (boolean explain) { 
    VM.sysWrite("\n\t\tCompilation Subsystem Report\n");
    VM.sysWrite("Comp\t#Meths\tTime\tbcb/ms\tmcb/bcb\tMCKB\tBCKB\n");
    for (int i=JNI_COMPILER; i<=OPT_COMPILER; i++) {
      if (total_methods[i]>0) {
	VM.sysWrite(name[i]);
	// Number of methods
	VM.sysWrite(total_methods[i]);
	VM.sysWrite("\t");
	// Compilation time
	VM.sysWrite(VM_Time.toSecs(total_time[i]));
	VM.sysWrite("\t");
	// Bytecode bytes per millisecond
	VM.sysWrite((double)total_bcodeLen[i]/total_time[i], 2);
	VM.sysWrite("\t");
	// Ratio of machine code bytes to bytecode bytes
	VM.sysWrite((double)(total_mcodeLen[i] << LG_INSTRUCTION_WIDTH)/(double)total_bcodeLen[i], 2);
	VM.sysWrite("\t");
	// Generated machine code Kbytes
	VM.sysWrite((double)(total_mcodeLen[i] << LG_INSTRUCTION_WIDTH)/1024, 1);
	VM.sysWrite("\t");
	// Compiled bytecode Kbytes
	VM.sysWrite((double)total_bcodeLen[i]/1024, 1); 
	VM.sysWrite("\n");
      }
    }
    if (explain) {
      // Generate an explanation of the metrics reported
      VM.sysWrite("\t\t\tExplanation of Metrics\n");
      VM.sysWrite("#Meths:\t\tTotal number of methods compiled by the compiler\n");
      VM.sysWrite("Time:\t\tTotal compilation time in milliseconds\n");
      VM.sysWrite("bcb/ms:\t\tNumber of bytecode bytes complied per millisecond\n");
      VM.sysWrite("mcb/bcb:\tRatio of machine code bytes to bytecode bytes\n");
      VM.sysWrite("MCKB:\t\tTotal number of machine code bytes generated in kilobytes\n");
      VM.sysWrite("BCKB:\t\tTotal number of bytecode bytes compiled in kilobytes\n");
    }
    VM_RuntimeCompiler.detailedCompilationReport(explain);
  }
   
  /**
   * Return the current estimate of basline-compiler rate, in bcb/sec
   */
  public static double getBaselineRate() {
    double bytes = (double) total_bcodeLen[BASELINE_COMPILER];
    double time = VM_Time.toSecs(total_time[BASELINE_COMPILER]);
    return bytes/time;
  }

  /**
   * This method will compile the passed method using the baseline compiler.
   * @param method the method to compile
   */
  public static VM_CompiledMethod baselineCompile(VM_NormalMethod method) {
    VM_Callbacks.notifyMethodCompile(method, VM_CompiledMethod.BASELINE);
    double start = 0;
    if (VM.BuildForAdaptiveSystem) {
      double now = VM_Time.now();
      start = updateStartAndTotalTimes(now);
    }

    VM_CompiledMethod cm = VM_BaselineCompiler.compile(method);

    if (VM.BuildForAdaptiveSystem) {
      double now = VM_Time.now();
      double end = updateStartAndTotalTimes(now);
      double compileTime = (end - start) * 1000; // convert to milliseconds
      cm.setCompilationTime(compileTime);
      record(BASELINE_COMPILER, method, cm);
    }
    
    return cm;
  }

  /**
   * This method will compile the passed method using the baseline compiler.
   * @param method the method to compile
   */
  public static VM_CompiledMethod jniCompile(VM_NativeMethod method) {
    VM_Callbacks.notifyMethodCompile(method, VM_CompiledMethod.JNI);
    double start = 0;
    if (VM.BuildForAdaptiveSystem) {
      double now = VM_Time.now();
      start = updateStartAndTotalTimes(now);
    }

    VM_CompiledMethod cm = VM_JNICompiler.compile(method);
    if (VM.verboseJNI) {
      VM.sysWriteln("[Dynamic-linking native method " + 
		    method.getDeclaringClass() + "." + method.getName() + 
		    " ... JNI]");
    }

    if (VM.BuildForAdaptiveSystem) {
      double now = VM_Time.now();
      double end = updateStartAndTotalTimes(now);
      double compileTime = (end - start) * 1000; // convert to milliseconds
      cm.setCompilationTime(compileTime);
      record(JNI_COMPILER, method, cm);
    }
    
    return cm;
  }

  /**
   * Support for timing compilation accurately by accumulating CPU on the Java thread.
   */
  protected static double updateStartAndTotalTimes(double now) {
    VM_Thread t = VM_Thread.getCurrentThread();
    t.setCPUTotalTime(t.getCPUTotalTime() + (now - t.getCPUStartTime()));
    t.setCPUStartTime(now);
    return t.getCPUTotalTime();
  }

}
