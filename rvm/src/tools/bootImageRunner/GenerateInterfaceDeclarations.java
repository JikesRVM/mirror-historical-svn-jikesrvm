/*
 * (C) Copyright IBM Corp 2001,2002, 2003, 2004
 */
//$Id$

package com.ibm.JikesRVM.GenerateInterfaceDeclarations;


import  java.io.*;
import  java.io.PrintStream;
import  java.util.*;
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

  /** If we use the -alternateRealityClasspath argument, then assume the
     user must be using Jikes RVM to perform self-booting. */
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

    Class vmClass = getTheVMClass(alternateRealityClasspath, 
                                  alternateRealityNativeLibDir);

    vmClass.initForTool();


    if (outFileName == null) {
      out = System.out;
    } else {
      try {
        // We'll let an unhandled exception throw an I/O error for us.
        out = new PrintStream(new FileOutputStream(outFileName));
        //      epln("We don't support the -out argument yet");
        //      System.exit(-1);
      } catch (IOException e) {
        reportTrouble("Caught an exception while opening" + outFileName +" for writing: " + e.toString());
      }
    }

    XXX Here, we need to load an emitters collection too, I think.

    emitStuff(vmClass);

    if (out.checkError()) {
      reportTrouble("an output error happened");
    }
    //    try {
    out.close();              // exception thrown up.
    //    } catch (IOException e) {
    //      reportTrouble("An output error when closing the output: " + e.toString());
    //    }
    System.exit(0);
  }

  static Class getTheVMClass(String alternateRealityClassPath,
                             String alternateRealityNativeLibDir) 
  {
    final String cname = "com.ibm.JikesRVM.VM";
    if (alternateRealityClasspath == null) {
      /* We're not using Jikes RVM to run GenerateInterfaceDeclarations  The
       * VM class is in the normal classpath.  */
      try {
        return Class.forName(cname);
      } catch (ClassNotFoundException e) {
        reportTrouble("Unable to load the class \"" + cname + "\""
                      + " with the default (application) class loader.");
        return null;            // unreached
      }
    } else {
      /* Self-booting with Jikes RVM. */
      ClassLoader altCL 
        = AlternateRealityClassLoader.init(alternateRealityClassPath, 
                                           alternateRealityNativeLibDir);
      try {
        return Class.forName(cname, true, altCL);
      } catch (ClassNotFoundException e) {
        reportTrouble("Unable to load the class \"" + cname + "\"" 
                      + " with the Alternate Reality class loader.");
        return null;            // unreached
      }
    }
  }

}



