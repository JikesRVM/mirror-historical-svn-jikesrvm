package com.ibm.JikesRVM.GenerateInterfaceDeclarations;

import  java.io.PrintStream;

/** A class for shared code among GenerateInterfaceDeclarations et al. */

class Shared {
  /** 
      These routines all handle I/O.  (More below)
   **/

  /** Never instantiated directly; constructor does nothing. . */
  Shared() {}
  static PrintStream out = System.out;
  static String outFileName;

  static void p(String s) {
    out.print(s);
  }
  static void pln(String s) {
    out.println(s);
  }
  static void pln() {
    out.println();
  }
  
  static void ep(String s) {
    System.err.print(s);
  }

  static void epln(String s) {
    System.err.println(s);
  }

  static void epln() {
    System.err.println();
  }

  static void reportTrouble(String msg) {
    reportTrouble(msg, (Exception) null);
  }

  static void reportTrouble(String msg, Throwable e) {
    epln("GenerateInterfaceDeclarations: While we were creating InterfaceDeclarations.h, there was a problem.");
    ep(msg);
    if (e != null) {
      ep(": ");
      ep(e.toString());
    }
    epln();
    if (outFileName != null) {
      ep("The build system (my caller) should delete the output file");
      ep(" ");
      epln(outFileName);
    }
    if (e != null) {
      e.printStackTrace();
    }
    System.exit(1);
  }


  /** 
      Non-IO routines.
   **/
  static ClassLoader altCL = null; // alternate reality class loader
  
  static Class getClassNamed(String cname) {
    if (altCL == null) {
      /* We're not worried about using Jikes RVM or another Classpath-based VM
       * to run GenerateInterfaceDeclarations.   The VM class is in the normal
       * CLASSPATH.  */ 
      try {
        return Class.forName(cname);
      } catch (ClassNotFoundException e) {
        reportTrouble("Unable to load the class \"" + cname + "\""
                      + " with the default (application) class loader:" + e);
        return null;            // unreached
      }
    } else {
      /* using the alternate reality class loader */
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
