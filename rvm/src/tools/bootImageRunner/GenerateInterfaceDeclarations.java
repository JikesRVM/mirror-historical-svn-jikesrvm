/*
 * (C) Copyright IBM Corp 2001,2002, 2003, 2004
 */
//$Id$

// package com.ibm.JikesRVM.GenerateInterfaceDeclarations;


import  java.io.*;
import  java.io.PrintStream;
import  java.util.*;
import java.lang.reflect.*;
import com.ibm.JikesRVM.*;
import com.ibm.JikesRVM.classloader.*;
import com.ibm.JikesRVM.classloader.AlternateRealityClassLoader;

/**
 * Emit a header file containing declarations required to access VM 
 * data structures from C++.
 * Posix version: AIX PPC, Linux PPC, Linux IA32
 *
 * @author Derek Lieber
 * @modified Steven Augart -- added the "-out" command-line argument.
 */
class GenerateInterfaceDeclarations extends Shared {

  /** Never instantiate. */
  private GenerateInterfaceDeclarations() {
  }

  static int bootImageAddress = 0;

  /** We don't just use the -alternateRealityClasspath argument when
     self-hosting (with Jikes RVM as the VM running this program). 

     Consider, for example, building a Jikes RVM using classpath 0.07 with an
     older Kaffe running classpath 0.06.  We want the GNU Classpath version to
     be written out as 0.07, not as 0.06.  */

  static String alternateRealityClasspath;

  /** If we use the -alternateRealityNativeLibDir argument, then assume the
     user must be using Jikes RVM to perform self-booting.   (We don't
     need it, though.) */
  static String alternateRealityNativeLibDir;

  public static void main (String args[]) throws Exception {
    // Process command line directives.
    //
    for (int i = 0, n = args.length; i < n; ++i) {

      if (args[i].equals("-ia")) {              // image address
        if (++i == args.length) {
          epln("Error: The -ia flag requires an argument");
          System.exit(-1);
        }
        bootImageAddress = Integer.decode(args[i]).intValue();
        continue;
      }

      if (args[i].equals("-out")) {              // output file
        if (++i == args.length) {
          epln("Error: The -out flag requires an argument");
          System.exit(-1);
        }
        outFileName = args[i];
        continue;
      }

      if (args[i].equals("-alternateRealityClasspath")) {
        if (++i == args.length) {
          epln("Error: The -alternateRealityClasspath flag requires an argument");
          System.exit(-1);
        }
        alternateRealityClasspath = args[i];
        continue;
      }

      if (args[i].equals("-alternateRealityNativeLibDir")) {
        if (++i == args.length) {
          epln("Error: The -alternateRealityNativeLibDir flag requires an argument");
          System.exit(-1);
        }
        alternateRealityNativeLibDir = args[i];
        continue;
      }
      epln("Error: unrecognized command line argument: " + args[i]);
      System.exit(-1);
    }

    if (bootImageAddress == 0) {
      epln("Error: Must specify boot image load address.");
      System.exit(-1);
    }

    /* Load and initialize the VM class first.  This should help us out.
     * There is an alternate reality class loader built into Jikes RVM.  We
     * can use that, OR we can use one that is included with these sources if
     * we want to. */
    Shared.altCL 
      = AlternateRealityClassLoader.init(alternateRealityClasspath, 
                                         alternateRealityNativeLibDir);

    Class vmClass = initializeVM();

    if (outFileName == null) {
      out = System.out;
    } else {
      try {
        out = new PrintStream(new FileOutputStream(outFileName));
        //      epln("We don't support the -out argument yet");
        //      System.exit(-1);
      } catch (IOException e) {
        reportTrouble("Caught an exception while opening" + outFileName +" for writing: " + e.toString());
      }
    }

    new Emitters(vmClass).emitStuff(bootImageAddress);

    if (out.checkError()) {
      reportTrouble("an output error happened");
    }
    if (out != System.out) {
      //    try {
      out.close();              // Let the exception be thrown up.
      //    } catch (IOException e) {
      //      reportTrouble("An error when closing " + outFileName + ": " 
      //          + e.toString());
      //    }
    }
    System.exit(0);
  }

    
  static Class initializeVM() 
    throws InvocationTargetException, IllegalAccessException
  {
    Class vmClass = getClassNamed("com.ibm.JikesRVM.VM");

    Method initVMForTool = null; // really dumb, but shuts up warnings.
    try {
      initVMForTool = vmClass.getMethod("initForTool", new Class[0]);
    } catch (NoSuchMethodException e) {
      reportTrouble("The VM Class doesn't have a zero-parameter initForTool() method?!: " + e.toString());
      // unreached
    }
    initVMForTool.invoke(vmClass, new Object[0]);
    return vmClass;
  }
}



