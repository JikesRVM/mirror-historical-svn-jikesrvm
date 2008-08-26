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
package org.jikesrvm.debug;

import java.util.StringTokenizer;
import org.jikesrvm.CommandLineArgs;
import org.jikesrvm.VM;
import org.jikesrvm.runtime.ExitStatus;
import org.jikesrvm.compilers.baseline.BaselineCompiler;
import org.jikesrvm.compilers.baseline.BaselineOptions;
import org.jikesrvm.Configuration;

/**
 * JikesRVM specific part of the JDWP implementation. This class processes the
 * JDWP specific command line argument, and loads a JDWP agent.
 */
public class JikesRVMJDWP implements ExitStatus {

  /** Arguments for JDWP. */
  private static String jdwpArgs;

  /** The verbose level. */
  private static int verbose = 0;

  /** Print help message. */
  public static void printHelp() {
    VM.sysWrite("-Xrunjdwp[:help]\t\tPrint usage of the JDWP agent.\n");
    VM.sysWrite("-Xrunjdwp:[<option>=<value>, ...]\t\tConfigure the JDWP agent.\n");
    VM.sysWrite("\n");

    // GNU Classpath JDWP options.
    VM.sysWrite("Option             Default value       Description\n");
    VM.sysWrite("suspend=y|n        y                   Suspend VM before starting application.\n");
    VM.sysWrite("transport=...      none                Name transport. e.g. dt_socket\n");
    VM.sysWrite("server=...         n                   Listens for the debugger\n");
    VM.sysWrite("address=...        none                Transport address for the connection\n");

    //JikesRVM specific options.
    VM.sysWrite("verbose=..         0                   JDWP subsystem verbose level\n");

    VM.sysExit(VM.EXIT_STATUS_PRINTED_HELP_MESSAGE);
  }

  /** Getter method for verbose. */
  public static int getVerbose() {
    return verbose;
  }

  /**
   * Process the JDWP command line argument. The argument has the following form.
   * 
   *   <name>=<value>,<name>=<value>,...,<name>=<value>
   *     
   * @param arg The argument.
   */
  public static void processsCommandLineArg(String arg) {
    if (VM.VerifyAssertions) {
      VM._assert(arg != null);
    }
    StringTokenizer t = new StringTokenizer(arg, ",");
    while(t.hasMoreTokens()) {
      final String nameAndValue = t.nextToken();
      final int i = nameAndValue.indexOf('=');
      if ( i <= 0 || i >= (nameAndValue.length()-1)) {
        VM.sysWriteln("can not correctly parse " + nameAndValue);
        printHelp();
      }
      String name = nameAndValue.substring(0, i);
      String value = nameAndValue.substring(i+1, nameAndValue.length());
      if (VM.VerifyAssertions) {
        VM._assert(name.length() > 0 && value.length() > 0);
      }
      handleJDWPOption(name, value);
    }
    jdwpArgs = arg;
  }

  /**
   * Handle JDWP name and value option for the JikesRVM JDWP implementation.
   * 
   * @param name The name.
   * @param value The value.
   */
  private static void handleJDWPOption(String name, String value) {
    if (name.equals("verbose")) {
      verbose = CommandLineArgs.primitiveParseInt(value);
    }
  }

  /**
   * Load a JDWP agent.
   */
  public static void boot() {
    if (jdwpArgs == null) { return; }
    if (VM.VerifyAssertions) {
      VM._assert(RVMDebug.getRVMDebug() != null,
          "RVMDebug must be initialized before initializing a JDWP agent.");
    }

    // The debugging requires a baseline only compilation and its debugging support
    if (!BaselineCompiler.options.DEBUG) {
      VM.sysWriteln("JDWP agent requires debugging support from the baseline compiler");
      VM.sysWriteln("Please, enable the debugging with -X:base:debug=true");
      VM.sysWriteln("Note that in IA32, you need to disable the basline line edge profiling with -X:base:edge_counters=false");
      VM.sysExit(EXIT_STATUS_JDWP_INITIALIZATION_FAILED);
    }
    if (Configuration.BuildForOptCompiler) {
      VM.sysWriteln("The JikesRVM Debugging does not work with optimizing compiler.");
      VM.sysWriteln("Please, choose a build configuration without the optimizing compiler.");
      VM.sysExit(EXIT_STATUS_JDWP_INITIALIZATION_FAILED);
    }
    
    //dynamically load a JDWP agent.
    try {
      Class.forName("gnu.classpath.jdwp.JikesRVMSupport")
          .getMethod("load", String.class).invoke(null, jdwpArgs);
    }catch (Exception e) {
      VM.sysWriteln("Jdwp initialization failed");
      e.printStackTrace();
      VM.sysExit(EXIT_STATUS_JDWP_INITIALIZATION_FAILED);
    }
  }  
}
