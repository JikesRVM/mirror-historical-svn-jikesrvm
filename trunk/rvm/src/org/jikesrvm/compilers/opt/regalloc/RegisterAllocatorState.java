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
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.compilers.opt.ir.Register;
import org.jikesrvm.compilers.opt.regalloc.LinearScan.CompoundInterval;
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
   *  Resets the physical register info
   */
  static void resetPhysicalRegisters(IR ir) {
    PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();
    for (Enumeration<Register> e = phys.enumerateAll(); e.hasMoreElements();) {
      Register reg = e.nextElement();
      reg.defList = null;
      reg.useList = null;
     }
  }
  
  /**
   * Mapping from Interval to Physical register.
   */
  public static  void setIntervalToRegister(Interval i, Register reg) {
    if (VM.VerifyAssertions) VM._assert(i != null && reg != null);
    i.setPhysicalRegister(reg);
  }
  
  /**
   * Get the physical register allocated to Interval i
   */
  public static Register getIntervalToRegister(Interval i) {
    return i.getPhysicalRegister();
  }
  
  /**
   * Check if Interval is assigned a physical register.
   */
  public static boolean isAssignedRegister(Interval i) {
    return (i.getPhysicalRegister() != null);  
  }
  
  /**
   * Remove the allocation of physical register to Interval. 
   * Invoked when Interval is spilled.
   */
  public static  void deallocateInterval(Interval i) {
    i.setPhysicalRegister(null);
  }
     
    
  /**
   * Set the spill location of a physical register.
   * Invoked during scratch register during Spill Code Insertion Phase.
   */
  public static void setSpill(Register reg, int spill) {
    if (reg.isPhysical()) {
      reg.spillRegister();
      reg.scratch = spill;
    }
    else
      /*
      * For symbolic register we have already determined the spill calculation before the
      * scratch register assignment i.e. during LinearScanPhase. This method is invoked during
      * scratch register assignment and this part should not be reachable or you can call
      * interval.spill() to avoid confusion.
      */
     VM._assert(false);
   }

  /**
   * Fetch the spill location assigned to a physical register.
   * If a register is symbolic the get the spill location assigned
   * to the CompoundInterval it represents.
   */
  public static int getSpill(Register reg, Instruction s) {
    return (reg.isPhysical()) ? ((reg.isSpilled()) ? reg.scratch : 0) : reg.getCompoundInterval().getSpill();
  }
  
  /**
   * Check if a physical register is marked for spilling.
   * If it is a symbolic register check for the interval associated with the 
   * symbolic register.
   */
  public static boolean isSpilled(Register reg,Instruction s) {
    return (reg.isPhysical()) ? reg.isSpilled() : reg.getCompoundInterval().isSpilled();
  }
}