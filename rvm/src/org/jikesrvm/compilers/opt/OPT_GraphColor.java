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
import org.jikesrvm.ArchitectureSpecific.OPT_PhysicalRegisterConstants;
import org.jikesrvm.ArchitectureSpecific.OPT_PhysicalRegisterSet;
import org.jikesrvm.ArchitectureSpecific.OPT_RegisterRestrictions;
import org.jikesrvm.ArchitectureSpecific.OPT_StackManager;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.VM_TypeReference;
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
import org.jikesrvm.compilers.opt.ir.OPT_RegSpillListElement;
import org.jikesrvm.compilers.opt.ir.OPT_Register;
import org.jikesrvm.compilers.opt.ir.OPT_RegisterOperand;
import org.jikesrvm.compilers.opt.ir.Move;
import org.jikesrvm.compilers.opt.ir.MIR_Move;
import org.jikesrvm.compilers.opt.ir.OPT_StackLocationOperand;
import org.jikesrvm.osr.OSR_Constants;
import org.jikesrvm.osr.OSR_LocalRegPair;
import org.jikesrvm.osr.OSR_MethodVariables;
import org.jikesrvm.osr.OSR_VariableMapElement;
import org.vmmagic.unboxed.Word;


/**
 * Main driver for linear scan register allocation.
 */
public final class OPT_GraphColor extends OPT_OptimizationPlanCompositeElement {

	/**
	 * Build this phase as a composite of others.
	 */
	public OPT_GraphColor() {
		super("Graph Color Composite Phase",
				new OPT_OptimizationPlanElement[]{
				new OPT_OptimizationPlanAtomicElement(new GraphColor())
				,new OPT_OptimizationPlanAtomicElement(new SpillCode())
				,new OPT_OptimizationPlanAtomicElement(new UpdateOSRMaps())
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
	private static final boolean debug = false;
	private static final boolean verboseDebug = false;

	/**
	 * Attempt to coalesce to eliminate register moves?
	 */
	static final boolean COALESCE_MOVES = true;


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
		 * The governing IR
		 */
		protected OPT_IR ir;

		/**
		 *  @param ir the IR
		 */
		public void perform(OPT_IR ir) {
			if(debug) {
				System.out.println(">>>>>>>>>>>>>>>>   START   <<<<<<<<<<<<<<<<<<<<");
				System.out.println("method " + ir.method.getName().toString());
			}

			this.ir = ir;
	
			IteratedCoalescing();
			
			if(debug) {
				System.out.println("<<<<<<<<<<<<<<<<    END    >>>>>>>>>>>>>>>>>>>>>\n");
			}
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

		// registers that have been coalesced
		public HashSet<Integer> coalescedNodes = new HashSet<Integer>();

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

		// when a move (u,v) has been coalesced, and v put in coalescedNodes, then alias(v) = u
		public HashMap<Integer, Integer> alias = new HashMap<Integer, Integer>(); 

		/**
		 * The color chosen by the algorithm for a node. For precolored nodes this 
		 * is initialized to the given color
		 */
		public HashMap<Integer, Integer> color = new HashMap<Integer, Integer>(); 

		/**
		 * Interference graph
		 */ 
		protected IGraph igraph = new IGraph();

		// OPT_Register to Integer Id mapping
		public HashMap<OPT_Register, Integer> RegToId = new HashMap<OPT_Register, Integer>();

		// Integer Id to OPT_Register mapping
		public HashMap<Integer, OPT_Register> IdToReg = new HashMap<Integer, OPT_Register>();

		// 
		public int currentRegId = -1;

		// number of colors
		public int K;

		/**
		 * Map from OPT_Register to Integer Id
		 * @param reg JikesRVM register class
		 * @return Unique identifier
		 */
		public Integer mapRegToId(OPT_Register reg) {

			if(!RegToId.containsKey(reg)) {
				currentRegId ++;
				RegToId.put(reg, currentRegId);
				IdToReg.put(currentRegId, reg);
				return currentRegId;
			}

			return RegToId.get(reg);
		}
		
		/**
		 * Get register identifier
		 * @param reg JikesRVM register class
		 * @return Unique identifier
		 */
		public Integer getRegId(OPT_Register reg) {

			if(!RegToId.containsKey(reg)) {
				throw new RuntimeException("OPT_GraphColor PANIC GetRegId: Identifier does not exists.");
				//System.out.println("[GetRegId]: Error. identifier does no exists");
			}

			return RegToId.get(reg);
		}
		
		/**
		 * Map Integer Id to OPT_Register 
		 * @param id Unique register id
		 * @return JikesRVM register class
		 */
		public OPT_Register mapIdToReg(Integer id) {

			if(!IdToReg.containsKey(id)) {
				throw new RuntimeException("OPT_GraphColor PANIC MapIdToReg: Identifier does not exists.");
				//System.out.println("[MapIdToReg]: Error. identifier does no exists");
			}
			
			return IdToReg.get(id);
		}
		
		/**
		 * 
		 *
		 */
		public void IteratedCoalescing() {

			
			if(debug) {
				for (OPT_Register reg = ir.regpool.getFirstSymbolicRegister(); reg != null; reg = reg.getNext()) {
					System.out.print(reg.toString() + " ");
				}
				System.out.println("");
			}
			
			
			initPrecoloredSet();
			
			
			for (OPT_Register reg = ir.regpool.getFirstSymbolicRegister(); reg != null; reg = reg.getNext()) {
				initial.add(mapRegToId(reg));
				igraph.addNode(getRegId(reg));
			}
			
			// number of symbolic registers
			numSymbRegs = initial.size();
			
			//OPT_CoalesceMoves cm = new OPT_CoalesceMoves();
			//cm.perform(ir);
			
			build();
			
			if(debug) {
				System.out.println("igraph.size() = " + igraph.size());
			}
			
			
			
			main();
			
			if(debug) {
				dumpStat();
			}
			
			//dumpMap();
		}

		/**
		 * Main procedure
		 * 
		 */
		public void main()
		{
			numIterations ++;
			
			// STUB 
			for(Integer n : initial)
				selectStack.push(n);

			if(false) { // STUB
				
				
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
			if(spilledNodes.size() > 0) {

				rewriteProgram();

				// STUB
				// next iteration
				//main();

			}
		}

		/*
		 * Build interference graph
		 */
		public void build() {

			// Perform live analysis
			OPT_LiveAnalysis liveAnalysis = new OPT_LiveAnalysis(false, false);
			liveAnalysis.perform(ir); 

			// Current live set
			HashSet<OPT_Register> live;

			for (OPT_BasicBlock bb = ir.cfg.entry(); bb != null; bb = (OPT_BasicBlock) bb.next) {

				if(bb == null) {
					System.out.println("build: BUG*** bb == null");
				}

				// live = liveOut(bb) 

				live = liveAnalysis.getLiveRegistersOnExit(bb);

				if(verboseDebug)
					this.DumpBB(bb, liveAnalysis);

				/*
				 * BUG????
				 */
				for (OPT_Instruction inst = bb.lastInstruction(); inst != bb.firstInstruction(); inst = inst.prevInstructionInCodeOrder()) {

					// isMoveInstruction(I) 
					if(MIR_Move.conforms(inst)) {

						// live = live - Use(I) 
						for (OPT_OperandEnumeration ops = inst.getUses(); ops.hasMoreElements();) {
							OPT_Operand op = ops.next();
							if (op.isRegister()) {
								live.remove(op.asRegister().register);
							}
						}


						// only mov r1,r2

						if(MIR_Move.getResult(inst).isRegister() && MIR_Move.getValue(inst).isRegister()) {

							igraph.addMovePair(
									this.mapRegToId(MIR_Move.getResult(inst).asRegister().register), 
									this.mapRegToId(MIR_Move.getValue(inst).asRegister().register),
									inst);

							this.workListMoves.add( 
									new MovePair(
											this.mapRegToId(MIR_Move.getResult(inst).asRegister().register), 
											this.mapRegToId(MIR_Move.getValue(inst).asRegister().register), 
											inst)
							);

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

								OPT_Register r1 = l.next();
								OPT_Register r2 = op.asRegister().register;

								if(r1 != r2)
								{
									igraph.addEdge(mapRegToId(r1), mapRegToId(r2));
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
				}
			}
			
			
			numMoves = Math.max(numMoves, workListMoves.size());
			

			
			
			if(verboseDebug) {
				igraph.DumpDot(ir.method.getName().toString());

				HashSet<MovePair> movePairs = new HashSet<MovePair>();
				for(Integer u : igraph.moveList.keySet()) {
					for(MovePair pair : igraph.moveList.get(u)) {
						movePairs.add(pair);
					}            	
				}
				System.out.println("Moves in IGraph: # " + movePairs.size());
				for(MovePair pair : movePairs) {
					System.out.println(pair.moveInstr.toString());
				}
			}
		}

		/**
		 * READY
		 * 
		 */
		public void makeWorkList() {

			for(Integer n : initial) {
					// modify heuristic for irregular architecture
					if(igraph.getDegree(n) > K) {
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
			int d = igraph.getDegree(m);
			igraph.decDegree(m);
			if(d == K) {
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
		
		public Integer getAlias(Integer n) {
			if(coalescedNodes.contains(n)) {
				return getAlias(alias.get(n));
			}
			else
				return n;
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
			freezeMoves(m);
		}

		public void assignColors() {
			HashSet<Integer> okColors = new HashSet<Integer>();
			HashSet<Integer> fixed = new HashSet<Integer>();
			
		    OPT_PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();
		    OPT_Register eax = phys.getEAX();
		    OPT_Register edx = phys.getEDX();
		    OPT_Register ebx = phys.getEBX();
		    
		    if(debug) {
		    	System.out.println("selectStack.size() = " + selectStack.size());
		    }
			
			while(!selectStack.empty()) {
				Integer n = selectStack.pop();
				
				// STUB
				if(mapIdToReg(n).isFloat() || mapIdToReg(n).isDouble()) {
					System.out.println("Float: " + mapIdToReg(n).toString());
					spilledNodes.add(n);
					continue;
				}

				// okColors := {0,1,2,..,K-1}

				okColors.clear();
				okColors.add(getRegId(eax));
				okColors.add(getRegId(edx));
				okColors.add(getRegId(ebx));
				

				fixed.clear();
				fixed.addAll(coloredNodes);
				fixed.addAll(precolored);
				
				//System.out.print("Reg: " + mapIdToReg(n).toString() + "| ");

				for(Integer w : igraph.adjList.get(n)) {
					//System.out.print(mapIdToReg(w).toString() + "[" + w + "]");
					if(fixed.contains(getAlias(w))) {
						if(precolored.contains(w)) {
							okColors.remove(getAlias(w));
							//System.out.print("(-" + getAlias(w) + ")");
						} else {
							okColors.remove(color.get(getAlias(w)));
							//System.out.print("(-" + color.get(getAlias(w)) + ")");
						}
					}
					//System.out.print(" ");
				}
				
				//System.out.print("okColors ");
				for(Integer c : okColors) {
					//System.out.print(mapIdToReg(c).toString() + "[" + c + "] ");
				}
				

				if(okColors.isEmpty()) {
					spilledNodes.add(n);
					//System.out.print("spilled");
				} else {
					coloredNodes.add(n);
					Integer c = okColors.iterator().next();

					color.put(n, c);

					if(debug) 
					{
						System.out.println();
						System.out.println(mapIdToReg(n) + ">>" + mapIdToReg(c).toString());
					}
					mapIdToReg(n).mapsToRegister = mapIdToReg(c);
					OPT_RegisterAllocatorState.mapOneToOne(mapIdToReg(n), mapIdToReg(c));
				}
				//System.out.println();
			}
			
			numSpilledRegs += spilledNodes.size();
			
			for(Integer n : coalescedNodes) {
				color.put(n, color.get(getAlias(n)));
			}
		}

		public void rewriteProgram() {

		}
		
		public void initPrecoloredSet() {
			OPT_PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();
			
			if(debug) {
				System.out.println("initPrecolored: ");
			}
			
		    for (Enumeration<OPT_Register> e = phys.enumerateAll(); e.hasMoreElements();) {
			      OPT_Register nv = e.nextElement();
			      if(nv != null) {
			    	  
			    	  if(debug) {
			    		  System.out.print(nv.toString() + " ");
			    	  }
			    	  precolored.add(mapRegToId(nv));
			      }
		    }
		    
		    
		    if(debug) {
		    	System.out.println("");
		    }
		}
		
		public void dumpMap() {
			System.out.println("Map Table Dump:");
			for(OPT_Register r : RegToId.keySet()) {
				System.out.println(r.toString() + "->" + RegToId.get(r).toString());
			}
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
			public void addEdge(Integer u, Integer v) {

				if(u == v) return;

				addNode(u);
				addNode(v);

				if(adjList.get(u).add(v)) {
					// element was really added
					nodeDegree.put(u, nodeDegree.get(u) + 1);
					adjSet.add(new Edge(u,v));
				}

				if(adjList.get(v).add(u)) {
					// element was really added
					nodeDegree.put(v, nodeDegree.get(v) + 1);
				}
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

		/*
		 * Need:
		 * 1. GraphViz representation of IG
		 * 2. VCG representation of CFG
		 */
		public boolean dump_def_use = false;
		public boolean dump_live_set = false;
		public boolean dump_reg = false;
		public boolean dump_moves = false;
		public boolean dump_build = true;

		public void DumpBB(OPT_BasicBlock bb, OPT_LiveAnalysis live) {
			if(bb == null) return;

			bb.printExtended();

			if(this.dump_live_set)
			{
				System.out.println("Live on entry:");

				for(Iterator<OPT_Register> it = live.getLiveRegistersOnEntry(bb).iterator(); it.hasNext();)
					System.out.print(it.next().toString() + " ");

				System.out.println("");

				System.out.println("Live on exit:");

				for(Iterator<OPT_Register> it = live.getLiveRegistersOnExit(bb).iterator(); it.hasNext();)
					System.out.print(it.next().toString() + " ");

				System.out.println("");
				System.out.println("");
			}
		}

		public void DumpInstr() {

		}

		public void Dump(OPT_IR ir)
		{


			HashSet<OPT_Register> reg = new HashSet<OPT_Register>();
			LinkedList<OPT_Instruction> moves = new LinkedList<OPT_Instruction>();

			int i = 0;
			for (OPT_BasicBlock bb = ir.cfg.entry(); bb != null; bb = (OPT_BasicBlock) bb.nextSorted) {

				for (OPT_Instruction inst = bb.firstInstruction(); inst != bb.lastInstruction(); 
				inst = inst.nextInstructionInCodeOrder()) {

					if(this.dump_def_use)
					{
						System.out.println("INSTR: " + inst.toString());

						System.out.println("Defs: ");
						for (OPT_OperandEnumeration ops = inst.getDefs(); ops.hasMoreElements();) 
						{
							OPT_Operand op = ops.next();
							if (op.isRegister()) {
								OPT_Register r = op.asRegister().register;
								System.out.print(r.toString() + " (R");
								if(op.isAddress()) {
									System.out.print(",A");
								}
								if(op.isMemory()) {
									System.out.print(",M");
								}								
								System.out.print(") ");

								System.out.println("");
							}


						}
						System.out.println("Use: ");
						for (OPT_OperandEnumeration ops = inst.getUses(); ops.hasMoreElements();) 
						{
							OPT_Operand op = ops.next();
							if (op.isRegister()) {
								OPT_Register r = op.asRegister().register;
								System.out.print(r.toString() + " (R");
								if(op.isAddress()) {
									System.out.print(",A");
								}
								if(op.isMemory()) {
									System.out.print(",M");
								}								
								System.out.print(") ");

								System.out.println("");
							}
						}
						System.out.println("");
						System.out.println("");
					}

					if(inst.isMove())
						moves.add(inst);

					for (OPT_OperandEnumeration ops = inst.getOperands(); ops.hasMoreElements();) {
						OPT_Operand op = ops.next();
						if (op.isRegister()) {
							OPT_RegisterOperand rOp = op.asRegister();
							OPT_Register r = rOp.register;
							reg.add(r);
						}
					}
				}
			}

			if(this.dump_reg)
			{
				i = 0;
				System.out.println("# Registers: " + reg.size());
				for(Iterator<OPT_Register> it = reg.iterator(); it.hasNext();)
				{
					i++;
					OPT_Register r = it.next();

					System.out.print(i);
					if(r.isPhysical())
						System.out.print(" p");
					if(r.isSymbolic())
						System.out.print(" s");

					System.out.print(": ");
					System.out.println(r.toString()); 
				}
			}

			if(this.dump_moves)
			{
				i = 0;
				System.out.println("# Moves: " + moves.size());
				for(Iterator<OPT_Instruction> it = moves.iterator(); it.hasNext();)
				{
					i++;
					System.out.println(i + ": " + it.next().toString()); 
				}
			}

			System.out.println("OPT_GraphColor IR: **************************************************************");
			if(this.dump_live_set)
			{	
				OPT_LiveAnalysis live = new OPT_LiveAnalysis(false, false);
				live.perform(ir);

				for (OPT_BasicBlock bb = ir.cfg.entry(); bb != null; bb = (OPT_BasicBlock) bb.nextSorted) {
					bb.printExtended();


					System.out.println("Live on entry:");

					for(Iterator<OPT_Register> it = live.getLiveRegistersOnEntry(bb).iterator(); it.hasNext();)
						System.out.print(it.next().toString() + " ");

					System.out.println("");

					System.out.println("Live on exit:");

					for(Iterator<OPT_Register> it = live.getLiveRegistersOnExit(bb).iterator(); it.hasNext();)
						System.out.print(it.next().toString() + " ");

					System.out.println("");
					System.out.println("");

				}	
			}

			System.out.println("OPT_GraphColor End");

			System.out.println("");
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


			// Generate spill code if necessary

			/*
			VM.sysWrite("################# method " + ir.method.getName().toString() + "\n");
			VM.sysWrite("Before\n");
			for (OPT_BasicBlock bb = ir.cfg.entry(); bb != null; bb = (OPT_BasicBlock) bb.nextSorted) {
				bb.printExtended();
			}
			*/

			
			
			mySpill(ir);

			/*
			if(ir.method.getName().toString().equalsIgnoreCase("main")) 
			{
				System.out.println("Spill apply");
				replaceSymbolicRegisters(ir);
			}
			*/
			
			replaceSymbolicRegisters(ir);


			//if (ir.hasSysCall() || ir.MIRInfo.linearScanState.spilledSomething) {
			if (true) {
				OPT_StackManager stackMan = (OPT_StackManager) ir.stackManager;
				stackMan.insertSpillCode();
			}

			if (VM.BuildForIA32 && !VM.BuildForSSE2Full) {
				OPT_Operators.helper.rewriteFPStack(ir);
			}

			/*
			VM.sysWrite("After\n");

			for (OPT_BasicBlock bb = ir.cfg.entry(); bb != null; bb = (OPT_BasicBlock) bb.nextSorted) {
				bb.printExtended();
			}
			*/
		}

		public void mySpill(OPT_IR ir) {

			int d = 0;
			//System.out.println(ir.method.getName().toString());
			for (OPT_Register reg = ir.regpool.getFirstSymbolicRegister(); reg != null; reg = reg.getNext()) {
				
				// check for allocated register
				if(OPT_RegisterAllocatorState.getMapping(reg) != null) {
					continue;
				}
				
				//if(reg.isValidation()) continue;
				
				
				
				//System.out.print(reg.toString() + " ");
				
				/*
				if(ir.method.getName().toString().equalsIgnoreCase("main")) {
					if((OPT_RegisterAllocatorState.getMapping(reg) != null) && (d < 10))
					{
						d++;
						System.out.println("RRRRR: " + reg.toString());
						continue;
					}
				}
				*/
				
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
							throw new OPT_OptimizingCompilerException("OPT_LinearScan", "Unexpected operand type at ", op.toString());
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
				throw new RuntimeException("OPT_LinearScan PANIC in OSRMAP, " + sym_reg + " is not alive");
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
