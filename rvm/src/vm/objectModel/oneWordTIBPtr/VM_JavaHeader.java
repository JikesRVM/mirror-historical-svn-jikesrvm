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
 * In this object model, there are NO available bits, and the TIB word is
 * simply a TIB ptr.
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
   * How many bits in the header are available for the GC and MISC headers? 
   * */
  public static final int NUM_AVAILABLE_BITS = 0;

  static {
    if (VM.VerifyAssertions) {
      VM.assert(VM_MiscHeader.REQUESTED_BITS + VM_AllocatorHeader.REQUESTED_BITS <= NUM_AVAILABLE_BITS);
      VM.assert(HASH_STATE_BITS == 0); // don't support copying collectors yet.
    }
  }

  /**
   * Get the TIB for an object.
   */
  public static Object[] getTIB(Object o) { 
    VM_Magic.pragmaInline();
    int tibWord = VM_Magic.getIntAtOffset(o,TIB_OFFSET);
    return VM_Magic.addressAsObjectArray(tibWord);
  }
  
  /**
   * Set the TIB for an object.
   */
  public static void setTIB(Object ref, Object[] tib) {
    VM_Magic.pragmaInline();
    ADDRESS tibPtr = VM_Magic.objectAsAddress(tib);
    VM_Magic.setIntAtOffset(ref, TIB_OFFSET, tibPtr);
  }

  /**
   * Set the TIB for an object.
   */
  public static void setTIB(BootImageInterface bootImage, int refOffset, int tibAddr, VM_Type type) {
    bootImage.setAddressWord(refOffset + TIB_OFFSET, tibAddr);
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
    return jdpService.readMemory(ptr + TIB_OFFSET);
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
    asm.emitL(dest, TIB_OFFSET, object);
  }
  //-#elif RVM_FOR_IA32
  public static void baselineEmitLoadTIB(VM_Assembler asm, byte dest, 
                                         byte object) {
    asm.emitMOV_Reg_RegDisp(dest, object, TIB_OFFSET);
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
    OPT_Operand address = GuardedUnary.getClearVal(s);
    OPT_RegisterOperand result = GuardedUnary.getClearResult(s);
    Load.mutate(s,INT_LOAD, result.copyRO(),
		address, new OPT_IntConstantOperand(TIB_OFFSET), 
		null, GuardedUnary.getClearGuard(s));
  }
  //-#endif
}
