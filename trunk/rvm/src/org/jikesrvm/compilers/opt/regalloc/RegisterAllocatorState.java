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
   *  Resets the physical register info
   */
  static void resetPhysicalRegisters(IR ir) {
    PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();
    for (Enumeration<Register> e = phys.enumerateAll(); e.hasMoreElements();) {
      Register reg = e.nextElement();
      reg.deallocateRegister();
      reg.mapsToRegister = null;  // mapping from real to symbolic
      //    putPhysicalRegResurrectList(reg, null);
      reg.defList = null;
      reg.useList = null;
     }
  }

  /**
   * Special use of scratchObject field as "resurrect lists" for real registers
   * TODO: use another field for safety; scratchObject is also used by
   *  clan LinearScanLiveAnalysis
   */
  /*
  static void putPhysicalRegResurrectList(Register r,
                                          LinearScanLiveInterval li) {
    if (VM.VerifyAssertions) VM._assert(r.isPhysical());
    r.scratchObject = li;
  }
  */
  /**
   *
   * Special use of scratchObject field as "resurrect lists" for real registers
   * TODO: use another field for safety; scratchObject is also used by
   *  clan LinearScanLiveAnalysis
   */
  /*
  static LinearScanLiveInterval getPhysicalRegResurrectList(Register r) {
    if (VM.VerifyAssertions) VM._assert(r.isPhysical());
    return (LinearScanLiveInterval) r.scratchObject;
  }
  */  
  
  /**
   * Record that register A and register B are associated with each other
   * in a bijection.
   *
   * The register allocator uses this state to indicate that a symbolic
   * register is presently allocated to a physical register.
   */
  static void mapOneToOne(Register A, Register B) {
    Register aFriend = getMapping(A);
    Register bFriend = getMapping(B);
    if (aFriend != null) {
      aFriend.mapsToRegister = null;
    }
    if (bFriend != null) {
      bFriend.mapsToRegister = null;
    }
    A.mapsToRegister = B;
    B.mapsToRegister = A;
  }

  /**
   * @return the register currently mapped 1-to-1 to r
   */
  static Register getMapping(Register r) {
    return r.mapsToRegister;
  }
 
  /**
   * Clear any 1-to-1 mapping for register R.
   */
  static void clearOneToOne(Register r) {
    if (r != null) {
      Register s = getMapping(r);
      if (s != null) {
        s.mapsToRegister = null;
      }
      r.mapsToRegister = null;
    }
   
  }
}