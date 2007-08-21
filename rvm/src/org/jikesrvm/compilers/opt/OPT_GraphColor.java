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

package org.jikesrvm.compilers.opt;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.LinkedList;
import java.util.Stack;
import java.lang.StringBuffer;
import org.jikesrvm.ArchitectureSpecific.OPT_PhysicalRegisterConstants;
import org.jikesrvm.ArchitectureSpecific.OPT_PhysicalRegisterSet;
import org.jikesrvm.ArchitectureSpecific.OPT_RegisterRestrictions;
import org.jikesrvm.ArchitectureSpecific.OPT_StackManager;
import org.jikesrvm.VM;
import org.jikesrvm.VM_Options;
import org.jikesrvm.classloader.VM_TypeReference;
import org.jikesrvm.compilers.opt.OPT_LinearScan.ActiveSet;
import org.jikesrvm.compilers.opt.OPT_LinearScan.BasicInterval;
import org.jikesrvm.compilers.opt.ir.OPT_AddressConstantOperand;
import org.jikesrvm.compilers.opt.ir.OPT_BasicBlock;
import org.jikesrvm.compilers.opt.ir.OPT_ControlFlowGraph;
import org.jikesrvm.compilers.opt.ir.OPT_GCIRMapElement;
import org.jikesrvm.compilers.opt.ir.OPT_IR;
import org.jikesrvm.compilers.opt.ir.OPT_Instruction;
import org.jikesrvm.compilers.opt.ir.OPT_InstructionEnumeration;
import org.jikesrvm.compilers.opt.ir.OPT_IntConstantOperand;
import org.jikesrvm.compilers.opt.ir.OPT_LongConstantOperand;
import org.jikesrvm.compilers.opt.ir.OPT_Operand;
import org.jikesrvm.compilers.opt.ir.OPT_OperandEnumeration;
import org.jikesrvm.compilers.opt.ir.OPT_Operators;
import static org.jikesrvm.compilers.opt.ir.OPT_Operators.SPLIT;
import org.jikesrvm.compilers.opt.ir.OPT_RegSpillListElement;
import org.jikesrvm.compilers.opt.ir.OPT_Register;
import org.jikesrvm.compilers.opt.ir.OPT_RegisterOperand;
import org.jikesrvm.compilers.opt.ir.OPT_RegisterOperandEnumeration;
import org.jikesrvm.compilers.opt.ir.Move;
import org.jikesrvm.compilers.opt.ir.MIR_Move;
import org.jikesrvm.compilers.opt.ir.OPT_StackLocationOperand;
import org.jikesrvm.osr.OSR_Constants;
import org.jikesrvm.osr.OSR_LocalRegPair;
import org.jikesrvm.osr.OSR_MethodVariables;
import org.jikesrvm.osr.OSR_VariableMapElement;
import org.vmmagic.unboxed.Word;

/**
 * Main driver for graph color register allocation.
 */
public class OPT_GraphColor extends OPT_OptimizationPlanCompositeElement {

	/**
	 * Build this phase as a composite of others.
	 */
	public OPT_GraphColor() {
		super("Graph Color Composite Phase",
				new OPT_OptimizationPlanElement[]{
				new OPT_OptimizationPlanAtomicElement(new RegisterRestrictions()),
				new OPT_OptimizationPlanAtomicElement(new GraphColor()),
				new OPT_OptimizationPlanAtomicElement(new UpdateGCMaps1()),
				new OPT_OptimizationPlanAtomicElement(new SpillCode()),
				new OPT_OptimizationPlanAtomicElement(new UpdateGCMaps2()),
				new OPT_OptimizationPlanAtomicElement(new UpdateOSRMaps())
		});
	}

	/**
	 * Register allocation is required
	 */
	public boolean shouldPerform(OPT_Options options) {
		return true;
	}

	public String getName() {
		return "Graph Color Composite Phase";
	}

	public boolean printingEnabled(OPT_Options options, boolean before) {
		return false;
	}

	/**
	 * debug flags
	 */
	private static boolean debug = false;
	private static boolean verboseDebug = false;
	private static boolean spillAllRegs = false; // -X:vm:spill_all=true option
	private static boolean gcdebug = false;
	private static boolean dumpStatistics = false;
	private static boolean debugCoalesce = false;
	private static boolean dumpRegisterMapping = false;

	/**
	 * Attempt to coalesce to eliminate register moves?
	 */
	private static boolean COALESCE_MOVES = false;

	private static boolean spilledSomething = false;


	/**
	 * A phase to compute register restrictions.
	 */
	static final class RegisterRestrictions extends OPT_CompilerPhase {

		/**
		 * Return this instance of this phase. This phase contains no
		 * per-compilation instance fields.
		 * @param ir not used
		 * @return this
		 */
		public OPT_CompilerPhase newExecution(OPT_IR ir) {
			return this;
		}

		public boolean shouldPerform(OPT_Options options) {
			return true;
		}

		public String getName() {
			return "Register Restrictions";
		}

		public boolean printingEnabled(OPT_Options options, boolean before) {
			return false;
		}

		/**
		 *  @param ir the IR
		 */
		public void perform(OPT_IR ir) {

			// The registerManager has already been initialized
			OPT_GenericStackManager sm = ir.stackManager;

			ir.numberInstructions();

			// Set up register restrictions
			sm.computeRestrictions(ir);
		}
	}

	public static final class GraphColorState {

		public boolean spilledSomething = false;

	}

	public static final class GraphColor extends OPT_CompilerPhase {

		/**
		 * Constructor for this compiler phase
		 */
		private static final Constructor<OPT_CompilerPhase> constructor = getCompilerPhaseConstructor(GraphColor.class);

		/**
		 * Get a constructor object for this compiler phase
		 * @return compiler phase constructor
		 */
		public Constructor<OPT_CompilerPhase> getClassConstructor() {
			return constructor;
		}

		/**
		 * Register allocation is required
		 */
		public boolean shouldPerform(OPT_Options options) {
			return true;
		}

		public String getName() {
			return "GraphColor";
		}

		public boolean printingEnabled(OPT_Options options, boolean before) {
			return false;
		}

		/**
		 * The governing IR
		 */
		protected OPT_IR ir;

		// OPT_Register to Integer Id mapping
		private HashMap<OPT_Register, Integer> RegToId = new HashMap<OPT_Register, Integer>();

		// Integer Id to OPT_Register mapping
		private HashMap<Integer, OPT_Register> IdToReg = new HashMap<Integer, OPT_Register>();

		// registers that have been coalesced
		private HashSet<Integer> coalescedNodes = new HashSet<Integer>();

		// when a move (u,v) has been coalesced, and v put in coalescedNodes, then alias(v) = u
		private HashMap<Integer, Integer> alias = new HashMap<Integer, Integer>(); 

		private int currentRegId = -1;

		private Integer mapRegToId(OPT_Register reg) {

			if(!RegToId.containsKey(reg)) {
				currentRegId ++;
				RegToId.put(reg, currentRegId);
				IdToReg.put(currentRegId, reg);
				return currentRegId;
			}

			return RegToId.get(reg);
		}

		private Integer getRegId(OPT_Register reg) {

			if(!RegToId.containsKey(reg)) {
				throw new RuntimeException("OPT_GraphColor PANIC getRegId: Identifier for register " +
						reg.toString() + " does not exists.");
			}

			return RegToId.get(reg);
		}


		// to delete in future
		private OPT_Register mapIdToReg(Integer id) {

			if(!IdToReg.containsKey(id)) {
				throw new RuntimeException("OPT_GraphColor PANIC mapIdToReg: Register with identifier " + 
						id + " does not exists.");
			}

			return IdToReg.get(id);
		}

		private Integer getAlias(Integer n) {
			if(coalescedNodes.contains(n)) {
				return getAlias(alias.get(n));
			}
			else
				return n;
		}

		// machine registers, preassigned a color
		public HashSet<Integer> precolored = new HashSet<Integer>();

		// temporary registers, not preassigned a color and not yet processed by the algorithm
		public HashSet<Integer> initial = new HashSet<Integer>();

		// list of low-degree non-move-related nodes 
		public HashSet<Integer> simplifyWorkList = new HashSet<Integer>();

		// low-degree move-related nodes
		public HashSet<Integer> freezeWorkList = new HashSet<Integer>();

		// high-degree nodes
		public HashSet<Integer> spillWorkList = new HashSet<Integer>();

		// nodes marked for spilling during this round
		public HashSet<Integer> spilledNodes = new HashSet<Integer>();

		// Nodes successfully colored
		public HashSet<Integer> coloredNodes = new HashSet<Integer>();

		// stack containing temporaries removed from the graph
		public Stack<Integer> selectStack = new Stack<Integer>();

		// a mappig from node to the list of moves it is associated with
		public HashMap<Integer, HashSet<MovePair>> moveList = new HashMap<Integer, HashSet<MovePair>>();

		// moves that have been coalesced
		public HashSet<MovePair> coalescedMoves = new HashSet<MovePair>();

		// moves whose source and target interfere
		public HashSet<MovePair> constrainedMoves = new HashSet<MovePair>();

		// moves that will no longer be considered for coalescing
		public HashSet<MovePair> frozenMoves = new HashSet<MovePair>();

		// moves enabled for possible coalescing
		public HashSet<MovePair> workListMoves = new HashSet<MovePair>();

		// moves not yet ready for colascing
		public HashSet<MovePair> activeMoves = new HashSet<MovePair>();

		/**
		 * The color chosen by the algorithm for a node. For precolored nodes this 
		 * is initialized to the given color
		 */
		public HashMap<Integer, Integer> color = new HashMap<Integer, Integer>(); 

		/**
		 * Interference graph
		 */ 
		protected IGraph igraph = new IGraph();

		// number of colors
		public int K;
		
		/**
		 * Register restictions
		 */
		public OPT_RegisterRestrictions restrictions = null;

		/**
		 *  @param ir the IR
		 */
		public void perform(OPT_IR ir) {

			this.restrictions = ir.stackManager.getRestrictions();
			this.ir = ir;

			spillAllRegs = VM_Options.SPILL_ALL_REGS;

			initPrecoloredSet();

			for (OPT_Register reg = ir.regpool.getFirstSymbolicRegister(); reg != null; reg = reg.getNext()) {
				initial.add(mapRegToId(reg));
			}

			if(false) System.out.println("Compiling method: " + ir.method.getName().toString());


			/*			if(ir.method.getName().toString().contentEquals(new StringBuffer("ftest"))) {
				debug = true;
			} else {
				debug = false;
			}*/

			if(spillAllRegs) {
				spillAllRegs();
				return;
			}

			IteratedCoalescing();

			if(debug) {
				System.out.println("_____________________________________    END    _____________________________________\n\n\n");
			}
		}

		/**
		 * 
		 *
		 */
		public void IteratedCoalescing() {
			
			for (OPT_Register reg = ir.regpool.getFirstSymbolicRegister(); reg != null; reg = reg.getNext()) {
				igraph.addNode(getRegId(reg));
			}
			//aBuild();
			build();
			//corBuild();
			//igraph.DumpDot(ir.method.getName().toString());
			
			if(debug) {
				igraph.DumpDot(ir.method.getName().toString());
				dumpCFG(ir);
			}

			// number of symbolic registers
			numSymbRegs = initial.size();

			main();

			// Dump register allocation statistics
			if(dumpStatistics) {
				dumpStat();
			}
		}

		public void initPrecoloredSet() {
			OPT_PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();

			for (Enumeration<OPT_Register> e = phys.enumerateAll(); e.hasMoreElements();) {
				OPT_Register physReg = e.nextElement();
				if(physReg != null) {
					Integer regId = mapRegToId(physReg); 
					precolored.add(regId);
					// Assign color
					color.put(regId, regId);
				}
			}
		}

		/**
		 * Main procedure
		 * 
		 */
		public void main()
		{
			numIterations ++;

			boolean debugSimple = true;

			if(debugSimple) {
				for(Integer n : initial) {
					selectStack.push(n);
				}
			} else {
				makeWorkList();

				do {
					if(simplifyWorkList.size() != 0)
						simplify();

					else if(workListMoves.size() != 0)
						coalesce();

					else if(freezeWorkList.size() != 0)
						freeze();

					else if(spillWorkList.size() != 0)
						selectSpill();

				} while(simplifyWorkList.size() != 0 || 
						workListMoves.size()    != 0 || 
						freezeWorkList.size()   != 0 || 
						spillWorkList.size()    != 0 );
			}

			assignColors();

			if(!debugSimple) {
				if(spilledNodes.size() > 0) {
					main();
				}
			}
		}
		
		public void corBuild() {
			OPT_PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();
			
			for (OPT_Register symbReg = ir.regpool.getFirstSymbolicRegister(); symbReg != null; symbReg = symbReg.getNext()) {
				for (Enumeration<OPT_Register> e = phys.enumerateAll(); e.hasMoreElements();) {
					OPT_Register physReg = e.nextElement();
					if(physReg != null) {
						if(restrictions.isForbidden(symbReg, physReg)) {
							if(igraph.addEdge(getRegId(symbReg), getRegId(physReg))) {
								System.out.println("*");
							}
						}
					}
				}				
			}
		}

		public void aBuild() {

			// Perform live analysis
			OPT_LiveAnalysis liveAnalysis = new OPT_LiveAnalysis(false, false);
			liveAnalysis.perform(ir); 

			// Compute def-use information.
			OPT_DefUse.computeDU(ir);

			// Number the instructions
			ir.numberInstructions();	

			OPT_PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();


			for (OPT_Register r1 = ir.regpool.getFirstSymbolicRegister(); r1 != null; r1 = r1.getNext()) {
				for (OPT_Register r2 = ir.regpool.getFirstSymbolicRegister(); r2 != null; r2 = r2.getNext()) {

					if(OPT_Coalesce.isLiveAtDef(r2, r1, liveAnalysis) || OPT_Coalesce.isLiveAtDef(r1, r2, liveAnalysis)) {
						if(r1 != r2 && r1 != phys.getPR() && r2 != phys.getPR())
						{
							if((r1.isNatural() && r2.isNatural()) ||
									(r1.isFloatingPoint() && r2.isFloatingPoint())) {
								igraph.addEdge(getRegId(r1), getRegId(r2));
							}
						}
					} 
				}
			}
		}

		/*
		 * Build interference graph
		 */
		public void build() {
			// try to delete this block

			// Perform live analysis
			OPT_LiveAnalysis liveAnalysis = new OPT_LiveAnalysis(false, false);

			liveAnalysis.perform(ir); 

			// Compute def-use information.
			OPT_DefUse.computeDU(ir);

			// Number the instructions
			ir.numberInstructions();

			// Current live set
			HashSet<OPT_Register> live;

			OPT_PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();

			for (OPT_BasicBlock bb = ir.cfg.entry(); bb != null; bb = (OPT_BasicBlock) bb.next) {
				// live = liveOut(bb) 
				live = liveAnalysis.getLiveRegistersOnExit(bb);

				for (OPT_Instruction inst = bb.lastInstruction(); ; inst = inst.prevInstructionInCodeOrder()) {

					if(MIR_Move.conforms(inst)) {

						// only mov r1,r2
						if(MIR_Move.getResult(inst).isRegister() && MIR_Move.getValue(inst).isRegister() ) {

							OPT_Register res = MIR_Move.getResult(inst).asRegister().register;
							OPT_Register val = MIR_Move.getValue(inst).asRegister().register;

							if(res != phys.getPR() && val != phys.getPR()) {

								if(live.contains(res) && live.contains(val)) {	
									// check if registers from the same class
									if((res.isNatural() && val.isNatural()) ||
											(res.isFloatingPoint() && val.isFloatingPoint())) {
										igraph.addEdge(getRegId(res), getRegId(val)); 
									}
								} else {
									if(COALESCE_MOVES) {
										igraph.addMovePair(getRegId(res), getRegId(val), inst);
										workListMoves.add(new MovePair(getRegId(res), getRegId(val), inst));
									}
								}
							}
						}

						// mov r1,r2
						if(MIR_Move.getResult(inst).isRegister() && MIR_Move.getValue(inst).isRegister()) {
							// live = live - Use(I) 
							for (OPT_OperandEnumeration ops = inst.getUses(); ops.hasMoreElements();) {
								OPT_Operand op = ops.next();
								if (op.isRegister()) {
									live.remove(op.asRegister().register);
								}
							}
						}
					}


					// live = live + Def(I) 
					for (OPT_OperandEnumeration ops = inst.getDefs(); ops.hasMoreElements();) {
						OPT_Operand op = ops.next();
						if (op.isRegister()) {
							live.add(op.asRegister().register);
						}
					}			

					for (OPT_OperandEnumeration ops = inst.getDefs(); ops.hasMoreElements();) {
						OPT_Operand op = ops.next();
						if (op.isRegister()) {
							for(Iterator<OPT_Register> l = live.iterator(); l.hasNext();) {

								OPT_Register reg1 = l.next();
								OPT_Register reg2 = op.asRegister().register;

								/*
								 * Check:
								 * 1. r1 != r2
								 * 2. Symbolic register is not a processor predefined register
								 */
								if(reg1 != reg2 && reg1 != phys.getPR() && reg2 != phys.getPR())
								{
									// check if registers from the same class
									if((reg1.isNatural() && reg2.isNatural()) ||
											(reg1.isFloatingPoint() && reg2.isFloatingPoint())) {
										igraph.addEdge(getRegId(reg1), getRegId(reg2));
									}
								}
							}
						}
					}

					// live = use(I) + (live - def(I)) 

					// tmpLive = live - def(I) 
					for (OPT_OperandEnumeration ops = inst.getDefs(); ops.hasMoreElements();) {
						OPT_Operand op = ops.next();
						if (op.isRegister()) {
							live.remove(op.asRegister().register);
						}
					}

					// live = Use(I) + tmpLive 
					for (OPT_OperandEnumeration ops = inst.getUses(); ops.hasMoreElements();) {
						OPT_Operand op = ops.next();
						if (op.isRegister()) {
							live.add(op.asRegister().register);
						}
					}

					if(inst == bb.firstInstruction()) break;
				} // Instruction enumeration
			} // BasicBlock enumeration


			// coalesce moves

			// Maintain a set of dead move instructions.
			HashSet<OPT_Instruction> dead = new HashSet<OPT_Instruction>(5);

			if(debugCoalesce) {
				System.out.println("Moves:");
				for(MovePair pair : workListMoves) {
					System.out.println(pair.moveInstr.toString());
				}

				System.out.println("_______");
			}

			OPT_RegisterRestrictions restrict = ir.stackManager.getRestrictions();




			for(MovePair pair : workListMoves) {

				OPT_Register res = MIR_Move.getResult(pair.moveInstr).asRegister().register;
				OPT_Register val = MIR_Move.getValue(pair.moveInstr).asRegister().register;

				res = mapIdToReg(getAlias(getRegId(res)));
				val = mapIdToReg(getAlias(getRegId(val)));

				if (res == val) {
					pair.moveInstr = pair.moveInstr.remove();
					continue;
				}

				if((res.isSymbolic() && res.isNatural() /*&& !res.spansBasicBlock()*/) &&
						(val.isSymbolic() && val.isNatural() /*&& !val.spansBasicBlock()*/) ) {
//
//					if(res.isCoalesced() || val.isCoalesced()) {
//						dumpCFG(ir);
//						OPT_OptimizingCompilerException.UNREACHABLE("GraphColor", "coalesced", val.toString() + " " + res.toString());
//					}

					OPT_RegisterRestrictions.RestrictedRegisterSet regSetRes = restrict.getRestrictions(res);
					OPT_RegisterRestrictions.RestrictedRegisterSet regSetVal = restrict.getRestrictions(val);

					boolean okCoal = false; 

					if (debugCoalesce) System.out.println(pair.moveInstr.toString());

					/*					if(OPT_Coalesce.isLiveAtDef(val, res, liveAnalysis) || OPT_Coalesce.isLiveAtDef(res, val, liveAnalysis)) {
						if(debugCoalesce)System.out.println("Simultaneously live from isLiveAtDefs()");

						okCoal = false;
					}*/

					if (igraph.containsEdge(getRegId(res), getRegId(val))) {
						if(debugCoalesce)System.out.println("Simultaneously live from igraph");
						okCoal = false;
					} else {
						okCoal = true;
					}

					/*					if (OPT_Coalesce.split(res, val)) {
						dumpCFG(ir);
						OPT_OptimizingCompilerException.UNREACHABLE("GraphColor", "Split", val.toString() + " " + res.toString());
					}

					if(OPT_Coalesce.attempt(ir, liveAnalysis, val, res)) {
						res.setCoalesced();
						dead.add(pair.moveInstr);
						coalescedNodes.add(getRegId(res));
						alias.put(getRegId(res), getRegId(val));
						continue;
					} else {

					}*/

					// val <-- res
					if(okCoal) {

						/*						if(OPT_Coalesce.isLiveAtDef(val, res, liveAnalysis) || OPT_Coalesce.isLiveAtDef(res, val, liveAnalysis)) {
							//dumpCFG(ir);
							//OPT_OptimizingCompilerException.UNREACHABLE("GraphColor", "Simultaneously live", val.toString() + " " + res.toString());
						} else {
							//mergeRegisters(val, res, liveAnalysis);
						}*/

						igraph.merge(getRegId(val), getRegId(res));

						dead.add(pair.moveInstr);
						coalescedNodes.add(getRegId(res));
						alias.put(getRegId(res), getRegId(val));
						initial.remove(getRegId(res));

						if(regSetRes != null) {
							restrict.addRestrictions(val, regSetRes.bitset);
							if(regSetRes.getNoVolatiles()) {
								restrict.forbidAllVolatiles(val);
							}
						}

						if(debugCoalesce)System.out.println("Coalesce ok");

//						res.setCoalesced();
					} else {
						if(debugCoalesce)System.out.println("Coalesce failed");
					}
				} // if
			} // for

			// Now remove all dead Move instructions.
			for (OPT_Instruction s : dead) {

				s = s.remove();
				//OPT_DefUse.removeInstructionAndUpdateDU(s);
			}
		}

		/**
		 * Merge register symbReg2 with symbReg1
		 * @param symbReg1
		 * @param symbReg2
		 * @param liveAnalysis
		 */
		public void mergeRegisters(OPT_Register symbReg1, OPT_Register symbReg2, OPT_LiveAnalysis liveAnalysis) {

			// Update liveness information to reflect the merge.
			liveAnalysis.merge(symbReg1, symbReg2);

			// Merge the defs.
			for (OPT_RegisterOperandEnumeration e = OPT_DefUse.defs(symbReg2); e.hasMoreElements();) {
				OPT_RegisterOperand def = e.nextElement();
				OPT_DefUse.removeDef(def);
				def.register = symbReg1;
				OPT_DefUse.recordDef(def);
			}
			// Merge the uses.
			for (OPT_RegisterOperandEnumeration e = OPT_DefUse.uses(symbReg2); e.hasMoreElements();) {
				OPT_RegisterOperand use = e.nextElement();
				OPT_DefUse.removeUse(use);
				use.register = symbReg1;
				OPT_DefUse.recordUse(use);
			}
		}

		/**
		 * READY
		 * 
		 */
		public void makeWorkList() {

			for(Integer n : initial) {
				if(heurGE(n)) {
					spillWorkList.add(n);
				} else if(moveRelated(n)) {
					freezeWorkList.add(n);
				} else {
					simplifyWorkList.add(n);
				}
			}
			initial.clear();
		}
		
		/**
		 * degree(reg) >= K
		 * @param n
		 * @return
		 */
		public boolean heurGE(Integer n) {
			OPT_Register reg = mapIdToReg(n);
			
			if(reg.isNatural()) {
				if(corDegree(n) >= 6) {
					return true;
				} else {
					return false;
				}
			} else if(reg.isFloatingPoint()) {
				return false;
			} else {
				OPT_OptimizingCompilerException.UNREACHABLE("GraphColor", "Not supported type", reg.toString());
				return false;
			}
		}
		
		public boolean heurE(Integer n) {
			OPT_Register reg = mapIdToReg(n);
			
			if(reg.isNatural()) {
				if(corDegree(n) == 6) {
					return true;
				} else {
					return false;
				}
			} else if(reg.isFloatingPoint()) {
				return false;
			} else {
				OPT_OptimizingCompilerException.UNREACHABLE("GraphColor", "Not supported type", reg.toString());
				return false;
			}
		}
		
		/**
		 * Corrected degree
		 */
		public int corDegree(Integer n) {
			OPT_Register reg = mapIdToReg(n);
			
			if(reg.isNatural()) {
				if (!restrictions.allVolatilesForbidden(reg)) {
					return (igraph.getDegree(n) + 3);
				} else {
					return igraph.getDegree(n);			
				}
			} else if(reg.isFloatingPoint()) {
				return 0;
			} else {
				OPT_OptimizingCompilerException.UNREACHABLE("GraphColor", "Not supported type", reg.toString());
				return 0;
			}
		}
		
		

		/**
		 * 
		 * @param n
		 * @return
		 */
		public HashSet<Integer> adjacent(Integer n) {

			HashSet<Integer> adj = new HashSet<Integer>();

			adj.addAll(igraph.adjList.get(n));
			adj.removeAll(selectStack);
			adj.removeAll(coalescedNodes);

			return adj;
		}

		/**
		 * 
		 * @param n
		 * @return
		 */
		public HashSet<MovePair> nodeMoves(Integer n) {

			HashSet<MovePair> moves = new HashSet<MovePair>();
			HashSet<MovePair> tmp = new HashSet<MovePair>();

			moves.addAll(igraph.moveList.get(n));

			tmp.addAll(activeMoves);
			tmp.addAll(workListMoves);

			moves.retainAll(tmp);

			return moves;		
		}

		/**
		 * READY
		 * @param n
		 * @return
		 */
		public boolean moveRelated(Integer n) {
			if(nodeMoves(n).iterator().hasNext())
				return true;
			else
				return false;
		}

		/**
		 * READY
		 *
		 */
		public void simplify() {

			Integer n = simplifyWorkList.iterator().next();

			simplifyWorkList.remove(n);
			selectStack.push(n);
			for(Integer m : adjacent(n)) {
				decrementDegree(m);
			}
		}

		/**
		 * 
		 * @param m
		 */
		public void decrementDegree(Integer m) {
			boolean ok = false;
			if(heurE(m)) {
				ok = true;
			}
			igraph.decDegree(m);
			
			if(ok) {
				enableMoves(m);
				spillWorkList.remove(m);
				if(moveRelated(m)) {
					freezeWorkList.add(m);
				} else {
					simplifyWorkList.add(m);
				}
			}
		}

		/**
		 * 
		 * @param u
		 */
		public void enableMoves(Integer u) {
			// u + adjacement(u)

			for(MovePair move : nodeMoves(u)) {
				if(activeMoves.contains(move)) {
					activeMoves.remove(move);
					workListMoves.add(move);
				}
			}

			for(Integer v : adjacent(u)) {
				for(MovePair move : nodeMoves(v)) {
					if(activeMoves.contains(move)) {
						activeMoves.remove(move);
						workListMoves.add(move);
					}
				}
			}
		}

		/**
		 * 
		 *
		 */
		public void coalesce() {
			Integer x,y,u,v;

			MovePair move = workListMoves.iterator().next();

			numCoalescedMoves ++;

			x = getAlias(move.dst);
			y = getAlias(move.src);

			if(precolored.contains(y)) {
				u = y;
				v = x;
			}
			else {
				u = x;
				v = y;
			}
			workListMoves.remove(move);

			if(u == v) {
				coalescedMoves.add(move);
				addWorkList(u);
			} else if (precolored.contains(v) || igraph.containsEdge(u, v)) {
				constrainedMoves.add(move);
				addWorkList(u);
				addWorkList(v);
			} else if((precolored.contains(u) && OK(v,u)) ||
					(!precolored.contains(u) && conservative(u,v))) {
				coalescedMoves.add(move);
				combine(u,v);
				addWorkList(u);
			} else
				activeMoves.add(move);
		}

		public void addWorkList(Integer u) {
			if(!precolored.contains(u) && !moveRelated(u) && igraph.getDegree(u) < K) {
				freezeWorkList.remove(u);
				simplifyWorkList.add(u);
			}
		}

		public boolean OK(Integer v, Integer u) {
			for(Integer t : adjacent(v)) {
				if(igraph.getDegree(t) < K || precolored.contains(t) || igraph.containsEdge(t, u)) {

				} else
					return false;
			}
			return true;
		}

		public boolean conservative(Integer u, Integer v) {
			int k = 0;

			for(Integer n : adjacent(u)) {
				if(igraph.getDegree(n) >= K) k = k + 1; 
			}

			for(Integer n : adjacent(v)) {
				if(igraph.getDegree(n) >= K) k = k + 1; 
			}

			return (k < K);
		}


		/**
		 * 
		 * @param u
		 * @param v
		 */
		public void combine(Integer u, Integer v) {
			if(freezeWorkList.contains(v)) {
				freezeWorkList.remove(v);
			}
			else {
				spillWorkList.remove(v);
			}

			coalescedNodes.add(v);
			alias.put(v, u);

			///

			for(Integer t : adjacent(v)) {
				igraph.addEdge(t, u);
				decrementDegree(t);
			}

			if((igraph.getDegree(u) >= K) && (freezeWorkList.contains(u))) {
				freezeWorkList.remove(u);
				spillWorkList.add(u);
			}
		}

		public void freeze() {
			Integer u = freezeWorkList.iterator().next();

			freezeWorkList.remove(u);
			simplifyWorkList.add(u);

			freezeMoves(u);
		}

		public void freezeMoves(Integer u) {
			Integer v;

			for(MovePair move : nodeMoves(u)) {

				if(u == move.dst) {
					v = move.src;
				}
				else {
					v = move.dst;
				}

				if(activeMoves.contains(move)) {
					activeMoves.remove(move);
				}
				else {
					workListMoves.remove(move);
				}

				frozenMoves.add(move);

				if(nodeMoves(v).isEmpty() && igraph.getDegree(v) < K) {
					freezeWorkList.remove(v);
					simplifyWorkList.add(v);
				}
			}
		}

		public void selectSpill() {

			Integer m = spillWorkList.iterator().next();

			spillWorkList.remove(m);
			simplifyWorkList.add(m);
			//freezeMoves(m);
		}

		/**
		 * Method for spilling all registers
		 *
		 */
		public void spillAllRegs() {
			for (OPT_Register symbReg = ir.regpool.getFirstSymbolicRegister(); symbReg != null; symbReg = symbReg.getNext()) {

				if(symbReg.isValidation()) continue;

				OPT_Register reg = mapIdToReg(getAlias(getRegId(symbReg)));

				if(!reg.isSpilled()) {
					// clear the 'long' type if it's persisted to here.
					if (VM.BuildFor32Addr && reg.isLong()) {
						reg.clearType();
						reg.setInteger();
					}

					int type = OPT_PhysicalRegisterSet.getPhysicalRegisterType(reg);
					if (type == -1) {
						type = 1;//DOUBLE_REG;
					}
					int spillSize = OPT_PhysicalRegisterSet.getSpillSize(type);
					int location = ir.stackManager.allocateNewSpillLocation(type);	

					OPT_RegisterAllocatorState.setSpill(reg, location);
				}

				if(OPT_PhysicalRegisterSet.getPhysicalRegisterType(reg) != OPT_PhysicalRegisterSet.getPhysicalRegisterType(symbReg)) {
					dumpCFG(ir);
					OPT_OptimizingCompilerException.UNREACHABLE("GraphColor", "Types wrong", symbReg.toString() + " " + reg.toString());
				}

				if(reg != symbReg) {
					// clear the 'long' type if it's persisted to here.
					if (VM.BuildFor32Addr && symbReg.isLong()) {
						symbReg.clearType();
						symbReg.setInteger();
					}
					OPT_RegisterAllocatorState.setSpill(symbReg, OPT_RegisterAllocatorState.getSpill(reg));
				}

			}
		}

		/**
		 * 
		 *
		 */
		public void spillRegister(OPT_Register reg) {
			int type;


			// clear the 'long' type if it's persisted to here.
			if (VM.BuildFor32Addr && reg.isLong()) {
				reg.clearType();
				reg.setInteger();
			}

			if(reg.isNatural() || reg.isFloatingPoint()) {
				type = OPT_PhysicalRegisterSet.getPhysicalRegisterType(reg);
				if (type == -1) {
					type = 1;//DOUBLE_REG;
				}
			} else {
				type = 0;
			}

			int spillSize = OPT_PhysicalRegisterSet.getSpillSize(type);
			int location = ir.stackManager.allocateNewSpillLocation(type);	

			OPT_RegisterAllocatorState.setSpill(reg, location);

		}

		public void assignColors() {
			HashSet<Integer> okColors = new HashSet<Integer>();
			HashSet<Integer> fixed = new HashSet<Integer>();

			OPT_PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();

			for (Enumeration<OPT_Register> e = phys.enumerateAll(); e.hasMoreElements();) {
				e.nextElement().clearAllocationFlags();
				//e.nextElement().touchRegister();
			}

			while(!selectStack.empty()) {

				Integer n = selectStack.pop();
				OPT_Register symbReg = mapIdToReg(n);
				OPT_Register physReg;

				// okColors := {0,1,2,..,K-1}

				okColors.clear();

				int type = OPT_PhysicalRegisterSet.getPhysicalRegisterType(symbReg);

				if(!ir.stackManager.getRestrictions().allVolatilesForbidden(symbReg)) {
					for (Enumeration<OPT_Register> e = phys.enumerateVolatiles(type); e.hasMoreElements();) {
						okColors.add(getRegId(e.nextElement()));
					}
				}

				for (Enumeration<OPT_Register> e = phys.enumerateNonvolatilesBackwards(type); e.hasMoreElements();) {
					okColors.add(getRegId(e.nextElement()));
				}

				fixed.clear();
				fixed.addAll(coloredNodes);
				fixed.addAll(precolored);

				for(Integer w : igraph.adjList.get(n)) {
					if(fixed.contains(getAlias(w))) {
						okColors.remove(color.get(getAlias(w)));
					}
				}

				if((physReg = findAvailableRegister(symbReg, okColors)) == null) {
					spilledNodes.add(n);
					spillRegister(symbReg);					
					spilledSomething = true;
				} else {

					if(!allocateNewSymbolicToPhysical(symbReg, physReg)) {
						//dumpInstr(ir);
						dumpCFG(ir);
						OPT_OptimizingCompilerException.UNREACHABLE("GraphColor", "Bad register", symbReg.toString() + " " + physReg.toString());
					}

					coloredNodes.add(n);
					color.put(n, getRegId(physReg));
					symbReg.freeRegister();
					physReg.touchRegister();
					symbReg.allocateRegister(physReg);
					symbReg.mapsToRegister = physReg;
				}

/*				if(okColors.isEmpty()) {
					spilledNodes.add(n);
					spillRegister(symbReg);
					spilledSomething = true;
				} else {
					physReg = mapIdToReg(okColors.iterator().next());

					if(!allocateNewSymbolicToPhysical(symbReg, physReg)) {
						//dumpInstr(ir);
						dumpCFG(ir);
						OPT_OptimizingCompilerException.UNREACHABLE("GraphColor", "Bad register", symbReg.toString() + " " + physReg.toString());
					}

					coloredNodes.add(n);
					color.put(n, getRegId(physReg));
					symbReg.freeRegister();
					physReg.touchRegister();
					symbReg.allocateRegister(physReg);
					symbReg.mapsToRegister = physReg;
				}*/
			}

			numSpilledRegs += spilledNodes.size();

			for(Integer n : coalescedNodes) {
				color.put(n, color.get(getAlias(n)));
			}

			if(dumpRegisterMapping) {
				System.out.println("***********");
				for (OPT_Register reg = ir.regpool.getFirstSymbolicRegister(); reg != null; reg = reg.getNext()) {
					if(reg.isAllocated()) {
						System.out.println(reg.toString() + "-->" + OPT_RegisterAllocatorState.getMapping(reg).toString());
					} else if(reg.isSpilled()) {
						System.out.println(reg.toString() + "-->Spilled");
					} else {
						System.out.println(reg.toString() + "-->Not defined");
					}
				}
				System.out.println("");
			}

			for(Integer id : coalescedNodes) {
				OPT_Register symbReg1 = mapIdToReg(id);
				OPT_Register symbReg2 = mapIdToReg(getAlias(id));

				if(symbReg2.isAllocated()) {
					OPT_Register physReg = OPT_RegisterAllocatorState.getMapping(symbReg2);

					if(allocateNewSymbolicToPhysical(symbReg1, physReg)) {
						symbReg1.freeRegister();
						symbReg1.allocateRegister(physReg);
						symbReg1.mapsToRegister = physReg;
					} else {
						OPT_OptimizingCompilerException.UNREACHABLE("GraphColor", "not reachable :", symbReg1.toString() + " " + symbReg2.toString() + " " + physReg.toString());
					}
				} else if (symbReg2.isSpilled()) {
					OPT_RegisterAllocatorState.setSpill(symbReg1, OPT_RegisterAllocatorState.getSpill(symbReg2));
				} else {
					OPT_OptimizingCompilerException.UNREACHABLE("GraphColor", "not reachable :", symbReg1.toString());
				}
			}
		}

		/**
		 * Try to find a free physical register to allocate to a symbolic
		 * register.
		 */
		OPT_Register findAvailableRegister(OPT_Register symb, HashSet<Integer> regSet) {

			OPT_PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();
			int type = OPT_PhysicalRegisterSet.getPhysicalRegisterType(symb);

			// next attempt to allocate to a volatile
			if (!restrictions.allVolatilesForbidden(symb)) {
				for (Enumeration<OPT_Register> e = phys.enumerateVolatiles(type); e.hasMoreElements();) {
					OPT_Register p = e.nextElement();
					if(regSet.contains(getRegId(p))) {
						if (allocateNewSymbolicToPhysical(symb, p)) {
							return p;
						}
					} else {
						//System.out.println("*");
					}
				}
			}


			// next attempt to allocate to a Nonvolatile.  we allocate the
			// novolatiles backwards.
			for (Enumeration<OPT_Register> e = phys.enumerateNonvolatilesBackwards(type); e.hasMoreElements();) {
				OPT_Register p = e.nextElement();
				if(regSet.contains(getRegId(p))) {
					if (allocateNewSymbolicToPhysical(symb, p)) {
						return p;
					}
				} else {
					//System.out.println("*");
				}
			}

			// no allocation succeeded.
			return null;
		}

		/**
		 * Check whether it's ok to allocate symbolic register to a physical
		 * register p.  If so, return true; If not, return false.
		 *
		 * NOTE: This routine assumes we're processing the first interval of
		 * register symb; so p.isAvailable() is the key information needed.
		 */
		private boolean allocateNewSymbolicToPhysical(OPT_Register symb, OPT_Register p) {
			OPT_PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();
			if (p != null && !phys.isAllocatable(p)) return false;

			if (false && p != null) {
				if (!p.isAvailable()) System.out.println("unavailable " + symb + p);
				if (restrictions.isForbidden(symb, p)) System.out.println("forbidden " + symb + "(" + p + ")");
			}

			return (p != null) && p.isAvailable() && !restrictions.isForbidden(symb, p);
		}


		/**
		 * Statistics
		 */ 

		/**
		 * Number of iterations in main
		 */ 
		private int numIterations = 0;

		/**
		 * number of moves
		 */
		private int numMoves = 0;

		/**
		 * number of coalesced moves
		 */
		private int numCoalescedMoves = 0;

		/**
		 * number of symbolic registers
		 */
		private int numSymbRegs = 0;

		/**
		 * number of spilled registers
		 */
		private int numSpilledRegs = 0;

		/**
		 * Dump statistics
		 */
		public void dumpStat() {

			System.out.println("************** GraphColor Statistics **************");
			System.out.println("# algo iterations:   " + numIterations);
			System.out.println("# symbolic regs:     " + numSymbRegs);
			System.out.println("# move instructions: " + numMoves);
			System.out.println("# move coalesced:    " + numCoalescedMoves);
			System.out.println("# spilled regs:      " + numSpilledRegs);

		}

		/**
		 * Move Instruction description
		 * 
		 */
		public class MovePair {

			public Integer src = null;
			public Integer dst = null;
			public OPT_Instruction moveInstr = null;


			/**
			 * MovePair constructor
			 * @param dst Destination register
			 * @param src Source register
			 * @param moveInstr JikesRVM instruction class
			 */
			public MovePair(Integer dst, Integer src, OPT_Instruction moveInstr) {
				this.src = src;
				this.dst = dst;
				this.moveInstr = moveInstr;
			}

			public boolean equals(Object pair) {
				MovePair temp = (MovePair)pair;
				if((this.src == temp.src) && (this.dst == temp.dst) && (this.moveInstr == temp.moveInstr)) {
					return true;
				}

				return false;
			}

			public int hashCode() {
				return( this.dst ^ this.src); 
			}

			public String Dump()
			{
				return (mapIdToReg(dst) + "--" + mapIdToReg(src) + " [style=dashed];");
			}
		}

		/**
		 * Interference graph
		 */
		public class IGraph	{

			private class Edge {
				public Integer u;
				public Integer v;

				public Edge(Integer u, Integer v) {
					this.u = u;    
					this.v = v;
				}

				public boolean equals(Object edge) {
					Edge temp = (Edge)edge;
					if(((int)temp.u == (int)this.u) && ((int)temp.v == (int)this.v)) {
						return true;
					} if(((int)temp.u == (int)this.v) && ((int)temp.v == (int)this.u)) {
						return true;
					}
					return false;
				}

				public int hashCode() {
					return (Math.max(this.u, this.v) ^ (Math.min(this.u, this.v) << 2)); 
				}

				public String Dump()
				{
					return (mapIdToReg(u) + "--" + mapIdToReg(v) + ";");
				}
			}

			private HashSet<Edge> adjSet = null;
			private HashMap<Integer, HashSet<Integer>> adjList = null;
			private HashMap<Integer, Integer> nodeDegree = null;
			private HashMap<Integer, HashSet<MovePair>> moveList = null;


			public IGraph()	{
				adjSet = new HashSet<Edge>();
				adjList = new HashMap<Integer, HashSet<Integer>>();
				nodeDegree = new HashMap<Integer, Integer>();
				moveList = new HashMap<Integer, HashSet<MovePair>>();
			}

			public IGraph(int initialCapacity)	{
				adjSet = new HashSet<Edge>(initialCapacity);
				adjList = new HashMap<Integer, HashSet<Integer>>(initialCapacity);
				nodeDegree = new HashMap<Integer, Integer>(initialCapacity);
				moveList = new HashMap<Integer, HashSet<MovePair>>(initialCapacity);
			}

			/**
			 * Add edge in Interference graph
			 * @param u
			 * @param v
			 */
			public boolean addEdge(Integer u, Integer v) {
				boolean ret = false;
				
				if(u == v) return ret;

				addNode(u);
				addNode(v);

				if(adjList.get(u).add(v)) {
					// element was really added
					nodeDegree.put(u, nodeDegree.get(u) + 1);
					adjSet.add(new Edge(u,v));
					ret = true;
				} else {
					ret = false;
				}

				if(adjList.get(v).add(u)) {
					// element was really added
					nodeDegree.put(v, nodeDegree.get(v) + 1);
					ret = true;
				} else {
					ret = false;
				}
				
				return ret;
			}

			/**
			 * 
			 * @param dst
			 * @param src
			 * @param moveInstr
			 */
			public void addMovePair(Integer dst, Integer src, OPT_Instruction moveInstr) {
				addNode(dst);
				addNode(src);

				moveList.get(src).add(new MovePair(dst, src, moveInstr));
				moveList.get(dst).add(new MovePair(dst, src, moveInstr));
			}

			public boolean deleteNode(Integer u) {
				return true;
			}

			/**
			 * 
			 * @param u
			 */
			public boolean addNode(Integer u) {
				if(!adjList.containsKey(u)) {
					// Initialization
					adjList.put(u, new HashSet<Integer>());
					moveList.put(u, new HashSet<MovePair>());
					nodeDegree.put(u, 0);
					return true;
				} else {
					return false;
				}
			}

			/**
			 * 
			 * @param u
			 * @param v
			 * @return
			 */
			public boolean containsEdge(Integer u, Integer v) {
				if(adjList.get(u).contains(v) && adjList.get(v).contains(u)) 
					return true;
				else
					return false;
			}

			/**
			 * Increment node degree
			 * @param u Node(must exists)
			 */
			public void incDegree(Integer u) {
				nodeDegree.put(u, nodeDegree.get(u) + 1);
			}

			/**
			 * Decrement node degree
			 * @param u Node(must exists)
			 */
			public void decDegree(Integer u) {
				nodeDegree.put(u, nodeDegree.get(u) - 1);
			}		

			/**
			 * Node degree
			 * @param u Node(must exists)
			 * @return
			 */
			public int getDegree(Integer u) {
				return nodeDegree.get(u);
			}

			/**
			 * Node count
			 * @return
			 */
			public int size() {
				return adjList.size();
			}

			public void merge(Integer u, Integer v) {
				Integer degree = 0;

				for(Integer t: adjList.get(v)) {
					if(adjList.get(u).add(t)) {
						degree ++;
					}
				}

				adjList.get(u).remove(v);

				adjList.remove(v);
				nodeDegree.remove(v);

				nodeDegree.put(u, nodeDegree.get(u) + degree);
			}

			/**
			 * Interference graph reinitialization
			 *
			 */
			public void clear() {
				adjSet.clear();
				adjList.clear();
				nodeDegree.clear();
				moveList.clear();

				adjSet = new HashSet<Edge>();
				adjList = new HashMap<Integer, HashSet<Integer>>();
				nodeDegree = new HashMap<Integer, Integer>();
				moveList = new HashMap<Integer, HashSet<MovePair>>();
			}

			/**
			 * Dump Interference Graph in GraphViz format
			 * @param grahName Graph name
			 */
			public void DumpDot(String graphName) {

				/* In production */

				System.out.println("graph " + graphName + " {");

				HashSet<MovePair> movePairs = new HashSet<MovePair>();
				for(Integer u : moveList.keySet()) {
					for(MovePair pair : moveList.get(u)) {
						movePairs.add(pair);
					}            	
				}
				for(MovePair pair : movePairs) {
					System.out.println(pair.Dump());
				}

				for(Integer u : adjList.keySet()) {
					System.out.println(mapIdToReg(u) + ";");
				}
				for(Edge edge : adjSet) {
					System.out.println(edge.Dump());
				}
				System.out.println("}");
			}
		}



		/* DUMP ROUTINES */

		public void dumpCFG(OPT_IR ir) {

			// Perform live analysis
			OPT_LiveAnalysis live = new OPT_LiveAnalysis(false, false);
			live.perform(ir); 

			for (OPT_BasicBlock bb = ir.cfg.entry(); bb != null; bb = (OPT_BasicBlock) bb.next) {

				bb.printExtended();

				System.out.println("LiveIn: ");

				for(Iterator<OPT_Register> it = live.getLiveRegistersOnEntry(bb).iterator(); it.hasNext();)
					System.out.print(it.next().toString() + " ");

				System.out.println("");

				System.out.println("LiveOut: ");

				for(Iterator<OPT_Register> it = live.getLiveRegistersOnExit(bb).iterator(); it.hasNext();)
					System.out.print(it.next().toString() + " ");

				System.out.println("");
				System.out.println("");
			}
		}

		public void dumpInstr(OPT_IR ir) {

			for (OPT_BasicBlock bb = ir.cfg.entry(); bb != null; bb = (OPT_BasicBlock) bb.next) {
				for (OPT_Instruction inst = bb.lastInstruction(); ; inst = inst.prevInstructionInCodeOrder()) {
					dumpInstr(inst);
					if(inst == bb.firstInstruction()) break;
				} 
			}
		}

		public void dumpInstr(OPT_Instruction inst)
		{
			System.out.println("INSTR: " + inst.toString());

			System.out.print("Defs: ");
			for (OPT_OperandEnumeration ops = inst.getDefs(); ops.hasMoreElements();) 
			{
				OPT_Operand op = ops.next();

				if (op.isRegister()) {
					OPT_Register r = op.asRegister().register;
					System.out.print(r.toString() + " ");
				}
			}
			System.out.println("");

			System.out.println("Use: ");
			for (OPT_OperandEnumeration ops = inst.getUses(); ops.hasMoreElements();) 
			{
				OPT_Operand op = ops.next();
				if (op.isRegister()) {
					OPT_Register r = op.asRegister().register;
					System.out.print(r.toString() + " ");
				}
			}
			System.out.println("");
		}
	}

	/**
	 * Insert Spill Code after register assignment.
	 */
	public static final class SpillCode extends OPT_CompilerPhase implements OPT_Operators {
		/**
		 * Return this instance of this phase. This phase contains no
		 * per-compilation instance fields.
		 * @param ir not used
		 * @return this
		 */
		public OPT_CompilerPhase newExecution(OPT_IR ir) {
			return this;
		}

		public boolean shouldPerform(OPT_Options options) {
			return true;
		}

		public String getName() {
			return "Spill Code";
		}

		public boolean printingEnabled(OPT_Options options, boolean before) {
			return false;
		}

		/**
		 *  @param ir the IR
		 */
		public void perform(OPT_IR ir) {
			replaceSymbolicRegisters(ir);

			if (ir.hasSysCall() || spilledSomething) {
				OPT_StackManager stackMan = (OPT_StackManager) ir.stackManager;
				stackMan.insertSpillCode();
			}

			if (VM.BuildForIA32 && !VM.BuildForSSE2Full) {
				OPT_Operators.helper.rewriteFPStack(ir);
			}
		}

		/**
		 *  Iterate over the IR and replace each symbolic register with its
		 *  allocated physical register.
		 *  Also used by ClassWriter
		 */
		public static void replaceSymbolicRegisters(OPT_IR ir) {
			for (OPT_InstructionEnumeration inst = ir.forwardInstrEnumerator(); inst.hasMoreElements();) {
				OPT_Instruction s = inst.next();
				for (OPT_OperandEnumeration ops = s.getOperands(); ops.hasMoreElements();) {
					OPT_Operand op = ops.next();
					if (op.isRegister()) {
						OPT_RegisterOperand rop = op.asRegister();
						OPT_Register r = rop.register;
						if (r.isSymbolic() && !r.isSpilled()) {
							OPT_Register p = OPT_RegisterAllocatorState.getMapping(r);
							if (VM.VerifyAssertions) VM._assert(p != null);
							rop.register = p;
						}
					}
				}
			}
		}
	}

	/**
	 * Update GC maps after register allocation but before inserting spill
	 * code.
	 */
	static final class UpdateGCMaps1 extends OPT_CompilerPhase {

		public boolean shouldPerform(OPT_Options options) {
			return true;
		}

		/**
		 * Return this instance of this phase. This phase contains no
		 * per-compilation instance fields.
		 * @param ir not used
		 * @return this
		 */
		public OPT_CompilerPhase newExecution(OPT_IR ir) {
			return this;
		}

		public String getName() {
			return "Update GCMaps 1";
		}

		public boolean printingEnabled(OPT_Options options, boolean before) {
			return false;
		}

		/**
		 *  Iterate over the IR-based GC map collection and for each entry
		 *  replace the symbolic reg with the real reg or spill it was allocated
		 *  @param ir the IR
		 */
		public void perform(OPT_IR ir) {

			for (OPT_GCIRMapElement GCelement : ir.MIRInfo.gcIRMap) {
				if (gcdebug) {
					VM.sysWrite("GCelement " + GCelement);
				}

				for (OPT_RegSpillListElement elem : GCelement.regSpillList()) {

					OPT_Register symbolic = elem.getSymbolicReg();

					if (gcdebug) {
						VM.sysWrite("get location for " + symbolic + '\n');
					}

					if (symbolic.isAllocated()) {
						OPT_Register ra = OPT_RegisterAllocatorState.getMapping(symbolic);
						elem.setRealReg(ra);
						if (gcdebug) { VM.sysWrite(ra + "\n"); }
					} else if (symbolic.isSpilled()) {
						int spill = symbolic.getSpillAllocated();
						elem.setSpill(spill);
						if (gcdebug) { VM.sysWrite(spill + "\n"); }
					} else {
						OPT_OptimizingCompilerException.UNREACHABLE("GraphColor", "register not alive:", symbolic.toString());
					}
				}
			}
		}
	}

	/**
	 * Update GC Maps again, to account for changes induced by spill code.
	 */
	static final class UpdateGCMaps2 extends OPT_CompilerPhase {
		/**
		 * Return this instance of this phase. This phase contains no
		 * per-compilation instance fields.
		 * @param ir not used
		 * @return this
		 */
		public OPT_CompilerPhase newExecution(OPT_IR ir) {
			return this;
		}

		public boolean shouldPerform(OPT_Options options) {
			return true;
		}

		public String getName() {
			return "Update GCMaps 2";
		}

		public boolean printingEnabled(OPT_Options options, boolean before) {
			return false;
		}

		/**
		 *  @param ir the IR
		 */
		public void perform(OPT_IR ir) {
			OPT_PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();
			OPT_ScratchMap scratchMap = ir.stackManager.getScratchMap();

			if (gcdebug) {
				System.out.println("SCRATCH MAP:\n" + scratchMap);
			}
			if (scratchMap.isEmpty()) return;

			// Walk over each instruction that has a GC point.
			for (OPT_GCIRMapElement GCelement : ir.MIRInfo.gcIRMap) {
				// new elements to add to the gc map
				HashSet<OPT_RegSpillListElement> newElements = new HashSet<OPT_RegSpillListElement>();

				OPT_Instruction GCinst = GCelement.getInstruction();

				// Get the linear-scan DFN for this instruction.
				int dfn = GCinst.scratch;

				if (gcdebug) {
					VM.sysWrite("GCelement at " + dfn + " , " + GCelement);
				}

				// a set of elements to delete from the GC Map
				HashSet<OPT_RegSpillListElement> toDelete = new HashSet<OPT_RegSpillListElement>(3);

				// For each element in the GC Map ...
				for (OPT_RegSpillListElement elem : GCelement.regSpillList()) {
					if (gcdebug) {
						VM.sysWrite("Update " + elem + "\n");
					}
					if (elem.isSpill()) {
						// check if the spilled value currently is cached in a scratch
						// register
						OPT_Register r = elem.getSymbolicReg();
						OPT_Register scratch = scratchMap.getScratch(r, dfn);
						if (scratch != null) {
							if (gcdebug) {
								VM.sysWrite("cached in scratch register " + scratch + "\n");
							}
							// we will add a new element noting that the scratch register
							// also must be including in the GC map
							OPT_RegSpillListElement newElem = new OPT_RegSpillListElement(r);
							newElem.setRealReg(scratch);
							newElements.add(newElem);
							// if the scratch register is dirty, then delete the spill
							// location from the map, since it doesn't currently hold a
							// valid value
							if (scratchMap.isDirty(GCinst, r)) {
								toDelete.add(elem);
							}
						}
					} else {
						// check if the physical register is currently spilled.
						int n = elem.getRealRegNumber();
						OPT_Register r = phys.get(n);
						if (scratchMap.isScratch(r, dfn)) {
							// The regalloc state knows where the physical register r is
							// spilled.
							if (gcdebug) {
								VM.sysWrite("CHANGE to spill location " + OPT_RegisterAllocatorState.getSpill(r) + "\n");
							}
							elem.setSpill(OPT_RegisterAllocatorState.getSpill(r));
						}
					}

				}
				// delete all obsolete elements
				for (OPT_RegSpillListElement deadElem : toDelete) {
					GCelement.deleteRegSpillElement(deadElem);
				}

				// add each new Element to the gc map
				for (OPT_RegSpillListElement newElem : newElements) {
					GCelement.addRegSpillElement(newElem);
				}
			}
		}


	}

	/**
	 * Update GC maps after register allocation but before inserting spill
	 * code.
	 */
	public static final class UpdateOSRMaps extends OPT_CompilerPhase {

		public boolean shouldPerform(OPT_Options options) {
			return true;
		}

		/**
		 * Constructor for this compiler phase
		 */
		private static final Constructor<OPT_CompilerPhase> constructor = getCompilerPhaseConstructor(UpdateOSRMaps.class);

		/**
		 * Get a constructor object for this compiler phase
		 * @return compiler phase constructor
		 */
		public Constructor<OPT_CompilerPhase> getClassConstructor() {
			return constructor;
		}

		public String getName() {
			return "Update OSRMaps";
		}

		public boolean printingEnabled(OPT_Options options, boolean before) {
			return false;
		}

		private OPT_IR ir;

		/*
		 * Iterate over the IR-based OSR map, and update symbolic registers
		 * with real reg number or spill locations.
		 * Verify there are only two types of operands:
		 *    OPT_ConstantOperand
		 *    OPT_RegisterOperand
		 *        for integer constant, we save the value of the integer
		 *
		 * The LONG register has another half part.
		 *
		 * CodeSpill replaces any allocated symbolic register by
		 * physical registers.
		 */
		public void perform(OPT_IR ir) throws OPT_OptimizingCompilerException {

			/*			if(ir.method.getName().toString().contentEquals(new StringBuffer("hashCode"))) {
				System.out.println("After: ");
				dumpCFG(ir);
			}*/

			this.ir = ir;

			// list of OsrVariableMapElement
			//LinkedList<OSR_VariableMapElement> mapList = ir.MIRInfo.osrVarMap.list;
			//for (int numOsrs=0, m=mapList.size(); numOsrs<m; numOsrs++) {
			//  OSR_VariableMapElement elm = mapList.get(numOsrs);
			/* for each osr instruction */
			for (OSR_VariableMapElement elm : ir.MIRInfo.osrVarMap.list) {

				// for each inlined method
				//LinkedList<OSR_MethodVariables> mvarsList = elm.mvars;                   XXX Remove once proven correct
				//for (int numMvars=0, n=mvarsList.size(); numMvars<n; numMvars++) {
				//  OSR_MethodVariables mvar = mvarsList.get(numMvars);
				for (OSR_MethodVariables mvar : elm.mvars) {

					// for each tuple
					//LinkedList<OSR_LocalRegPair> tupleList = mvar.tupleList;
					//for (int numTuple=0, k=tupleList.size(); numTuple<k; numTuple++) {
					//OSR_LocalRegPair tuple = tupleList.get(numTuple);
					for (OSR_LocalRegPair tuple : mvar.tupleList) {

						OPT_Operand op = tuple.operand;
						if (op.isRegister()) {
							OPT_Register sym_reg = ((OPT_RegisterOperand) op).register;

							setRealPosition(tuple, sym_reg);

							// get another half part of long register
							if (VM.BuildFor32Addr && (tuple.typeCode == OSR_Constants.LongTypeCode)) {

								OSR_LocalRegPair other = tuple._otherHalf;
								OPT_Operand other_op = other.operand;

								if (VM.VerifyAssertions) VM._assert(other_op.isRegister());

								OPT_Register other_reg = ((OPT_RegisterOperand) other_op).register;
								setRealPosition(other, other_reg);
							}
							/* According to OPT_ConvertToLowLevelIR, StringConstant, LongConstant,
							 * NullConstant, FloatConstant, and DoubleConstant are all materialized
							 * The only thing left is the integer constant.
							 * POTENTIAL DRAWBACKS: since any long, float, and double are moved
							 * to register and treated as use, it may consume more registers and
							 * add unnecessary MOVEs.
							 *
							 * Perhaps, OPT_ConvertToLowLevelIR can skip OsrPoint instruction.
							 */
						} else if (op.isIntConstant()) {
							setTupleValue(tuple, OSR_Constants.ICONST, ((OPT_IntConstantOperand) op).value);
							if (VM.BuildFor32Addr && (tuple.typeCode == OSR_Constants.LongTypeCode)) {
								OSR_LocalRegPair other = tuple._otherHalf;
								OPT_Operand other_op = other.operand;

								if (VM.VerifyAssertions) VM._assert(other_op.isIntConstant());
								setTupleValue(other, OSR_Constants.ICONST, ((OPT_IntConstantOperand) other_op).value);
							}
						} else if (op.isAddressConstant()) {
							setTupleValue(tuple, OSR_Constants.ACONST, ((OPT_AddressConstantOperand) op).value.toWord());
						} else if (VM.BuildFor64Addr && op.isLongConstant()) {
							setTupleValue(tuple, OSR_Constants.LCONST, Word.fromLong(((OPT_LongConstantOperand) op).value));
						} else {
							throw new OPT_OptimizingCompilerException("OPT_GraphColor", "Unexpected operand type at ", op.toString());
						} // for the op type
					} // for each tuple
				} // for each inlined method
			} // for each osr instruction

			this.ir = null;
		} // end of method

		void setRealPosition(OSR_LocalRegPair tuple, OPT_Register sym_reg) {
			if (VM.VerifyAssertions) VM._assert(sym_reg != null);

			int REG_MASK = 0x01F;

			// now it is not symbolic register anymore.
			// is is really confusing that sometimes a sym reg is a phy,
			// and sometimes not.
			if (sym_reg.isAllocated()) {
				setTupleValue(tuple, OSR_Constants.PHYREG, sym_reg.number & REG_MASK);
			} else if (sym_reg.isPhysical()) {
				setTupleValue(tuple, OSR_Constants.PHYREG, sym_reg.number & REG_MASK);
			} else if (sym_reg.isSpilled()) {
				setTupleValue(tuple, OSR_Constants.SPILL, sym_reg.getSpillAllocated());
			} else {
				dumpIR(ir, "PANIC");
				throw new RuntimeException("OPT_GraphColor PANIC in OSRMAP, " + sym_reg + " is not alive");
			}
		} // end of setRealPosition

		static void setTupleValue(OSR_LocalRegPair tuple, int type, int value) {
			tuple.valueType = type;
			tuple.value = Word.fromIntSignExtend(value);
		} // end of setTupleValue

		static void setTupleValue(OSR_LocalRegPair tuple, int type, Word value) {
			tuple.valueType = type;
			tuple.value = value;
		} // end of setTupleValue
	} // end of inner class
}
