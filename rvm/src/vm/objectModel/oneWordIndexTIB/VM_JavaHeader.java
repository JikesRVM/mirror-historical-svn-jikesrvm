/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

//-#if RVM_WITH_OPT_COMPILER
import instructionFormats.*;
//-#endif

/**
 * Defines the JavaHeader portion of the object header for the 
 * JikesRVM object model. <p>
 * This object model uses a one-word header for most scalar objects, and
 * a two-word header for scalar objects of classes with synchronized
 * methods<p>
 *
 * In this object model, the bottom N bits of the TIB word are the
 * available bits, and the rest of the TIB word holds the index into the
 * JTOC holding the TIB reference.
 *
 * @see VM_NurseryObjectModel
 *
 * @author David Bacon
 * @author Steve Fink
 * @author Dave Grove
 */
public final class VM_JavaHeader extends VM_NurseryObjectModel 
                                            implements VM_Uninterruptible
					    //-#if RVM_WITH_OPT_COMPILER
					    ,OPT_Operators
					    //-#endif
					    {
  /**
   * An upper bound on the JTOC size
   */
  private static final int LOG_JTOC_MAX_SIZE = 30;
  /** 
   * How many bits in the header are available for the GC and MISC headers? 
   * */
  public static final int NUM_AVAILABLE_BITS = 32 - LOG_JTOC_MAX_SIZE;

  /**
   * The mask that defines the TIB index in the one-word header.
   */
  private static final int TIB_MASK = 0xffffffff << NUM_AVAILABLE_BITS;

  /**
   * The mask that defines the available bits the one-word header.
   */
  private static final int BITS_MASK = ~TIB_MASK;

  static {
    if (VM.VerifyAssertions) {
      VM.assert(VM_MiscHeader.REQUESTED_BITS + VM_AllocatorHeader.REQUESTED_BITS <= NUM_AVAILABLE_BITS);
      if (VM_Collector.MOVES_OBJECTS) VM.assert(NOT_REACHED);
    }
  }

  /**
   * Get the TIB for an object.
   */
  public static Object[] getTIB(Object o) { 
    VM_Magic.pragmaInline();
    int tibWord = VM_Magic.getIntAtOffset(o,TIB_OFFSET) & TIB_MASK;
    int tibIndex = tibWord >>> NUM_AVAILABLE_BITS;
    return VM_Statics.getSlotContentsAsObjectArray(tibIndex);
  }
  
  /**
   * Set the TIB for an object.
   */
  public static void setTIB(Object ref, Object[] tib) {
    VM_Magic.pragmaInline();
    int tibSlot = VM_Magic.objectAsType(tib[0]).getTibSlot();
    int tibWord = (VM_Magic.getIntAtOffset(o, TIB_OFFSET) & BITS_MASK) | (tibSlot << NUM_AVAILABLE_BITS);
    VM_Magic.setIntAtOffset(o, TIB_OFFSET, tibWord);

  }

  /**
   * Set the TIB for an object.
   * Note: Beware; this function clears the additional bits.
   */
  public static void setTIB(BootImageInterface bootImage, int refOffset, int tibAddr, VM_Type type) {
    int tibSlot = type.getTibSlot();
    int tibWord = tibSlot << NUM_AVAILABLE_BITS;
    bootImage.setAddressWord(refOffset + TIB_OFFSET, tibWord);
  }

  /**
   * Process the TIB field during copyingGC.  NOT IMPLEMENTED, since
   * copyingGC not currently supported.
   */
  public static void gcProcessTIB(int ref) {
    VM.assert(NOT_REACHED);
  }

  /**
   * Get a reference to the TIB for an object.
   *
   * @param jdpService
   * @param address address of the object
   */
  public static ADDRESS getTIB(JDPServiceInterface jdpService, ADDRESS ptr) {
    int tibWord = jdpService.readMemory(ptr + TIB_OFFSET) & TIB_MASK;
    int tibIndex = tibWord >>> NUM_AVAILABLE_BITS;
    VM.assert(NOT_REACHED);
//    TODO: implement the following method!!
//    return jdpService.readJTOCSlot(tibIndex);
    return -1;
  }

  /**
   * Perform any required initialization of the JAVA portion of the header.
   * @param ref the object ref to the storage to be initialized
   * @param tib the TIB of the instance being created
   * @param size the number of bytes allocated by the GC system for this object.
   * @param isScalar are we initializing a scalar (true) or array (false) object?
   */
  public static void initializeHeader(Object ref, Object[] tib, int size, boolean isScalar) {
    // nothing to do (TIB set by VM_ObjectModel)
  }

  /**
   * Perform any required initialization of the JAVA portion of the header.
   * @param bootImage the bootimage being written
   * @param ref the object ref to the storage to be initialized
   * @param tib the TIB of the instance being created
   * @param size the number of bytes allocated by the GC system for this object.
   * @param isScalar are we initializing a scalar (true) or array (false) object?
   */
  public static void initializeHeader(BootImageInterface bootImage, int ref, 
				      Object[] tib, int size, boolean isScalar) {
    // (TIB set by BootImageWriter2)

    // If Collector.MOVES_OBJECTS then we must preallocate hash code
    // and set the barrier bit to avoid problems with non-atomic access
    // to available bits byte corrupting hash code by write barrier sequence.
    if (VM_Collector.MOVES_OBJECTS && VM_Collector.NEEDS_WRITE_BARRIER) {
      VM.assert(NOT_REACHED);
    }
  }


  /**
   * The following method will emit code that moves a reference to an
   * object's TIB into a destination register.
   *
   * @param asm the assembler object to emit code with
   * @param dest the number of the destination register
   * @param object the number of the register holding the object reference
   */
  //-#if RVM_FOR_POWERPC
  public static void baselineEmitLoadTIB(VM_Assembler asm, int dest, 
                                         int object) {
    if (VM.VerifyAssertions) VM.assert(NUM_AVAILABLE_BITS == 2);
    int ME = 31 - NUM_AVAILABLE_BITS;
    asm.emitL(dest, TIB_OFFSET, object);
    // The following clears the low-order bits. See p.119 of PowerPC book
    asm.emitRLWINM(dest, dest, 0, 0, ME);
    // NOTE: No shift required because NUM_AVAILABLE_BITS == 2. TODO: generalize.
    // Load the result off the JTOC
    asm.emitLX(dest,JTOC,dest);
  }
  //-#elif RVM_FOR_IA32
  public static void baselineEmitLoadTIB(VM_Assembler asm, byte dest, 
                                         byte object) {
    asm.emitMOV_Reg_RegDisp(dest, object, TIB_OFFSET);
    asm.emitAND_Reg_Imm(dest,TIB_MASK);
    asm.emitMOV_Reg_RegDisp(dest,JTOC,dest);
  }
  /**
   * The following method will emit code that pushes a reference to an
   * object's TIB onto the stack.
   *
   * DANGER, DANGER!!! This method kills the value in the 'object'
   * register.
   *
   * TODO: consider deprecating this method; rewriting the appropriate
   * sequences in the baseline compiler to use a scratch register.
   *
   * @param asm the assembler object to emit code with
   * @param object the number of the register holding the object reference
   */
  public static void baselineEmitPushTIB(VM_Assembler asm, byte object) {
    baselineEmitLoadTIB(asm,object,object);
    asm.emitPUSH_Reg(object);
  }
  //-#endif

  //-#if RVM_WITH_OPT_COMPILER
  /**
   * Mutate a GET_OBJ_TIB instruction to the LIR
   * instructions required to implement it.
   * 
   * @param s the GET_OBJ_TIB instruction to lower
   * @param ir the enclosing OPT_IR
   */
  public static void lowerGET_OBJ_TIB(OPT_Instruction s, OPT_IR ir) {
    OPT_RegisterOperand result = GuardedUnary.getClearResult(s);
    OPT_RegisterOperand ref = GuardedUnary.getClearVal(s).asRegister();
    OPT_Operand guard = GuardedUnary.getClearGuard(s);
    OPT_RegisterOperand headerWord = OPT_ConvertToLowLevelIR.InsertLoadOffset(s, ir, INT_LOAD, 
                                                                              VM_Type.IntType, ref, TIB_OFFSET, guard);
    OPT_RegisterOperand tibOffset = OPT_ConvertToLowLevelIR.InsertBinary(s, ir, INT_AND, 
                                                                         VM_Type.IntType, headerWord, 
                                                                         new OPT_IntConstantOperand(TIB_MASK));
    // shift the tibIdx to a byte offset.
    if (NUM_AVAILABLE_BITS > 2) {
      tibOffset = OPT_ConvertToLowLevelIR.InsertBinary(s, ir, INT_USHR, VM_Type.IntType, tibOffset, 
                                                       new OPT_IntConstantOperand(NUM_AVAILABLE_BITS - 2));
    } else if (NUM_AVAILABLE_BITS < 2) {
      tibOffset = OPT_ConvertToLowLevelIR.InsertBinary(s, ir, INT_SHL, VM_Type.IntType, tibOffset, 
                                                       new OPT_IntConstantOperand(2 - NUM_AVAILABLE_BITS));
    }

    Load.mutate(s, INT_LOAD, result, ir.regpool.makeJTOCOp(ir,s), tibOffset, null);

  }

  //-#endif
}
