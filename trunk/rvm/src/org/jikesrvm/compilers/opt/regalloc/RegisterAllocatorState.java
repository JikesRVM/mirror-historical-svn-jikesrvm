/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.compilers.opt.regalloc;

import java.util.Enumeration;

import org.jikesrvm.VM;
import org.jikesrvm.ArchitectureSpecificOpt.PhysicalRegisterSet;
import org.jikesrvm.compilers.opt.ir.IR;
import org.jikesrvm.compilers.opt.ir.Register;
import org.jikesrvm.compilers.opt.regalloc.LinearScan.Interval;
import org.jikesrvm.compilers.opt.regalloc.LinearScan.BasicInterval;

/**
 * The register allocator currently caches a bunch of state in the IR;
 * This class provides accessors to this state.
 * TODO: Consider caching the state in a lookaside structure.
 * TODO: Currently, the physical registers are STATIC! fix this.
 */
public class RegisterAllocatorState {
  
  /**
   * A scratchInterval to be used so as to avoid unnecessary creation of BasicInterval during 
   * RegisterAloocation.
   */
  public static Interval scratchInterval = new BasicInterval(0,0);
  
  /**
   * Register Allocation state of the following IR
   */
  public static IR thisIR = null;
  
  /**
   *  Resets the physical register info
   */
  static void resetPhysicalRegisters(IR ir) {
    PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();
    for (Enumeration<Register> e = phys.enumerateAll(); e.hasMoreElements();) {
      Register reg = e.nextElement();
      reg.deallocateRegister();
      reg.defList = null;
      reg.useList = null;
     }
  }
  
  /**
   * Mapping from Interval to Physical register.
   */
  public static  void setIntervalToRegister(Object i, Register reg) {
    if (VM.VerifyAssertions) VM._assert(i != null);
    thisIR.stackManager.intervalToRegister.put((Interval)i,reg);
  }
  
  /**
   * Get the physical register allocated to Interval i
   */
  public static Register getIntervalToRegister(Object i) {
    return thisIR.stackManager.intervalToRegister.get(i);
  }
  
  /**
   * Check if Interval is present the HashMap indicating that Interval i was allocated 
   * a physical register.
   */
  public static boolean isAssignedRegister(Object i) {
    return thisIR.stackManager.intervalToRegister.containsKey(i);  
  }
  
  /**
   * Remove the Interval form the HashMap intervalToRegister and thus removing the mapping
   * from Interval to physical register.
   */
  public static  void deallocateInterval(Object i) {
    thisIR.stackManager.intervalToRegister.remove(i);
  }
}