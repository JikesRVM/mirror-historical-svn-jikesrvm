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
 * In this object model, the bottom N (N<=2) bits of the TIB word are the
 * available bits, and TIBs are aligned. So to acquire the TIB, we mask
 * the bottom N bits.
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
   * The number of low-order-bits of TIB that must be zero.
   */
  static final int LOG_TIB_ALIGNMENT = NUM_AVAILABLE_BITS;

  /**
   * The mask that defines the TIB value in the one-word header.
   */
  private static final int TIB_MASK = 0xffffffff << LOG_TIB_ALIGNMENT;

  /**
   * The mask that defines the available bits the one-word header.
   */
  private static final int BITS_MASK = ~TIB_MASK;

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
    int tibWord = VM_Magic.getIntAtOffset(o,TIB_OFFSET) & TIB_MASK;
    return VM_Magic.addressAsObjectArray(tibWord);
  }
  
  /**
   * Set the TIB for an object.
   */
  public static void setTIB(Object ref, Object[] tib) {
    VM_Magic.pragmaInline();
    int tibPtr = VM_Magic.objectAsAddress(tib);
    if (VM.VerifyAssertions) {
      VM.assert((tibPtr & BITS_MASK) == 0);
    }
    int tibWord = (VM_Magic.getIntAtOffset(ref, TIB_OFFSET) & BITS_MASK) | tibPtr;
    VM_Magic.setIntAtOffset(ref, TIB_OFFSET, tibWord);
  }

  /**
   * Set the TIB for an object.
   * Note: Beware; this function clears the additional bits.
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
    return jdpService.readMemory(ptr + TIB_OFFSET) & TIB_MASK;
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
    int ME = 31 - LOG_TIB_ALIGNMENT;
    asm.emitL(dest, TIB_OFFSET, object);
    // The following clears the low-order bits. See p.119 of PowerPC book
    asm.emitRLWINM(dest, dest, 0, 0, ME);
  }
  //-#elif RVM_FOR_IA32
  public static void baselineEmitLoadTIB(VM_Assembler asm, byte dest, 
                                         byte object) {
    asm.emitMOV_Reg_RegDisp(dest, object, TIB_OFFSET);
    asm.emitAND_Reg_Imm(dest,TIB_MASK);
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
    OPT_Operand address = GuardedUnary.getClearVal(s);
    OPT_RegisterOperand result = GuardedUnary.getClearResult(s);
    s.insertBefore(Load.create(INT_LOAD, result.copyRO(),
		address, new OPT_IntConstantOperand(TIB_OFFSET), 
		null, GuardedUnary.getClearGuard(s)));
    Binary.mutate(s, INT_AND, result.copyRO(), result.copyRO(), 
                  new OPT_IntConstantOperand(TIB_MASK));
  }

  //-#endif
}
