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

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.jikesrvm.ArchitectureSpecificOpt.PhysicalRegisterSet;
import org.jikesrvm.VM;
import org.jikesrvm.compilers.opt.ir.BasicBlock;
import org.jikesrvm.compilers.opt.ir.IR;
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.compilers.opt.ir.InstructionEnumeration;
import static org.jikesrvm.compilers.opt.ir.Operators.CALL_SAVE_VOLATILE;
import static org.jikesrvm.compilers.opt.ir.Operators.YIELDPOINT_OSR;
import org.jikesrvm.compilers.opt.ir.Register;
import org.jikesrvm.compilers.opt.regalloc.LinearScan.Interval;
import org.jikesrvm.compilers.opt.util.BitSet;

/**
 * An instance of this class provides a mapping from symbolic register to
 * a set of restricted registers.
 *
 * Each architcture will subclass this in a class
 * RegisterRestrictions.
 */
public abstract class GenericRegisterRestrictions {
  // for each symbolic register, the set of physical registers that are
  // illegal for assignment
  private final HashMap<Register, RestrictedRegisterSet> hash = new HashMap<Register, RestrictedRegisterSet>();
  // a set of symbolic registers that must not be spilled.
  private final HashSet<Register> noSpill = new HashSet<Register>();
  protected final PhysicalRegisterSet phys;
  private final HashMap<Interval,RestrictedRegisterSet> intervalHash = new HashMap<Interval,RestrictedRegisterSet>();
  private final HashSet<Interval> intervalNoSpill = new HashSet<Interval>();
    
  /**
   * Default Constructor
   */
  protected GenericRegisterRestrictions(PhysicalRegisterSet phys) {
    this.phys = phys;
  }

  /**
   * Record that the register allocator must not spill a symbolic
   * register.
   */
  protected final void noteMustNotSpill(Register r) {
    noSpill.add(r);
  }
  protected final void noteMustNotSpill(Interval I) {
	    intervalNoSpill.add(I);
	  }

  /**
   * Is spilling a register forbidden?
   */
  public final boolean mustNotSpill(Register r) {
    return noSpill.contains(r);
  }
  public final boolean mustNotSpill(Interval I) {
	    return intervalNoSpill.contains(I);
	  }
  /**
   * Record all the register restrictions dictated by an IR.
   *
   * PRECONDITION: the instructions in each basic block are numbered in
   * increasing order before calling this.  The number for each
   * instruction is stored in its <code>scratch</code> field.
   */
  public final void init(IR ir) {
    // process each basic block
    for (Enumeration<BasicBlock> e = ir.getBasicBlocks(); e.hasMoreElements();) {
      BasicBlock b = e.nextElement();
      processBlock(b);
    }
  }
  /*
public void iterateOverSet(){
	Iterator<Interval> iKeys = intervalHash.keySet().iterator();
	Iterator<Register> rKeys = hash.keySet().iterator();
	Iterator<Register> noSpillRegisters = noSpill.iterator();
	Iterator<Interval> noSpillIntervals = intervalNoSpill.iterator();
    
    System.out.println("intervals and its restrcited register set");
	while(iKeys.hasNext()){
		Enumeration<Register> enume = phys.enumerateAll();
		Interval i = iKeys.next();
		RestrictedRegisterSet set = intervalHash.get(i);
		System.out.println("Basic Interval's reg "+i.getContainer().getRegister().toString());
		while(enume.hasMoreElements()){
			Register temp = enume.nextElement();
			if(set.contains(temp))
				temp.toString();
		}
	}
	
	System.out.println("Register and its restrcited register set");
	while(rKeys.hasNext()){ 
		Enumeration<Register> enume = phys.enumerateAll();
		Register reg = rKeys.next();
		
		System.out.println("CompoundInterval Interval's reg "+reg.toString());
		RestrictedRegisterSet set = hash.get(reg);
		BitSet s =set.bitset;
		while(enume.hasMoreElements()){
			Register temp = enume.nextElement();
			if(set.contains(temp))
				temp.toString();
			
		}
		
	}
	
	while(noSpillIntervals.hasNext()){
		System.out.println("interval/ Register which cannot be spilled");
		Interval i = noSpillIntervals.next();
		MappedBasicInterval m = (MappedBasicInterval)i;
		System.out.println("Contains Registernum "+ m.container.getRegister().number);
		
	}
	
	while(noSpillRegisters.hasNext()){
		System.out.println(" Register which cannot be spilled");
		Register reg = noSpillRegisters.next();
		
		System.out.println("Contains Registernum "+ reg.number);
	}
}
*/
  /**
   * Record all the register restrictions dictated by live ranges on a
   * particular basic block.
   *
   * PRECONDITION: the instructions in each basic block are numbered in
   * increasing order before calling this.  The number for each
   * instruction is stored in its <code>scratch</code> field.
   */
  private void processBlock(BasicBlock bb) {
    ArrayList<LiveIntervalElement> symbolic = new ArrayList<LiveIntervalElement>(20);
    ArrayList<LiveIntervalElement> physical = new ArrayList<LiveIntervalElement>(20);
    Interval i;
    // 1. walk through the live intervals and identify which correspond to
    // physical and symbolic registers
    for (Enumeration<LiveIntervalElement> e = bb.enumerateLiveIntervals(); e.hasMoreElements();) {
      LiveIntervalElement li = e.nextElement();
      Register r = li.getRegister();
      if (r.isPhysical()) {
        if (r.isVolatile() || r.isNonVolatile()) {
          physical.add(li);
        }
      } else {
        symbolic.add(li);
      }
    }

    // 2. walk through the live intervals for physical registers.  For
    // each such interval, record the conflicts where the live range
    // overlaps a live range for a symbolic register.
    for (LiveIntervalElement phys : physical) {
      for (LiveIntervalElement symb : symbolic) {
        if (overlaps(phys, symb)) {
        	 i = symb.getInterval();
            VM._assert(i != null);
            if(i.getContainer().equals(i.getInterval())) 
        		addRestriction(symb.getRegister(), phys.getRegister());
            else
        		addRestriction(i, phys.getRegister());
        }
      }
    }

    // 3. Volatile registers used by CALL instructions do not appear in
    // the liveness information.  Handle CALL instructions as a special
    // case.
    for (InstructionEnumeration ie = bb.forwardInstrEnumerator(); ie.hasMoreElements();) {
      Instruction s = ie.next();
      if (s.operator.isCall() && s.operator != CALL_SAVE_VOLATILE) {
        for (LiveIntervalElement symb : symbolic) {
          if (contains(symb, s.scratch)) {
        	  i = symb.getInterval();
        	  VM._assert(i != null);
        	  if(i.getContainer().equals(i.getInterval())) 
         		forbidAllVolatiles(symb.getRegister());
             else 
         		forbidAllVolatiles(i);
          }
        }
      }

      // Before OSR points, we need to save all FPRs,
      // On OptExecStateExtractor, all GPRs have to be recovered,
      // but not FPRS.
      //
      if (s.operator == YIELDPOINT_OSR) {
        for (LiveIntervalElement symb : symbolic) {
          if (symb.getRegister().isFloatingPoint()) {
            if (contains(symb, s.scratch)) {
            	 i = symb.getInterval();
            	VM._assert(i != null);
            	if(i.getContainer().equals(i.getInterval()))
            		forbidAllVolatiles(symb.getRegister());
            	else
            		forbidAllVolatiles(i);
            }
          }
        }
      }
    }

    // 3. architecture-specific restrictions
    addArchRestrictions(bb, symbolic);
  }

  /**
   * Add architecture-specific register restrictions for a basic block.
   * Override as needed.
   *
   * @param bb the basic block
   * @param symbolics the live intervals for symbolic registers on this
   * block
   */
  public void addArchRestrictions(BasicBlock bb, ArrayList<LiveIntervalElement> symbolics) {}

  /**
   * Does a live range R contain an instruction with number n?
   *
   * PRECONDITION: the instructions in each basic block are numbered in
   * increasing order before calling this.  The number for each
   * instruction is stored in its <code>scratch</code> field.
   */
  protected final boolean contains(LiveIntervalElement R, int n) {
    int begin = -1;
    int end = Integer.MAX_VALUE;
    if (R.getBegin() != null) {
      begin = R.getBegin().scratch;
    }
    if (R.getEnd() != null) {
      end = R.getEnd().scratch;
    }

    return ((begin <= n) && (n <= end));
  }

  /**
   * Do two live ranges overlap?
   *
   * PRECONDITION: the instructions in each basic block are numbered in
   * increasing order before calling this.  The number for each
   * instruction is stored in its <code>scratch</code> field.
   */
  private boolean overlaps(LiveIntervalElement li1, LiveIntervalElement li2) {
    // Under the following conditions: the live ranges do NOT overlap:
    // 1. begin2 >= end1 > -1
    // 2. begin1 >= end2 > -1
    // Under all other cases, the ranges overlap

    int begin1 = -1;
    int end1 = -1;
    int begin2 = -1;
    int end2 = -1;

    if (li1.getBegin() != null) {
      begin1 = li1.getBegin().scratch;
    }
    if (li2.getEnd() != null) {
      end2 = li2.getEnd().scratch;
    }
    if (end2 <= begin1 && end2 > -1) return false;

    if (li1.getEnd() != null) {
      end1 = li1.getEnd().scratch;
    }
    if (li2.getBegin() != null) {
      begin2 = li2.getBegin().scratch;
    }
    return end1 > begin2 || end1 <= -1;

  }

  /**
   * Record that it is illegal to assign a symbolic register symb to any
   * volatile physical registers
   */
  final void forbidAllVolatiles(Register symb) {
    RestrictedRegisterSet r = hash.get(symb);
    if (r == null) {
      r = new RestrictedRegisterSet(phys);
      hash.put(symb, r);
    }
    r.setNoVolatiles();
  }
  final void forbidAllVolatiles(Interval I) {
	    RestrictedRegisterSet r = intervalHash.get(I);
	    if (r == null) {
	      r = new RestrictedRegisterSet(phys);
	      intervalHash.put(I, r);
	    }
	    r.setNoVolatiles();
	  }


  /**
   * Record that it is illegal to assign a symbolic register symb to any
   * of a set of physical registers
   */
  protected final void addRestrictions(Register symb, BitSet set) {
    RestrictedRegisterSet r = hash.get(symb);
    if (r == null) {
      r = new RestrictedRegisterSet(phys);
      hash.put(symb, r);
    }
    r.addAll(set);
  }
  protected final void addRestrictions(Interval I, BitSet set) {
	    RestrictedRegisterSet r = intervalHash.get(I);
	    if (r == null) {
	      r = new RestrictedRegisterSet(phys);
	      intervalHash.put(I, r);
	    }
	    r.addAll(set);
	  }
  /**
   * Record that it is illegal to assign a symbolic register symb to a
   * physical register p
   */
  protected final void addRestriction(Register symb, Register p) {
    RestrictedRegisterSet r = hash.get(symb);
    if (r == null) {
      r = new RestrictedRegisterSet(phys);
      hash.put(symb, r);
    }
    r.add(p);
  }
  /**
   * Record that it is illegal to assign a symbolic register symb to a
   * physical register p
   */
  protected final void addRestriction(Interval I, Register p) {
    RestrictedRegisterSet r = intervalHash.get(I);
    if (r == null) {
      r = new RestrictedRegisterSet(phys);
      intervalHash.put(I, r);
    }
    r.add(p);
  }
  /**
   * Return the set of restricted physical register for a given symbolic
   * register. Return null if no restrictions.
   */
  final RestrictedRegisterSet getRestrictions(Register symb) {
    return hash.get(symb);
  }
  
  final RestrictedRegisterSet getRestrictions(Interval I) {
	    return intervalHash.get(I);
	  }

  /**
   * Is it forbidden to assign symbolic register symb to any volatile
   * register?
   * @return true :yes, all volatiles are forbidden
   *         false :maybe, maybe not
   */
  public final boolean allVolatilesForbidden(Register symb) {
    if (VM.VerifyAssertions) {
      VM._assert(symb != null);
    }
    RestrictedRegisterSet s = getRestrictions(symb);
    if (s == null) return false;
    return s.getNoVolatiles();
  }
  
  public final boolean allVolatilesForbidden(Interval I) {
	    if (VM.VerifyAssertions) {
	      VM._assert(I != null);
	    }
	    RestrictedRegisterSet s = getRestrictions(I);
	    if (s == null) return false;
	    return s.getNoVolatiles();
	  }
  /**
   * Is it forbidden to assign symbolic register symb to physical register
   * phys?
   */
  public final boolean isForbidden(Register symb, Register phys) {
    if (VM.VerifyAssertions) {
      VM._assert(symb != null);
      VM._assert(phys != null);
    }
    RestrictedRegisterSet s = getRestrictions(symb);
    if (s == null) return false;
    return s.contains(phys);
  }
  
  public final boolean isForbidden(Interval I, Register phys) {
	    if (VM.VerifyAssertions) {
	      VM._assert(I != null);
	      VM._assert(phys != null);
	    }
	    RestrictedRegisterSet s = getRestrictions(I);
	    if (s == null) return false;
	    return s.contains(phys);
	  }
  /**
   * Is it forbidden to assign symbolic register symb to physical register r
   * in instruction s?
   */
  public abstract boolean isForbidden(Register symb, Register r, Instruction s);

  /**
   * An instance of this class represents restrictions on physical register
   * assignment.
   */
  private static final class RestrictedRegisterSet {
    /**
     * The set of registers to which assignment is forbidden.
     */
    private final BitSet bitset;

    /**
     * additionally, are all volatile registers forbidden?
     */
    private boolean noVolatiles = false;

    boolean getNoVolatiles() { return noVolatiles; }

    void setNoVolatiles() { noVolatiles = true; }

    /**
     * Default constructor
     */
    RestrictedRegisterSet(PhysicalRegisterSet phys) {
      bitset = new BitSet(phys);
    }

    /**
     * Add a particular physical register to the set.
     */
    void add(Register r) {
      bitset.add(r);
    }

    /**
     * Add a set of physical registers to this set.
     */
    void addAll(BitSet set) {
      bitset.addAll(set);
    }

    /**
     * Does this set contain a particular register?
     */
    boolean contains(Register r) {
      if (r.isVolatile() && noVolatiles) {
        return true;
      } else {
        return bitset.contains(r);
      }
    }
  }
}
