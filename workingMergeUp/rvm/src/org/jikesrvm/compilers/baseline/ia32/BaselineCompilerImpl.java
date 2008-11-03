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
package org.jikesrvm.compilers.baseline.ia32;

import org.jikesrvm.SizeConstants;
import org.jikesrvm.VM;
import org.jikesrvm.adaptive.AosEntrypoints;
import org.jikesrvm.adaptive.recompilation.InvocationCounts;
import org.jikesrvm.classloader.Atom;
import org.jikesrvm.classloader.DynamicTypeCheck;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.InterfaceInvocation;
import org.jikesrvm.classloader.InterfaceMethodSignature;
import org.jikesrvm.classloader.MemberReference;
import org.jikesrvm.classloader.MethodReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMArray;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.compilers.baseline.BaselineCompiledMethod;
import org.jikesrvm.compilers.baseline.BaselineCompiler;
import org.jikesrvm.compilers.baseline.EdgeCounts;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.common.assembler.ForwardReference;
import org.jikesrvm.compilers.common.assembler.ia32.Assembler;
import org.jikesrvm.ia32.BaselineConstants;
import org.jikesrvm.ia32.ThreadLocalState;
import org.jikesrvm.jni.ia32.JNICompiler;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.mm.mminterface.MemoryManagerConstants;
import org.jikesrvm.objectmodel.JavaHeaderConstants;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.runtime.ArchEntrypoints;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.runtime.MagicNames;
import org.jikesrvm.runtime.RuntimeEntrypoints;
import org.jikesrvm.runtime.Statics;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Offset;

/**
 * BaselineCompilerImpl is the baseline compiler implementation for the IA32 architecture.
 */
public abstract class BaselineCompilerImpl extends BaselineCompiler implements BaselineConstants, SizeConstants {

  static {
    // Force resolution of BaselineMagic before using in genMagic
    Object x = BaselineMagic.generateMagic(null, null, null, Offset.zero());
  }

  private final int parameterWords;
  private int firstLocalOffset;

  static final Offset NO_SLOT = Offset.zero();
  static final Offset ONE_SLOT = NO_SLOT.plus(WORDSIZE);
  static final Offset TWO_SLOTS = ONE_SLOT.plus(WORDSIZE);
  static final Offset THREE_SLOTS = TWO_SLOTS.plus(WORDSIZE);
  static final Offset FOUR_SLOTS = THREE_SLOTS.plus(WORDSIZE);
  static final Offset FIVE_SLOTS = FOUR_SLOTS.plus(WORDSIZE);
  private static final Offset MINUS_ONE_SLOT = NO_SLOT.minus(WORDSIZE);

  /**
   * Create a BaselineCompilerImpl object for the compilation of method.
   */
  protected BaselineCompilerImpl(BaselineCompiledMethod cm) {
    super(cm);
    stackHeights = new int[bcodes.length()];
    parameterWords = method.getParameterWords() + (method.isStatic() ? 0 : 1); // add 1 for this pointer
  }

  @Override
  protected void initializeCompiler() {
    //nothing to do for intel
  }

  public final byte getLastFixedStackRegister() {
    return -1; //doesn't dedicate registers to stack;
  }

  public final byte getLastFloatStackRegister() {
    return -1; //doesn't dedicate registers to stack;
  }

  @Uninterruptible
  public static short getGeneralLocalLocation(int index, short[] localloc, NormalMethod m) {
    return offsetToLocation(getStartLocalOffset(m) -
                            (index << LOG_BYTES_IN_ADDRESS)); //we currently do not use location arrays on intel
  }

  @Uninterruptible
  public static short getFloatLocalLocation(int index, short[] localloc, NormalMethod m) {
    return offsetToLocation(getStartLocalOffset(m) -
                            (index << LOG_BYTES_IN_ADDRESS)); //we currently do not use location arrays on intel
  }

  @Uninterruptible
  public static int locationToOffset(short location) {
    return -location;
  }

  @Uninterruptible
  public static short offsetToLocation(int offset) {
    return (short)-offset;
  }

  /**
   * The last true local
   */
  @Uninterruptible
  public static int getEmptyStackOffset(NormalMethod m) {
    return getFirstLocalOffset(m) - (m.getLocalWords() << LG_WORDSIZE) + WORDSIZE;
  }

  /**
   * This is misnamed.  It should be getFirstParameterOffset.
   * It will not work as a base to access true locals.
   * TODO!! make sure it is not being used incorrectly
   */
  @Uninterruptible
  private static int getFirstLocalOffset(NormalMethod method) {
    if (method.getDeclaringClass().hasBridgeFromNativeAnnotation()) {
      return STACKFRAME_BODY_OFFSET - (JNICompiler.SAVED_GPRS_FOR_JNI << LG_WORDSIZE);
    } else if (method.hasBaselineSaveLSRegistersAnnotation()) {
      return STACKFRAME_BODY_OFFSET - (SAVED_GPRS_FOR_SAVE_LS_REGISTERS << LG_WORDSIZE);
    } else {
      return STACKFRAME_BODY_OFFSET - (SAVED_GPRS << LG_WORDSIZE);
    }
  }

  @Uninterruptible
  private static int getStartLocalOffset(NormalMethod method) {
    return getFirstLocalOffset(method) + WORDSIZE;
  }

  /**
   * Adjust the value of ESP/RSP
   *
   * @param size amount to change ESP/RSP by
   * @param mayClobber can the value in S0 or memory be destroyed?
   * (ie can we use a destructive short push/pop opcode)
   */
  private void adjustStack(int size, boolean mayClobber) {
    final boolean debug=false;
    if (size != 0) {
      if (mayClobber) {
        // first try short opcodes
        switch(size >> LG_WORDSIZE) {
        case -2:
          if (debug) {
            asm.emitPUSH_Imm(0xFA1FACE);
            asm.emitPUSH_Imm(0xFA2FACE);
          } else {
            asm.emitPUSH_Reg(EAX);
            asm.emitPUSH_Reg(EAX);
          }
          return;
        case -1:
          if (debug) {
            asm.emitPUSH_Imm(0xFA3FACE);
          } else {
           asm.emitPUSH_Reg(EAX);
          }
          return;
        case 1:
          asm.emitPOP_Reg(S0);
          if (debug) {
            asm.emitMOV_Reg_Imm(S0, 0xFA4FACE);
          }
          return;
        case 2:
          asm.emitPOP_Reg(S0);
          asm.emitPOP_Reg(S0);
          if (debug) {
            asm.emitMOV_Reg_Imm(S0, 0xFA5FACE);
          }
          return;
        }
      }
      if (VM.BuildFor32Addr) {
        asm.emitADD_Reg_Imm(SP, size);
      } else {
        asm.emitADD_Reg_Imm_Quad(SP, size);
      }
    }
  }

  /*
   * implementation of abstract methods of BaselineCompiler
   */

  /*
   * Misc routines not directly tied to a particular bytecode
   */

  /**
   * Utility to call baselineEmitLoadTIB with int arguments not GPR
   */
  static void baselineEmitLoadTIB(org.jikesrvm.ArchitectureSpecific.Assembler asm, GPR dest, GPR object) {
    ObjectModel.baselineEmitLoadTIB(asm, dest.value(), object.value());
  }
  /**
   * Notify BaselineCompilerImpl that we are starting code gen for the bytecode biStart
   */
  @Override
  protected final void starting_bytecode() {}

  /**
   * Emit the prologue for the method
   */
  @Override
  protected final void emit_prologue() {
    genPrologue();
  }

  /**
   * Emit the code for a threadswitch tests (aka a yieldpoint).
   * @param whereFrom is this thread switch from a PROLOGUE, BACKEDGE, or EPILOGUE?
   */
  @Override
  protected final void emit_threadSwitchTest(int whereFrom) {
    genThreadSwitchTest(whereFrom);
  }

  /**
   * Emit the code to implement the spcified magic.
   * @param magicMethod desired magic
   */
  @Override
  protected final boolean emit_Magic(MethodReference magicMethod) {
    return genMagic(magicMethod);
  }

  /*
  * Loading constants
  */

  /**
   * Emit code to load the null constant.
   */
  @Override
  protected final void emit_aconst_null() {
    asm.emitPUSH_Imm(0);
  }

  /**
   * Emit code to load an int constant.
   * @param val the int constant to load
   */
  @Override
  protected final void emit_iconst(int val) {
    asm.emitPUSH_Imm(val);
  }

  /**
   * Emit code to load a long constant
   * @param val the lower 32 bits of long constant (upper32 are 0).
   */
  @Override
  protected final void emit_lconst(int val) {
    asm.emitPUSH_Imm(0);    // high part
    asm.emitPUSH_Imm(val);  //  low part
  }

  /**
   * Emit code to load 0.0f
   */
  @Override
  protected final void emit_fconst_0() {
    asm.emitPUSH_Imm(0);
  }

  /**
   * Emit code to load 1.0f
   */
  @Override
  protected final void emit_fconst_1() {
    asm.emitPUSH_Imm(0x3f800000);
  }

  /**
   * Emit code to load 2.0f
   */
  @Override
  protected final void emit_fconst_2() {
    asm.emitPUSH_Imm(0x40000000);
  }

  /**
   * Emit code to load 0.0d
   */
  @Override
  protected final void emit_dconst_0() {
    if (VM.BuildFor32Addr) {
      asm.emitPUSH_Imm(0x00000000);
      asm.emitPUSH_Imm(0x00000000);
    } else {
      adjustStack(-WORDSIZE, true);
      asm.emitPUSH_Imm(0x00000000);
    }
  }

  /**
   * Emit code to load 1.0d
   */
  @Override
  protected final void emit_dconst_1() {
    if (VM.BuildFor32Addr) {
      asm.emitPUSH_Imm(0x3ff00000);
      asm.emitPUSH_Imm(0x00000000);
    } else {
      adjustStack(-WORDSIZE, true);
      asm.emitPUSH_Abs(Magic.getTocPointer().plus(Entrypoints.oneDoubleField.getOffset()));
    }
  }

  /**
   * Emit code to load a 32 bit constant
   * @param offset JTOC offset of the constant
   * @param type the type of the constant
   */
  @Override
  protected final void emit_ldc(Offset offset, byte type) {
      if (VM.BuildFor32Addr || (type == RVMClass.CP_CLASS) || (type == RVMClass.CP_STRING)) {
      asm.emitPUSH_Abs(Magic.getTocPointer().plus(offset));
    } else {
      asm.emitMOV_Reg_Abs(T0, Magic.getTocPointer().plus(offset));
      asm.emitPUSH_Reg(T0);
    }
  }

  /**
   * Emit code to load a 64 bit constant
   * @param offset JTOC offset of the constant
   * @param type the type of the constant
   */
  @Override
  protected final void emit_ldc2(Offset offset, byte type) {
    if (VM.BuildFor32Addr) {
      if (SSE2_BASE) {
        adjustStack(-2*WORDSIZE, true);     // adjust stack
        asm.emitMOVQ_Reg_Abs(XMM0, Magic.getTocPointer().plus(offset)); // XMM0 is constant value
        asm.emitMOVQ_RegInd_Reg(SP, XMM0);  // place value on stack
      } else {
        asm.emitPUSH_Abs(Magic.getTocPointer().plus(offset).plus(WORDSIZE)); // high 32 bits
        asm.emitPUSH_Abs(Magic.getTocPointer().plus(offset));   // low 32 bits
      }
    } else {
      adjustStack(-WORDSIZE, true);
      asm.emitPUSH_Abs(Magic.getTocPointer().plus(offset));
    }
  }

  /*
   * loading local variables
   */

  /**
   * Emit code to load an int local variable
   * @param index the local index to load
   */
  @Override
  protected final void emit_iload(int index) {
    Offset offset = localOffset(index);
    if (offset.EQ(Offset.zero())) {
      asm.emitPUSH_RegInd(ESP);
    } else {
      asm.emitPUSH_RegDisp(ESP, offset);
    }
  }

  /**
   * Emit code to local a float local variable
   * @param index the local index to load
   */
  @Override
  protected final void emit_fload(int index) {
    // identical to iload
    emit_iload(index);
  }

  /**
   * Emit code to load a reference local variable
   * @param index the local index to load
   */
  @Override
  protected final void emit_aload(int index) {
    // identical to iload
    emit_iload(index);
  }

  /**
   * Emit code to load a long local variable
   * @param index the local index to load
   */
  @Override
  protected final void emit_lload(int index) {
    Offset offset = localOffset(index);
    if (VM.BuildFor32Addr) {
      if (SSE2_BASE) {
        asm.emitMOVQ_Reg_RegDisp(XMM0, SP, offset.minus(WORDSIZE)); // XMM0 is local value
        adjustStack(-2*WORDSIZE, true);     // adjust stack
        asm.emitMOVQ_RegInd_Reg(SP, XMM0);  // place value on stack
      } else {
        asm.emitPUSH_RegDisp(ESP, offset); // high part
        asm.emitPUSH_RegDisp(ESP, offset); // low part (ESP has moved by 4!!)
      }
    } else {
      adjustStack(-WORDSIZE, true);
      asm.emitPUSH_RegDisp(ESP, offset);
    }
  }

  /**
   * Emit code to load a double local variable
   * @param index the local index to load
   */
  @Override
  protected final void emit_dload(int index) {
    // identical to lload
    emit_lload(index);
  }

  /*
   * storing local variables
   */

  /**
   * Emit code to store an int to a local variable
   * @param index the local index to load
   */
  @Override
  protected final void emit_istore(int index) {
    Offset offset = localOffset(index).minus(WORDSIZE); // pop computes EA after ESP has moved by WORDSIZE!
    if (offset.EQ(Offset.zero())) {
      asm.emitPOP_RegInd(ESP);
    } else {
      asm.emitPOP_RegDisp(ESP, offset);
    }
  }

  /**
   * Emit code to store a float to a local variable
   * @param index the local index to load
   */
  @Override
  protected final void emit_fstore(int index) {
    // identical to istore
    emit_istore(index);
  }

  /**
   * Emit code to store a reference to a local variable
   * @param index the local index to load
   */
  @Override
  protected final void emit_astore(int index) {
    // identical to istore
    emit_istore(index);
  }

  /**
   * Emit code to store a long to a local variable
   * @param index the local index to load
   */
  @Override
  protected final void emit_lstore(int index) {
    if (VM.BuildFor32Addr) {
      if (SSE2_BASE) {
        Offset offset = localOffset(index).minus(WORDSIZE);
        asm.emitMOVQ_Reg_RegInd(XMM0, SP);  // XMM0 is stack value
        asm.emitMOVQ_RegDisp_Reg(SP, offset, XMM0);  // place value in local
        adjustStack(2*WORDSIZE, true);
      } else {
        // pop computes EA after ESP has moved by 4!
        Offset offset = localOffset(index + 1).minus(WORDSIZE);
        asm.emitPOP_RegDisp(ESP, offset); // high part
        asm.emitPOP_RegDisp(ESP, offset); // low part (ESP has moved by 4!!)
      }
    } else {
      Offset offset = localOffset(index + 1).minus(WORDSIZE);
      asm.emitPOP_RegDisp(ESP, offset);
      adjustStack(WORDSIZE, true); // throw away top word
    }
  }

  /**
   * Emit code to store an double  to a local variable
   * @param index the local index to load
   */
  @Override
  protected final void emit_dstore(int index) {
    // identical to lstore
    emit_lstore(index);
  }

  /*
   * array loads
   */

  /**
   * Emit code to load from an int array
   */
  @Override
  protected final void emit_iaload() {
    asm.emitPOP_Reg(T0); // T0 is array index
    asm.emitPOP_Reg(S0); // S0 is array ref
    genBoundsCheck(asm, T0, S0); // T0 is index, S0 is address of array
    // push [S0+T0<<2]
    asm.emitPUSH_RegIdx(S0, T0, Assembler.WORD, NO_SLOT);
  }

  /**
   * Emit code to load from a float array
   */
  @Override
  protected final void emit_faload() {
    // identical to iaload
    emit_iaload();
  }

  /**
   * Emit code to load from a reference array
   */
  @Override
  protected final void emit_aaload() {
    asm.emitPOP_Reg(T0); // T0 is array index
    asm.emitPOP_Reg(T1); // T1 is array ref
    genBoundsCheck(asm, T0, T1); // T0 is index, T1 is address of array
    if (MemoryManagerConstants.NEEDS_READ_BARRIER) {
      // rewind 2 args on stack
      asm.emitPUSH_Reg(T1); // T1 is array ref
      asm.emitPUSH_Reg(T0); // T0 is array index
      Barriers.compileArrayLoadBarrier(asm, true);
    } else {
      asm.emitPUSH_RegIdx(T1, T0, (short)LG_WORDSIZE, NO_SLOT); // push [S0+T0*WORDSIZE]
    }
  }

  /**
   * Emit code to load from a char array
   */
  @Override
  protected final void emit_caload() {
    asm.emitPOP_Reg(T0); // T0 is array index
    asm.emitPOP_Reg(S0); // S0 is array ref
    genBoundsCheck(asm, T0, S0); // T0 is index, S0 is address of array
    // T1 = (int)[S0+T0<<1]
    if (VM.BuildFor32Addr) {
      asm.emitMOVZX_Reg_RegIdx_Word(T1, S0, T0, Assembler.SHORT, NO_SLOT);
    } else {
      asm.emitMOVZXQ_Reg_RegIdx_Word(T1, S0, T0, Assembler.SHORT, NO_SLOT);
    }
    asm.emitPUSH_Reg(T1);        // push short onto stack
  }

  /**
   * Emit code to load from a short array
   */
  @Override
  protected final void emit_saload() {
    asm.emitPOP_Reg(T0); // T0 is array index
    asm.emitPOP_Reg(S0); // S0 is array ref
    genBoundsCheck(asm, T0, S0); // T0 is index, S0 is address of array
    // T1 = (int)[S0+T0<<1]
    if (VM.BuildFor32Addr) {
      asm.emitMOVSX_Reg_RegIdx_Word(T1, S0, T0, Assembler.SHORT, NO_SLOT);
    } else {
      asm.emitMOVSXQ_Reg_RegIdx_Word(T1, S0, T0, Assembler.SHORT, NO_SLOT);
    }
    asm.emitPUSH_Reg(T1);        // push short onto stack
  }

  /**
   * Emit code to load from a byte/boolean array
   */
  @Override
  protected final void emit_baload() {
    asm.emitPOP_Reg(T0); // T0 is array index
    asm.emitPOP_Reg(S0); // S0 is array ref
    genBoundsCheck(asm, T0, S0); // T0 is index, S0 is address of array
    // T1 = (int)[S0+T0<<1]
    if (VM.BuildFor32Addr) {
      asm.emitMOVSX_Reg_RegIdx_Byte(T1, S0, T0, Assembler.BYTE, NO_SLOT);
    } else {
      asm.emitMOVSXQ_Reg_RegIdx_Byte(T1, S0, T0, Assembler.BYTE, NO_SLOT);
    }
    asm.emitPUSH_Reg(T1);        // push byte onto stack
  }

  /**
   * Emit code to load from a long array
   */
  @Override
  protected final void emit_laload() {
    asm.emitPOP_Reg(T0); // T0 is array index
    asm.emitPOP_Reg(T1); // T1 is array ref
    if (VM.BuildFor32Addr && SSE2_BASE) {
      adjustStack(WORDSIZE*-2, true); // create space for result
    }
    genBoundsCheck(asm, T0, T1); // T0 is index, T1 is address of array
    if (VM.BuildFor32Addr) {
      if (SSE2_BASE) {
        asm.emitMOVQ_Reg_RegIdx(XMM0, T1, T0, Assembler.LONG, NO_SLOT);
        asm.emitMOVQ_RegInd_Reg(SP, XMM0);
      } else {
        asm.emitPUSH_RegIdx(T1, T0, Assembler.LONG, ONE_SLOT); // load high part of desired long array element
        asm.emitPUSH_RegIdx(T1, T0, Assembler.LONG, NO_SLOT);  // load low part of desired long array element
      }
    } else {
      adjustStack(-WORDSIZE, true);
      asm.emitPUSH_RegIdx(T1, T0, Assembler.LONG, NO_SLOT);  // load desired long array element
    }
  }

  /**
   * Emit code to load from a double array
   */
  @Override
  protected final void emit_daload() {
    // identical to laload
    emit_laload();
  }

  /*
   * array stores
   */

  /**
   * Emit code to store to an int array
   */
  @Override
  protected final void emit_iastore() {
    Barriers.compileModifyCheck(asm, 8);
    asm.emitPOP_Reg(T1); // T1 is the value
    asm.emitPOP_Reg(T0); // T0 is array index
    asm.emitPOP_Reg(S0); // S0 is array ref
    genBoundsCheck(asm, T0, S0);                // T0 is index, S0 is address of array
    asm.emitMOV_RegIdx_Reg(S0, T0, Assembler.WORD, NO_SLOT, T1); // [S0 + T0<<2] <- T1
  }

  /**
   * Emit code to store to a float array
   */
  @Override
  protected final void emit_fastore() {
    // identical to iastore
    emit_iastore();
  }


  /**
   * Emit code to store to a reference array
   */
  @Override
  protected final void emit_aastore() {
    Barriers.compileModifyCheck(asm, 8);
    if (doesCheckStore) {
      asm.emitPUSH_RegDisp(SP, TWO_SLOTS); // duplicate array ref
      asm.emitPUSH_RegDisp(SP, ONE_SLOT);  // duplicate object value
      genParameterRegisterLoad(2);         // pass 2 parameter
      // call checkstore(array ref, value)
      asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.checkstoreMethod.getOffset()));
    }
    asm.emitMOV_Reg_RegDisp(T0, SP, ONE_SLOT);  // T0 is array index
    asm.emitMOV_Reg_RegDisp(S0, SP, TWO_SLOTS); // S0 is the array ref
    genBoundsCheck(asm, T0, S0);        // T0 is index, S0 is address of array
    if (MM_Constants.NEEDS_WRITE_BARRIER) {
      Barriers.compileArrayStoreBarrier(asm);
      asm.emitADD_Reg_Imm(SP, WORDSIZE * 3); // complete popping the 3 args
    } else {
      asm.emitMOV_Reg_RegDisp(T1, SP, NO_SLOT); // T1 is the object value
      asm.emitADD_Reg_Imm(SP, WORDSIZE * 3); // complete popping the 3 args
      asm.emitMOV_RegIdx_Reg(S0, T0, Assembler.WORD, NO_SLOT, T1); // [S0 + T0<<2] <- T1
    }
  }

  /**
   * Emit code to store to a char array
   */
  @Override
  protected final void emit_castore() {
    Barriers.compileModifyCheck(asm, 8);
    asm.emitPOP_Reg(T1); // T1 is the value
    asm.emitPOP_Reg(T0); // T0 is array index
    asm.emitPOP_Reg(S0); // S0 is array ref
    genBoundsCheck(asm, T0, S0);        // T0 is index, S0 is address of array
    // store halfword element into array i.e. [S0 +T0] <- T1 (halfword)
    asm.emitMOV_RegIdx_Reg_Word(S0, T0, Assembler.SHORT, NO_SLOT, T1);
  }

  /**
   * Emit code to store to a short array
   */
  @Override
  protected final void emit_sastore() {
    // identical to castore
    emit_castore();
  }

  /**
   * Emit code to store to a byte/boolean array
   */
  @Override
  protected final void emit_bastore() {
    Barriers.compileModifyCheck(asm, 8);
    asm.emitPOP_Reg(T1); // T1 is the value
    asm.emitPOP_Reg(T0); // T0 is array index
    asm.emitPOP_Reg(S0); // S0 is array ref
    genBoundsCheck(asm, T0, S0);         // T0 is index, S0 is address of array
    asm.emitMOV_RegIdx_Reg_Byte(S0, T0, Assembler.BYTE, NO_SLOT, T1); // [S0 + T0<<2] <- T1
  }

  /**
   * Emit code to store to a long array
   */
  @Override
  protected final void emit_lastore() {
    Barriers.compileModifyCheck(asm, 3*WORDSIZE);
    if (VM.BuildFor32Addr) {
      if (SSE2_BASE) {
        asm.emitMOVQ_Reg_RegInd(XMM0,SP); // XMM0 is the value
        adjustStack(WORDSIZE*2, true);    // remove value from the stack
        asm.emitPOP_Reg(T0); // T0 is array index
        asm.emitPOP_Reg(S0); // S0 is array ref
      } else {
        asm.emitMOV_Reg_RegDisp(T0, SP, TWO_SLOTS);    // T0 is the array index
        asm.emitMOV_Reg_RegDisp(S0, SP, THREE_SLOTS);  // S0 is the array ref
        asm.emitMOV_Reg_RegInd(T1, SP);              // low part of long value
      }
    } else {
      asm.emitPOP_Reg(T1);         // T1 is the value
      adjustStack(WORDSIZE, true); // throw away slot
      asm.emitPOP_Reg(T0);         // T0 is array index
      asm.emitPOP_Reg(S0);         // S0 is array ref
    }
    genBoundsCheck(asm, T0, S0);                   // T0 is index, S0 is address of array
    if (VM.BuildFor32Addr) {
      if (SSE2_BASE) {
        asm.emitMOVQ_RegIdx_Reg(S0, T0, Assembler.LONG, NO_SLOT, XMM0); // [S0+T0<<<3] <- XMM0
      } else {
        // [S0 + T0<<3 + 0] <- T1 store low part into array
        asm.emitMOV_RegIdx_Reg(S0, T0, Assembler.LONG, NO_SLOT, T1);
        asm.emitMOV_Reg_RegDisp(T1, SP, ONE_SLOT); // high part of long value
        // [S0 + T0<<3 + 4] <- T1 store high part into array
        adjustStack(WORDSIZE*4, false); // remove index and ref from the stack
        asm.emitMOV_RegIdx_Reg(S0, T0, Assembler.LONG, ONE_SLOT, T1);
      }
    } else {
      asm.emitMOV_RegIdx_Reg_Quad(S0, T0, Assembler.LONG, NO_SLOT, T1); // [S0+T0<<<3] <- T1
    }
  }

  /**
   * Emit code to store to a double array
   */
  @Override
  protected final void emit_dastore() {
    // identical to lastore
    emit_lastore();
  }

  /*
  * expression stack manipulation
  */

  /**
   * Emit code to implement the pop bytecode
   */
  @Override
  protected final void emit_pop() {
    adjustStack(WORDSIZE, true);
  }

  /**
   * Emit code to implement the pop2 bytecode
   */
  @Override
  protected final void emit_pop2() {
    // This could be encoded as the single 3 byte instruction
    // asm.emitADD_Reg_Imm(SP, 8);
    // or as the following 2 1 byte instructions. There doesn't appear to be any
    // performance difference.
    adjustStack(WORDSIZE*2, true);
  }

  /**
   * Emit code to implement the dup bytecode
   */
  @Override
  protected final void emit_dup() {
    // This could be encoded as the 2 instructions totalling 4 bytes:
    // asm.emitMOV_Reg_RegInd(T0, SP);
    // asm.emitPUSH_Reg(T0);
    // However, there doesn't seem to be any performance difference to:
    asm.emitPUSH_RegInd(SP);
  }

  /**
   * Emit code to implement the dup_x1 bytecode
   */
  @Override
  protected final void emit_dup_x1() {
    asm.emitPOP_Reg(T0);
    asm.emitPOP_Reg(S0);
    asm.emitPUSH_Reg(T0);
    asm.emitPUSH_Reg(S0);
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to implement the dup_x2 bytecode
   */
  @Override
  protected final void emit_dup_x2() {
    asm.emitPOP_Reg(T0);
    asm.emitPOP_Reg(S0);
    asm.emitPOP_Reg(T1);
    asm.emitPUSH_Reg(T0);
    asm.emitPUSH_Reg(T1);
    asm.emitPUSH_Reg(S0);
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to implement the dup2 bytecode
   */
  @Override
  protected final void emit_dup2() {
    asm.emitPOP_Reg(T0);
    asm.emitPOP_Reg(S0);
    asm.emitPUSH_Reg(S0);
    asm.emitPUSH_Reg(T0);
    asm.emitPUSH_Reg(S0);
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to implement the dup2_x1 bytecode
   */
  @Override
  protected final void emit_dup2_x1() {
    asm.emitPOP_Reg(T0);
    asm.emitPOP_Reg(S0);
    asm.emitPOP_Reg(T1);
    asm.emitPUSH_Reg(S0);
    asm.emitPUSH_Reg(T0);
    asm.emitPUSH_Reg(T1);
    asm.emitPUSH_Reg(S0);
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to implement the dup2_x2 bytecode
   */
  @Override
  protected final void emit_dup2_x2() {
    asm.emitPOP_Reg(T0);
    asm.emitPOP_Reg(S0);
    asm.emitPOP_Reg(T1);
    asm.emitPOP_Reg(S1);
    asm.emitPUSH_Reg(S0);
    asm.emitPUSH_Reg(T0);
    asm.emitPUSH_Reg(S1);
    asm.emitPUSH_Reg(T1);
    asm.emitPUSH_Reg(S0);
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to implement the swap bytecode
   */
  @Override
  protected final void emit_swap() {
    // This could be encoded as the 4 instructions totalling 14 bytes:
    // asm.emitMOV_Reg_RegInd(T0, SP);
    // asm.emitMOV_Reg_RegDisp(S0, SP, ONE_SLOT);
    // asm.emitMOV_RegDisp_Reg(SP, ONE_SLOT, T0);
    // asm.emitMOV_RegInd_Reg(SP, S0);
    // But the following is 4bytes:
    asm.emitPOP_Reg(T0);
    asm.emitPOP_Reg(S0);
    asm.emitPUSH_Reg(T0);
    asm.emitPUSH_Reg(S0);
  }

  /*
  * int ALU
  */

  /**
   * Emit code to implement the iadd bytecode
   */
  @Override
  protected final void emit_iadd() {
    asm.emitPOP_Reg(T0);
    asm.emitADD_RegInd_Reg(SP, T0);
  }

  /**
   * Emit code to implement the isub bytecode
   */
  @Override
  protected final void emit_isub() {
    asm.emitPOP_Reg(T0);
    asm.emitSUB_RegInd_Reg(SP, T0);
  }

  /**
   * Emit code to implement the imul bytecode
   */
  @Override
  protected final void emit_imul() {
    asm.emitPOP_Reg(T0);
    asm.emitPOP_Reg(T1);
    asm.emitIMUL2_Reg_Reg(T0, T1);
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to implement the idiv bytecode
   */
  @Override
  protected final void emit_idiv() {
    asm.emitPOP_Reg(ECX);  // ECX is divisor; NOTE: can't use symbolic registers because of intel hardware requirements
    asm.emitPOP_Reg(EAX);  // EAX is dividend
    asm.emitCDQ();         // sign extend EAX into EDX
    asm.emitIDIV_Reg_Reg(EAX, ECX);
    asm.emitPUSH_Reg(EAX); // push result
  }

  /**
   * Emit code to implement the irem bytecode
   */
  @Override
  protected final void emit_irem() {
    asm.emitPOP_Reg(ECX);  // ECX is divisor; NOTE: can't use symbolic registers because of intel hardware requirements
    asm.emitPOP_Reg(EAX);  // EAX is dividend
    asm.emitCDQ();         // sign extend EAX into EDX
    asm.emitIDIV_Reg_Reg(EAX, ECX);
    asm.emitPUSH_Reg(EDX); // push remainder
  }

  /**
   * Emit code to implement the ineg bytecode
   */
  @Override
  protected final void emit_ineg() {
    asm.emitNEG_RegInd(SP); // [SP] <- -[SP]
  }

  /**
   * Emit code to implement the ishl bytecode
   */
  @Override
  protected final void emit_ishl() {
    asm.emitPOP_Reg(ECX);
    asm.emitSHL_RegInd_Reg(SP, ECX);
  }

  /**
   * Emit code to implement the ishr bytecode
   */
  @Override
  protected final void emit_ishr() {
    asm.emitPOP_Reg(ECX);
    asm.emitSAR_RegInd_Reg(SP, ECX);
  }

  /**
   * Emit code to implement the iushr bytecode
   */
  @Override
  protected final void emit_iushr() {
    asm.emitPOP_Reg(ECX);
    asm.emitSHR_RegInd_Reg(SP, ECX);
  }

  /**
   * Emit code to implement the iand bytecode
   */
  @Override
  protected final void emit_iand() {
    asm.emitPOP_Reg(T0);
    asm.emitAND_RegInd_Reg(SP, T0);
  }

  /**
   * Emit code to implement the ior bytecode
   */
  @Override
  protected final void emit_ior() {
    asm.emitPOP_Reg(T0);
    asm.emitOR_RegInd_Reg(SP, T0);
  }

  /**
   * Emit code to implement the ixor bytecode
   */
  @Override
  protected final void emit_ixor() {
    asm.emitPOP_Reg(T0);
    asm.emitXOR_RegInd_Reg(SP, T0);
  }

  /**
   * Emit code to implement the iinc bytecode
   * @param index index of local
   * @param val value to increment it by
   */
  @Override
  protected final void emit_iinc(int index, int val) {
    Offset offset = localOffset(index);
    asm.emitADD_RegDisp_Imm(ESP, offset, val);
  }

  /*
  * long ALU
  */

  /**
   * Emit code to implement the ladd bytecode
   */
  @Override
  protected final void emit_ladd() {
    if (VM.BuildFor32Addr) {
      asm.emitPOP_Reg(T0);                       // the low half of one long
      asm.emitPOP_Reg(S0);                       // the high half
      asm.emitADD_RegInd_Reg(SP, T0);            // add low halves
      asm.emitADC_RegDisp_Reg(SP, ONE_SLOT, S0); // add high halves with carry
    } else {
      asm.emitPOP_Reg(T0);  // the long value
      asm.emitPOP_Reg(S0);  // throw away slot
      asm.emitADD_RegInd_Reg_Quad(SP, T0); // add values
    }
  }

  /**
   * Emit code to implement the lsub bytecode
   */
  @Override
  protected final void emit_lsub() {
    if (VM.BuildFor32Addr) {
      asm.emitPOP_Reg(T0);                       // the low half of one long
      asm.emitPOP_Reg(S0);                       // the high half
      asm.emitSUB_RegInd_Reg(SP, T0);            // subtract low halves
      asm.emitSBB_RegDisp_Reg(SP, ONE_SLOT, S0); // subtract high halves with borrow
    } else {
      asm.emitPOP_Reg(T0);         // the long value
      adjustStack(WORDSIZE, true); // throw away slot
      asm.emitSUB_RegInd_Reg_Quad(SP, T0); // sub values
    }
  }

  /**
   * Emit code to implement the lmul bytecode
   */
  @Override
  protected final void emit_lmul() {
    if (VM.BuildFor64Addr) {
      asm.emitPOP_Reg(T0); // the long value
      asm.emitPOP_Reg(S0); // throw away slot
      asm.emitIMUL2_Reg_RegInd_Quad(T0, SP);
      asm.emitMOV_RegInd_Reg_Quad(SP, T0);
    } else {
      // stack: value1.high = mulitplier
      //        value1.low
      //        value2.high = multiplicand
      //        value2.low  <-- ESP
      if (VM.VerifyAssertions) VM._assert(S0 != EAX);
      if (VM.VerifyAssertions) VM._assert(S0 != EDX);
      // EAX = multiplicand low; SP changed!
      asm.emitPOP_Reg(EAX);
      // EDX = multiplicand high
      asm.emitPOP_Reg(EDX);
      // stack: value1.high = mulitplier
      //        value1.low  <-- ESP
      //        value2.high = multiplicand
      //        value2.low
      // S0 = multiplier high
      asm.emitMOV_Reg_RegDisp(S0, SP, ONE_SLOT);
      // is one operand > 2^32 ?
      asm.emitOR_Reg_Reg(EDX, S0);
      // EDX = multiplier low
      asm.emitMOV_Reg_RegInd(EDX, SP);
      // Jump if we need a 64bit multiply
      ForwardReference fr1 = asm.forwardJcc(Assembler.NE);
      // EDX:EAX = 32bit multiply of multiplier and multiplicand low
      asm.emitMUL_Reg_Reg(EAX, EDX);
      // Jump over 64bit multiply
      ForwardReference fr2 = asm.forwardJMP();
      // Start of 64bit multiply
      fr1.resolve(asm);
      // EDX = multiplicand high * multiplier low
      asm.emitIMUL2_Reg_RegDisp(EDX, SP, MINUS_ONE_SLOT);
      // S0 = multiplier high * multiplicand low
      asm.emitIMUL2_Reg_Reg(S0, EAX);
      // S0 = S0 + EDX
      asm.emitADD_Reg_Reg(S0, EDX);
      // EDX:EAX = 32bit multiply of multiplier and multiplicand low
      asm.emitMUL_Reg_RegInd(EAX, SP);
      // EDX = EDX + S0
      asm.emitADD_Reg_Reg(EDX, S0);
      // Finish up
      fr2.resolve(asm);
      // store EDX:EAX to stack
      asm.emitMOV_RegDisp_Reg(SP, ONE_SLOT, EDX);
      asm.emitMOV_RegInd_Reg(SP, EAX);
    }
  }

  /**
   * Emit code to implement the ldiv bytecode
   */
  @Override
  protected final void emit_ldiv() {
    if (VM.BuildFor64Addr) {
      asm.emitPOP_Reg(ECX);  // ECX is divisor; NOTE: can't use symbolic registers because of intel hardware requirements
      asm.emitPOP_Reg(EAX);  // throw away slot
      asm.emitPOP_Reg(EAX);  // EAX is dividend
      asm.emitCDO();         // sign extend EAX into EDX
      asm.emitIDIV_Reg_Reg_Quad(EAX, ECX);
      asm.emitPUSH_Reg(EAX); // push result
    } else {
      // (1) zero check
      asm.emitMOV_Reg_RegDisp(T0, SP, NO_SLOT);
      asm.emitOR_Reg_RegDisp(T0, SP, ONE_SLOT);
      asm.emitBranchLikelyNextInstruction();
      ForwardReference fr1 = asm.forwardJcc(Assembler.NE);
      asm.emitINT_Imm(RuntimeEntrypoints.TRAP_DIVIDE_BY_ZERO + RVM_TRAP_BASE);    // trap if divisor is 0
      fr1.resolve(asm);
      // (2) save RVM nonvolatiles
      int numNonVols = NONVOLATILE_GPRS.length;
      Offset off = Offset.fromIntSignExtend(numNonVols * WORDSIZE);
      for (int i = 0; i < numNonVols; i++) {
        asm.emitPUSH_Reg(NONVOLATILE_GPRS[i]);
      }
      // (3) Push args to C function (reversed)
      asm.emitPUSH_RegDisp(SP, off.plus(4));
      asm.emitPUSH_RegDisp(SP, off.plus(4));
      asm.emitPUSH_RegDisp(SP, off.plus(20));
      asm.emitPUSH_RegDisp(SP, off.plus(20));
      // (4) invoke C function through bootrecord
      asm.emitMOV_Reg_Abs(S0, Magic.getTocPointer().plus(Entrypoints.the_boot_recordField.getOffset()));
      asm.emitCALL_RegDisp(S0, Entrypoints.sysLongDivideIPField.getOffset());
      // (5) pop space for arguments
      adjustStack(4 * WORDSIZE, true);
      // (6) restore RVM nonvolatiles
      for (int i = numNonVols - 1; i >= 0; i--) {
        asm.emitPOP_Reg(NONVOLATILE_GPRS[i]);
      }
      // (7) pop expression stack
      adjustStack(WORDSIZE*4, true);
      // (8) push results
      asm.emitPUSH_Reg(T1);
      asm.emitPUSH_Reg(T0);
    }
  }

  /**
   * Emit code to implement the lrem bytecode
   */
  @Override
  protected final void emit_lrem() {
    if (VM.BuildFor64Addr) {
      asm.emitPOP_Reg(ECX);  // ECX is divisor; NOTE: can't use symbolic registers because of intel hardware requirements
      asm.emitPOP_Reg(EAX);  // throw away slot
      asm.emitPOP_Reg(EAX);  // EAX is dividend
      asm.emitCDO();         // sign extend EAX into EDX
      asm.emitIDIV_Reg_Reg_Quad(EAX, ECX);
      asm.emitPUSH_Reg(EAX); // push result
    } else {
      // (1) zero check
      asm.emitMOV_Reg_RegDisp(T0, SP, NO_SLOT);
      asm.emitOR_Reg_RegDisp(T0, SP, ONE_SLOT);
      asm.emitBranchLikelyNextInstruction();
      ForwardReference fr1 = asm.forwardJcc(Assembler.NE);
      asm.emitINT_Imm(RuntimeEntrypoints.TRAP_DIVIDE_BY_ZERO + RVM_TRAP_BASE);    // trap if divisor is 0
      fr1.resolve(asm);
      // (2) save RVM nonvolatiles
      int numNonVols = NONVOLATILE_GPRS.length;
      Offset off = Offset.fromIntSignExtend(numNonVols * WORDSIZE);
      for (int i = 0; i < numNonVols; i++) {
        asm.emitPUSH_Reg(NONVOLATILE_GPRS[i]);
      }
      // (3) Push args to C function (reversed)
      asm.emitPUSH_RegDisp(SP, off.plus(4));
      asm.emitPUSH_RegDisp(SP, off.plus(4));
      asm.emitPUSH_RegDisp(SP, off.plus(20));
      asm.emitPUSH_RegDisp(SP, off.plus(20));
      // (4) invoke C function through bootrecord
      asm.emitMOV_Reg_Abs(S0, Magic.getTocPointer().plus(Entrypoints.the_boot_recordField.getOffset()));
      asm.emitCALL_RegDisp(S0, Entrypoints.sysLongRemainderIPField.getOffset());
      // (5) pop space for arguments
      adjustStack(4 * WORDSIZE, true);
      // (6) restore RVM nonvolatiles
      for (int i = numNonVols - 1; i >= 0; i--) {
        asm.emitPOP_Reg(NONVOLATILE_GPRS[i]);
      }
      // (7) pop expression stack
      adjustStack(WORDSIZE*4, true);
      // (8) push results
      asm.emitPUSH_Reg(T1);
      asm.emitPUSH_Reg(T0);
    }
  }

  /**
   * Emit code to implement the lneg bytecode
   */
  @Override
  protected final void emit_lneg() {
    if (VM.BuildFor32Addr){
      asm.emitNOT_RegDisp(SP, ONE_SLOT);    // [SP+4] <- ~[SP+4] or high <- ~high
      asm.emitNEG_RegInd(SP);    // [SP] <- -[SP] or low <- -low
      asm.emitSBB_RegDisp_Imm(SP, ONE_SLOT, -1); // [SP+4] += 1+borrow or high += 1+borrow
    } else {
      asm.emitNEG_RegInd_Quad(SP);
    }
  }

  /**
   * Emit code to implement the lshsl bytecode
   */
  @Override
  protected final void emit_lshl() {
    if (VM.BuildFor32Addr) {
      if (SSE2_BASE) {
        asm.emitPOP_Reg(T0);                  // shift amount (6 bits)
        asm.emitMOVQ_Reg_RegInd(XMM1, SP);    // XMM1 <- [SP]
        asm.emitAND_Reg_Imm(T0, 0x3F);        // mask to 6bits
        asm.emitMOVD_Reg_Reg(XMM0, T0);      // XMM0 <- T0
        asm.emitPSLLQ_Reg_Reg(XMM1, XMM0);    // XMM1 <<= XMM0
        asm.emitMOVQ_RegInd_Reg(SP, XMM1);    // [SP] <- XMM1
      } else {
        if (VM.VerifyAssertions) VM._assert(ECX != T0); // ECX is constrained to be the shift count
        if (VM.VerifyAssertions) VM._assert(ECX != T1);
        asm.emitPOP_Reg(ECX);                  // shift amount (6 bits)
        asm.emitPOP_Reg(T0);                   // pop low half
        asm.emitPOP_Reg(T1);                   // pop high half
        asm.emitTEST_Reg_Imm(ECX, 32);
        ForwardReference fr1 = asm.forwardJcc(Assembler.NE);
        asm.emitSHLD_Reg_Reg_Reg(T1, T0, ECX);  // shift high half
        asm.emitSHL_Reg_Reg(T0, ECX);           // shift low half
        ForwardReference fr2 = asm.forwardJMP();
        fr1.resolve(asm);
        asm.emitMOV_Reg_Reg(T1, T0);  // shift high half
        asm.emitSHL_Reg_Reg(T1, ECX);
        asm.emitXOR_Reg_Reg(T0, T0);  // low half == 0
        fr2.resolve(asm);
        asm.emitPUSH_Reg(T1);                   // push high half
        asm.emitPUSH_Reg(T0);                   // push low half
      }
    } else {
      asm.emitPOP_Reg(ECX);
      asm.emitSHL_RegInd_Reg_Quad(SP, ECX);
    }
  }

  /**
   * Emit code to implement the lshr bytecode
   */
  @Override
  protected final void emit_lshr() {
    if (VM.BuildFor32Addr) {
      if (VM.VerifyAssertions) VM._assert(ECX != T0); // ECX is constrained to be the shift count
      if (VM.VerifyAssertions) VM._assert(ECX != T1);
      asm.emitPOP_Reg(ECX);                  // shift amount (6 bits)
      asm.emitPOP_Reg(T0);                   // pop low half
      asm.emitPOP_Reg(T1);                   // pop high half
      asm.emitTEST_Reg_Imm(ECX, 32);
      ForwardReference fr1 = asm.forwardJcc(Assembler.NE);
      asm.emitSHRD_Reg_Reg_Reg(T0, T1, ECX);  // shift high half
      asm.emitSAR_Reg_Reg(T1, ECX);           // shift low half
      ForwardReference fr2 = asm.forwardJMP();
      fr1.resolve(asm);
      asm.emitMOV_Reg_Reg(T0, T1);  // low half = high half
      asm.emitSAR_Reg_Imm(T1, 31);  // high half = high half >> 31
      asm.emitSAR_Reg_Reg(T0, ECX); // low half = high half >> ecx
      fr2.resolve(asm);
      asm.emitPUSH_Reg(T1);                   // push high half
      asm.emitPUSH_Reg(T0);                   // push low half
    } else {
      asm.emitPOP_Reg(ECX);
      asm.emitSAR_RegInd_Reg_Quad(SP, ECX);
    }
  }

  /**
   * Emit code to implement the lushr bytecode
   */
  @Override
  protected final void emit_lushr() {
    if (VM.BuildFor32Addr) {
      if (SSE2_BASE) {
        asm.emitPOP_Reg(T0);                  // shift amount (6 bits)
        asm.emitMOVQ_Reg_RegInd(XMM1, SP);    // XMM1 <- [SP]
        asm.emitAND_Reg_Imm(T0, 0x3F);        // mask to 6bits
        asm.emitMOVD_Reg_Reg(XMM0, T0);      // XMM0 <- T0
        asm.emitPSRLQ_Reg_Reg(XMM1, XMM0);    // XMM1 >>>= XMM0
        asm.emitMOVQ_RegInd_Reg(SP, XMM1);    // [SP] <- XMM1
      } else {
        if (VM.VerifyAssertions) VM._assert(ECX != T0); // ECX is constrained to be the shift count
        if (VM.VerifyAssertions) VM._assert(ECX != T1);
        asm.emitPOP_Reg(ECX);                  // shift amount (6 bits)
        asm.emitPOP_Reg(T0);                   // pop low half
        asm.emitPOP_Reg(T1);                   // pop high half
        asm.emitTEST_Reg_Imm(ECX, 32);
        ForwardReference fr1 = asm.forwardJcc(Assembler.NE);
        asm.emitSHRD_Reg_Reg_Reg(T0, T1, ECX);  // shift high half
        asm.emitSHR_Reg_Reg(T1, ECX);           // shift low half
        ForwardReference fr2 = asm.forwardJMP();
        fr1.resolve(asm);
        asm.emitMOV_Reg_Reg(T0, T1);  // low half = high half
        asm.emitXOR_Reg_Reg(T1, T1);  // high half = 0
        asm.emitSHR_Reg_Reg(T0, ECX); // low half = high half >>> ecx
        fr2.resolve(asm);
        asm.emitPUSH_Reg(T1);                   // push high half
        asm.emitPUSH_Reg(T0);                   // push low half
      }
    } else {
      asm.emitPOP_Reg(ECX);
      asm.emitSHR_RegInd_Reg_Quad(SP, ECX);
    }
  }

  /**
   * Emit code to implement the land bytecode
   */
  @Override
  protected final void emit_land() {
    if (VM.BuildFor32Addr) {
      asm.emitPOP_Reg(T0);        // low
      asm.emitPOP_Reg(S0);        // high
      asm.emitAND_RegInd_Reg(SP, T0);
      asm.emitAND_RegDisp_Reg(SP, ONE_SLOT, S0);
    } else {
      asm.emitPOP_Reg(T0); // long value
      asm.emitPOP_Reg(S0); // throw away slot
      asm.emitAND_RegInd_Reg_Quad(SP, T0);
    }
  }

  /**
   * Emit code to implement the lor bytecode
   */
  @Override
  protected final void emit_lor() {
    if (VM.BuildFor32Addr) {
      asm.emitPOP_Reg(T0);        // low
      asm.emitPOP_Reg(S0);        // high
      asm.emitOR_RegInd_Reg(SP, T0);
      asm.emitOR_RegDisp_Reg(SP, ONE_SLOT, S0);
    } else {
      asm.emitPOP_Reg(T0); // long value
      asm.emitPOP_Reg(S0); // throw away slot
      asm.emitOR_RegInd_Reg_Quad(SP, T0);
    }
  }

  /**
   * Emit code to implement the lxor bytecode
   */
  @Override
  protected final void emit_lxor() {
    if (VM.BuildFor32Addr) {
      asm.emitPOP_Reg(T0);        // low
      asm.emitPOP_Reg(S0);        // high
      asm.emitXOR_RegInd_Reg(SP, T0);
      asm.emitXOR_RegDisp_Reg(SP, ONE_SLOT, S0);
    } else {
      asm.emitPOP_Reg(T0); // long value
      asm.emitPOP_Reg(S0); // throw away slot
      asm.emitXOR_RegInd_Reg_Quad(SP, T0);
    }
  }

  /*
  * float ALU
  */

  /**
   * Emit code to implement the fadd bytecode
   */
  @Override
  protected final void emit_fadd() {
    if (SSE2_BASE) {
      asm.emitMOVSS_Reg_RegInd(XMM0, SP);            // XMM0 = value2
      asm.emitADDSS_Reg_RegDisp(XMM0, SP, ONE_SLOT); // XMM0 += value1
      adjustStack(WORDSIZE, true);                   // throw away slot
      asm.emitMOVSS_RegInd_Reg(SP, XMM0);            // set result on stack
    } else {
      asm.emitFLD_Reg_RegInd(FP0, SP);               // FPU reg. stack <- value2
      asm.emitFADD_Reg_RegDisp(FP0, SP, ONE_SLOT);   // FPU reg. stack += value1
      adjustStack(WORDSIZE, true);                   // throw away slot
      asm.emitFSTP_RegInd_Reg(SP, FP0);              // POP FPU reg. stack onto stack
    }
  }

  /**
   * Emit code to implement the fsub bytecode
   */
  @Override
  protected final void emit_fsub() {
    if (SSE2_BASE) {
      asm.emitMOVSS_Reg_RegDisp(XMM0, SP, ONE_SLOT); // XMM0 = value1
      asm.emitSUBSS_Reg_RegInd(XMM0, SP);            // XMM0 -= value2
      adjustStack(WORDSIZE, true);                   // throw away slot
      asm.emitMOVSS_RegInd_Reg(SP, XMM0);            // set result on stack
    } else {
      asm.emitFLD_Reg_RegDisp(FP0, SP, ONE_SLOT);    // FPU reg. stack <- value1
      asm.emitFSUB_Reg_RegDisp(FP0, SP, NO_SLOT);    // FPU reg. stack -= value2
      adjustStack(WORDSIZE, true);                   // throw away slot
      asm.emitFSTP_RegInd_Reg(SP, FP0);              // POP FPU reg. stack onto stack
    }
  }

  /**
   * Emit code to implement the fmul bytecode
   */
  @Override
  protected final void emit_fmul() {
    if (SSE2_BASE) {
      asm.emitMOVSS_Reg_RegInd(XMM0, SP);            // XMM0 = value2
      asm.emitMULSS_Reg_RegDisp(XMM0, SP, ONE_SLOT); // XMM0 *= value1
      adjustStack(WORDSIZE, true);                   // throw away slot
      asm.emitMOVSS_RegInd_Reg(SP, XMM0);            // set result on stack
    } else {
      asm.emitFLD_Reg_RegInd(FP0, SP);               // FPU reg. stack <- value2
      asm.emitFMUL_Reg_RegDisp(FP0, SP, ONE_SLOT);   // FPU reg. stack *= value1
      adjustStack(WORDSIZE, true);                   // throw away slot
      asm.emitFSTP_RegInd_Reg(SP, FP0);              // POP FPU reg. stack onto stack
    }
  }

  /**
   * Emit code to implement the fdiv bytecode
   */
  @Override
  protected final void emit_fdiv() {
    if (SSE2_BASE) {
      asm.emitMOVSS_Reg_RegDisp(XMM0, SP, ONE_SLOT); // XMM0 = value1
      asm.emitDIVSS_Reg_RegInd(XMM0, SP);            // XMM0 /= value2
      adjustStack(WORDSIZE, true);                   // throw away slot
      asm.emitMOVSS_RegInd_Reg(SP, XMM0);            // set result on stack
    } else {
      asm.emitFLD_Reg_RegDisp(FP0, SP, ONE_SLOT);    // FPU reg. stack <- value1
      asm.emitFDIV_Reg_RegDisp(FP0, SP, NO_SLOT);    // FPU reg. stack /= value2
      adjustStack(WORDSIZE, true);                   // throw away slot
      asm.emitFSTP_RegInd_Reg(SP, FP0);              // POP FPU reg. stack onto stack
    }
  }

  /**
   * Emit code to implement the frem bytecode
   */
  @Override
  protected final void emit_frem() {
    // TODO: Something else when SSE2?
    asm.emitFLD_Reg_RegInd(FP0, SP);                 // FPU reg. stack <- value2, or a
    asm.emitFLD_Reg_RegDisp(FP0, SP, ONE_SLOT);      // FPU reg. stack <- value1, or b
    asm.emitFPREM();                                 // FPU reg. stack <- a%b
    asm.emitFSTP_RegDisp_Reg(SP, ONE_SLOT, FP0);     // POP FPU reg. stack (results) onto java stack
    asm.emitFSTP_RegInd_Reg(SP, FP0);                // POP FPU reg. stack onto java stack
    adjustStack(WORDSIZE, true);                     // throw away slot
  }

  /**
   * Emit code to implement the fneg bytecode
   */
  @Override
  protected final void emit_fneg() {
    // flip sign bit
    asm.emitXOR_RegInd_Imm(SP, 0x80000000);
  }

  /*
  * double ALU
  */

  /**
   * Emit code to implement the dadd bytecode
   */
  @Override
  protected final void emit_dadd() {
    if (SSE2_BASE) {
      asm.emitMOVLPD_Reg_RegInd(XMM0, SP);               // XMM0 = value2
      asm.emitADDSD_Reg_RegDisp(XMM0, SP, TWO_SLOTS);    // XMM0 += value1
      adjustStack(WORDSIZE*2, true);                     // throw away long slot
      asm.emitMOVLPD_RegInd_Reg(SP, XMM0);               // set result on stack
    } else {
      asm.emitFLD_Reg_RegInd_Quad(FP0, SP);              // FPU reg. stack <- value2
      asm.emitFADD_Reg_RegDisp_Quad(FP0, SP, TWO_SLOTS); // FPU reg. stack += value1
      adjustStack(WORDSIZE*2, true);                     // throw away long slot
      asm.emitFSTP_RegInd_Reg_Quad(SP, FP0);             // POP FPU reg. stack onto stack
    }
  }

  /**
   * Emit code to implement the dsub bytecode
   */
  @Override
  protected final void emit_dsub() {
    if (SSE2_BASE) {
      asm.emitMOVLPD_Reg_RegDisp(XMM0, SP, TWO_SLOTS);   // XMM0 = value1
      asm.emitSUBSD_Reg_RegInd(XMM0, SP);                // XMM0 -= value2
      adjustStack(WORDSIZE*2, true);                     // throw away long slot
      asm.emitMOVLPD_RegInd_Reg(SP, XMM0);               // set result on stack
    } else {
      asm.emitFLD_Reg_RegDisp_Quad(FP0, SP, TWO_SLOTS);  // FPU reg. stack <- value1
      asm.emitFSUB_Reg_RegDisp_Quad(FP0, SP, NO_SLOT);   // FPU reg. stack -= value2
      adjustStack(WORDSIZE*2, true);                     // throw away long slot
      asm.emitFSTP_RegInd_Reg_Quad(SP, FP0);             // POP FPU reg. stack onto stack
    }
  }

  /**
   * Emit code to implement the dmul bytecode
   */
  @Override
  protected final void emit_dmul() {
    if (SSE2_BASE) {
      asm.emitMOVLPD_Reg_RegInd(XMM0, SP);               // XMM0 = value2
      asm.emitMULSD_Reg_RegDisp(XMM0, SP, TWO_SLOTS);    // XMM0 *= value1
      adjustStack(WORDSIZE*2, true);                     // throw away long slot
      asm.emitMOVLPD_RegInd_Reg(SP, XMM0);               // set result on stack
    } else {
      asm.emitFLD_Reg_RegInd_Quad(FP0, SP);              // FPU reg. stack <- value2
      asm.emitFMUL_Reg_RegDisp_Quad(FP0, SP, TWO_SLOTS); // FPU reg. stack *= value1
      adjustStack(WORDSIZE*2, true);                     // throw away long slot
      asm.emitFSTP_RegInd_Reg_Quad(SP, FP0);             // POP FPU reg. stack onto stack
    }
  }

  /**
   * Emit code to implement the ddiv bytecode
   */
  @Override
  protected final void emit_ddiv() {
    if (SSE2_BASE) {
      asm.emitMOVLPD_Reg_RegDisp(XMM0, SP, TWO_SLOTS);   // XMM0 = value1
      asm.emitDIVSD_Reg_RegInd(XMM0, SP);                // XMM0 /= value2
      adjustStack(WORDSIZE*2, true);                     // throw away long slot
      asm.emitMOVLPD_RegInd_Reg(SP, XMM0);               // set result on stack
    } else {
      asm.emitFLD_Reg_RegDisp_Quad(FP0, SP, TWO_SLOTS);  // FPU reg. stack <- value1
      asm.emitFDIV_Reg_RegInd_Quad(FP0, SP);             // FPU reg. stack /= value2
      adjustStack(WORDSIZE*2, true);                     // throw away long slot
      asm.emitFSTP_RegInd_Reg_Quad(SP, FP0);             // POP FPU reg. stack onto stack
    }
  }

  /**
   * Emit code to implement the drem bytecode
   */
  @Override
  protected final void emit_drem() {
    // TODO: Something else when SSE2?
    asm.emitFLD_Reg_RegInd_Quad(FP0, SP);                // FPU reg. stack <- value2, or a
    asm.emitFLD_Reg_RegDisp_Quad(FP0, SP, TWO_SLOTS);    // FPU reg. stack <- value1, or b
    asm.emitFPREM();                                     // FPU reg. stack <- a%b
    asm.emitFSTP_RegDisp_Reg_Quad(SP, TWO_SLOTS, FP0);   // POP FPU reg. stack (result) onto java stack
    asm.emitFSTP_RegInd_Reg_Quad(SP, FP0);               // POP FPU reg. stack onto java stack
    adjustStack(WORDSIZE*2, true);                       // throw away long slot
  }

  /**
   * Emit code to implement the dneg bytecode
   */
  @Override
  protected final void emit_dneg() {
    // flip sign bit
    asm.emitXOR_RegDisp_Imm(SP, Offset.fromIntZeroExtend(4), 0x80000000);
  }

  /*
   * conversion ops
   */

  /**
   * Emit code to implement the i2l bytecode
   */
  @Override
  protected final void emit_i2l() {
    if (VM.BuildFor32Addr) {
      asm.emitPUSH_RegInd(SP);                   // duplicate int on stack
      asm.emitSAR_RegDisp_Imm(SP, ONE_SLOT, 31); // sign extend as high word of long
    } else {
      asm.emitPOP_Reg(EAX);
      asm.emitCDQE();
      adjustStack(-WORDSIZE, true);
      asm.emitPUSH_Reg(EAX);
    }
  }

  /**
   * Emit code to implement the l2i bytecode
   */
  @Override
  protected final void emit_l2i() {
    asm.emitPOP_Reg(T0);         // long value
    adjustStack(WORDSIZE, true); // throw away slot
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to implement the i2f bytecode
   */
  @Override
  protected final void emit_i2f() {
    if (SSE2_BASE) {
      asm.emitCVTSI2SS_Reg_RegInd(XMM0, SP);
      asm.emitMOVSS_RegInd_Reg(SP, XMM0);
    } else {
      asm.emitFILD_Reg_RegInd(FP0, SP);
      asm.emitFSTP_RegInd_Reg(SP, FP0);
    }
  }

  /**
   * Emit code to implement the i2d bytecode
   */
  @Override
  protected final void emit_i2d() {
    if (SSE2_BASE) {
      asm.emitCVTSI2SD_Reg_RegInd(XMM0, SP);
      adjustStack(-WORDSIZE, true); // grow the stack
      asm.emitMOVLPD_RegInd_Reg(SP, XMM0);
    } else {
      asm.emitFILD_Reg_RegInd(FP0, SP);
      adjustStack(-WORDSIZE, true); // grow the stack
      asm.emitFSTP_RegInd_Reg_Quad(SP, FP0);
    }
  }

  /**
   * Emit code to implement the l2f bytecode
   */
  @Override
  protected final void emit_l2f() {
    asm.emitFILD_Reg_RegInd_Quad(FP0, SP);
    adjustStack(WORDSIZE, true); // shrink the stack
    asm.emitFSTP_RegInd_Reg(SP, FP0);
  }

  /**
   * Emit code to implement the l2d bytecode
   */
  @Override
  protected final void emit_l2d() {
    asm.emitFILD_Reg_RegInd_Quad(FP0, SP);
    asm.emitFSTP_RegInd_Reg_Quad(SP, FP0);
  }

  /**
   * Emit code to implement the f2d bytecode
   */
  @Override
  protected final void emit_f2d() {
    if (SSE2_BASE) {
      asm.emitCVTSS2SD_Reg_RegInd(XMM0, SP);
      adjustStack(-WORDSIZE, true); // throw away slot
      asm.emitMOVLPD_RegInd_Reg(SP, XMM0);
    } else {
      asm.emitFLD_Reg_RegInd(FP0, SP);
      adjustStack(-WORDSIZE, true); // throw away slot
      asm.emitFSTP_RegInd_Reg_Quad(SP, FP0);
    }
  }

  /**
   * Emit code to implement the d2f bytecode
   */
  @Override
  protected final void emit_d2f() {
    if (SSE2_BASE) {
      asm.emitCVTSD2SS_Reg_RegInd(XMM0, SP);
      adjustStack(WORDSIZE, true); // throw away slot
      asm.emitMOVSS_RegInd_Reg(SP, XMM0);
    } else {
      asm.emitFLD_Reg_RegInd_Quad(FP0, SP);
      adjustStack(WORDSIZE, true); // throw away slot
      asm.emitFSTP_RegInd_Reg(SP, FP0);
    }
  }

  /**
   * Emit code to implement the f2i bytecode
   */
  @Override
  protected final void emit_f2i() {
    if (SSE2_BASE) {
      // Set up max int in XMM0
      asm.emitMOVSS_Reg_Abs(XMM0, Magic.getTocPointer().plus(Entrypoints.maxintFloatField.getOffset()));
      // Set up value in XMM1
      asm.emitMOVSS_Reg_RegInd(XMM1, SP);
      // if value > maxint or NaN goto fr1; FP0 = value
      asm.emitUCOMISS_Reg_Reg(XMM0, XMM1);
      ForwardReference fr1 = asm.forwardJcc(Assembler.LLE);
      asm.emitCVTTSS2SI_Reg_Reg(T0, XMM1);
      asm.emitMOV_RegInd_Reg(SP, T0);
      ForwardReference fr2 = asm.forwardJMP();
      fr1.resolve(asm);
      ForwardReference fr3 = asm.forwardJcc(Assembler.PE); // if value == NaN goto fr3
      asm.emitMOV_RegInd_Imm(SP, 0x7FFFFFFF);
      ForwardReference fr4 = asm.forwardJMP();
      fr3.resolve(asm);
      asm.emitMOV_RegInd_Imm(SP, 0);
      fr2.resolve(asm);
      fr4.resolve(asm);
    } else {
      // TODO: use x87 operations to do this conversion inline taking care of
      // the boundary cases that differ between x87 and Java

      // (1) save RVM nonvolatiles
      int numNonVols = NONVOLATILE_GPRS.length;
      Offset off = Offset.fromIntSignExtend(numNonVols * WORDSIZE);
      for (int i = 0; i < numNonVols; i++) {
        asm.emitPUSH_Reg(NONVOLATILE_GPRS[i]);
      }
      // (2) Push arg to C function
      asm.emitPUSH_RegDisp(SP, off);
      // (3) invoke C function through bootrecord
      asm.emitMOV_Reg_Abs(S0, Magic.getTocPointer().plus(Entrypoints.the_boot_recordField.getOffset()));
      asm.emitCALL_RegDisp(S0, Entrypoints.sysFloatToIntIPField.getOffset());
      // (4) pop argument;
      asm.emitPOP_Reg(S0);
      // (5) restore RVM nonvolatiles
      for (int i = numNonVols - 1; i >= 0; i--) {
        asm.emitPOP_Reg(NONVOLATILE_GPRS[i]);
      }
      // (6) put result on expression stack
      asm.emitMOV_RegDisp_Reg(SP, NO_SLOT, T0);
    }
  }

  /**
   * Emit code to implement the f2l bytecode
   */
  @Override
  protected final void emit_f2l() {
    if (VM.BuildFor32Addr) {
      // TODO: SSE3 has a FISTTP instruction that stores the value with truncation
      // meaning the FPSCW can be left alone

      // Setup value into FP1
      asm.emitFLD_Reg_RegInd(FP0, SP);
      // Setup maxlong into FP0
      asm.emitFLD_Reg_Abs(FP0, Magic.getTocPointer().plus(Entrypoints.maxlongFloatField.getOffset()));
      // if value > maxlong or NaN goto fr1; FP0 = value
      asm.emitFUCOMIP_Reg_Reg(FP0, FP1);
      ForwardReference fr1 = asm.forwardJcc(Assembler.LLE);
      // Normally the status and control word rounds numbers, but for conversion
      // to an integer/long value we want truncation. We therefore save the FPSCW,
      // set it to truncation perform operation then restore
      adjustStack(-WORDSIZE, true);                            // Grow the stack
      asm.emitFNSTCW_RegDisp(SP, MINUS_ONE_SLOT);              // [SP-4] = fpscw
      asm.emitMOVZX_Reg_RegDisp_Word(T0, SP, MINUS_ONE_SLOT);  // EAX = fpscw
      asm.emitOR_Reg_Imm(T0, 0xC00);                           // EAX = FPSCW in truncate mode
      asm.emitMOV_RegInd_Reg(SP, T0);                          // [SP] = new fpscw value
      asm.emitFLDCW_RegInd(SP);                                // Set FPSCW
      asm.emitFISTP_RegInd_Reg_Quad(SP, FP0);                  // Store 64bit long
      asm.emitFLDCW_RegDisp(SP, MINUS_ONE_SLOT);               // Restore FPSCW
      ForwardReference fr2 = asm.forwardJMP();
      fr1.resolve(asm);
      asm.emitFSTP_Reg_Reg(FP0, FP0);                          // pop FPU*1
      ForwardReference fr3 = asm.forwardJcc(Assembler.PE); // if value == NaN goto fr3
      asm.emitMOV_RegInd_Imm(SP, 0x7FFFFFFF);
      asm.emitPUSH_Imm(-1);
      ForwardReference fr4 = asm.forwardJMP();
      fr3.resolve(asm);
      asm.emitMOV_RegInd_Imm(SP, 0);
      asm.emitPUSH_Imm(0);
      fr2.resolve(asm);
      fr4.resolve(asm);
    } else {
      // Set up max int in XMM0
      asm.emitMOVSS_Reg_Abs(XMM0, Magic.getTocPointer().plus(Entrypoints.maxlongFloatField.getOffset()));
      // Set up value in XMM1
      asm.emitMOVSS_Reg_RegInd(XMM1, SP);
      // if value > maxint or NaN goto fr1; FP0 = value
      asm.emitUCOMISS_Reg_Reg(XMM0, XMM1);
      ForwardReference fr1 = asm.forwardJcc(Assembler.LLE);
      asm.emitCVTTSS2SI_Reg_Reg_Quad(T0, XMM1);
      ForwardReference fr2 = asm.forwardJMP();
      fr1.resolve(asm);
      ForwardReference fr3 = asm.forwardJcc(Assembler.PE); // if value == NaN goto fr3
      asm.emitMOV_Reg_Imm_Quad(T0, 0x7FFFFFFFFFFFFFFFL);
      ForwardReference fr4 = asm.forwardJMP();
      fr3.resolve(asm);
      asm.emitXOR_Reg_Reg(T0, T0);
      fr2.resolve(asm);
      fr4.resolve(asm);
      asm.emitPUSH_Reg(T0);
    }
  }

  /**
   * Emit code to implement the d2i bytecode
   */
  @Override
  protected final void emit_d2i() {
    if (SSE2_BASE) {
      // Set up max int in XMM0
      asm.emitMOVLPD_Reg_Abs(XMM0, Magic.getTocPointer().plus(Entrypoints.maxintField.getOffset()));
      // Set up value in XMM1
      asm.emitMOVLPD_Reg_RegInd(XMM1, SP);
      adjustStack(WORDSIZE, true); // throw away slot
      // if value > maxint or NaN goto fr1; FP0 = value
      asm.emitUCOMISD_Reg_Reg(XMM0, XMM1);
      ForwardReference fr1 = asm.forwardJcc(Assembler.LLE);
      asm.emitCVTTSD2SI_Reg_Reg(T0, XMM1);
      asm.emitMOV_RegInd_Reg(SP, T0);
      ForwardReference fr2 = asm.forwardJMP();
      fr1.resolve(asm);
      ForwardReference fr3 = asm.forwardJcc(Assembler.PE); // if value == NaN goto fr3
      asm.emitMOV_RegInd_Imm(SP, 0x7FFFFFFF);
      ForwardReference fr4 = asm.forwardJMP();
      fr3.resolve(asm);
      asm.emitMOV_RegInd_Imm(SP, 0);
      fr2.resolve(asm);
      fr4.resolve(asm);
    } else {
      // TODO: use x87 operations to do this conversion inline taking care of
      // the boundary cases that differ between x87 and Java
      // (1) save RVM nonvolatiles
      int numNonVols = NONVOLATILE_GPRS.length;
      Offset off = Offset.fromIntSignExtend(numNonVols * WORDSIZE);
      for (int i = 0; i < numNonVols; i++) {
        asm.emitPUSH_Reg(NONVOLATILE_GPRS[i]);
      }
      // (2) Push args to C function (reversed)
      asm.emitPUSH_RegDisp(SP, off.plus(4));
      asm.emitPUSH_RegDisp(SP, off.plus(4));
      // (3) invoke C function through bootrecord
      asm.emitMOV_Reg_Abs(S0, Magic.getTocPointer().plus(Entrypoints.the_boot_recordField.getOffset()));
      asm.emitCALL_RegDisp(S0, Entrypoints.sysDoubleToIntIPField.getOffset());
      // (4) pop arguments
      asm.emitPOP_Reg(S0);
      asm.emitPOP_Reg(S0);
      // (5) restore RVM nonvolatiles
      for (int i = numNonVols - 1; i >= 0; i--) {
        asm.emitPOP_Reg(NONVOLATILE_GPRS[i]);
      }
      // (6) put result on expression stack
      adjustStack(WORDSIZE, true); // throw away slot
      asm.emitMOV_RegDisp_Reg(SP, NO_SLOT, T0);
    }
  }

  /**
   * Emit code to implement the d2l bytecode
   */
  @Override
  protected final void emit_d2l() {
    if (VM.BuildFor32Addr) {
      // TODO: SSE3 has a FISTTP instruction that stores the value with truncation
      // meaning the FPSCW can be left alone

      // Setup value into FP1
      asm.emitFLD_Reg_RegInd_Quad(FP0, SP);
      // Setup maxlong into FP0
      asm.emitFLD_Reg_Abs_Quad(FP0, Magic.getTocPointer().plus(Entrypoints.maxlongField.getOffset()));
      // if value > maxlong or NaN goto fr1; FP0 = value
      asm.emitFUCOMIP_Reg_Reg(FP0, FP1);
      ForwardReference fr1 = asm.forwardJcc(Assembler.LLE);
      // Normally the status and control word rounds numbers, but for conversion
      // to an integer/long value we want truncation. We therefore save the FPSCW,
      // set it to truncation perform operation then restore
      asm.emitFNSTCW_RegDisp(SP, MINUS_ONE_SLOT);              // [SP-4] = fpscw
      asm.emitMOVZX_Reg_RegDisp_Word(T0, SP, MINUS_ONE_SLOT);  // EAX = fpscw
      asm.emitOR_Reg_Imm(T0, 0xC00);                           // EAX = FPSCW in truncate mode
      asm.emitMOV_RegInd_Reg(SP, T0);                          // [SP] = new fpscw value
      asm.emitFLDCW_RegInd(SP);                                // Set FPSCW
      asm.emitFISTP_RegInd_Reg_Quad(SP, FP0);                  // Store 64bit long
      asm.emitFLDCW_RegDisp(SP, MINUS_ONE_SLOT);               // Restore FPSCW
      ForwardReference fr2 = asm.forwardJMP();
      fr1.resolve(asm);
      asm.emitFSTP_Reg_Reg(FP0, FP0);                          // pop FPU*1
      ForwardReference fr3 = asm.forwardJcc(Assembler.PE); // if value == NaN goto fr3
      asm.emitMOV_RegDisp_Imm(SP, ONE_SLOT, 0x7FFFFFFF);
      asm.emitMOV_RegInd_Imm(SP, -1);
      ForwardReference fr4 = asm.forwardJMP();
      fr3.resolve(asm);
      asm.emitMOV_RegDisp_Imm(SP, ONE_SLOT, 0);
      asm.emitMOV_RegInd_Imm(SP, 0);
      fr2.resolve(asm);
      fr4.resolve(asm);
    } else {
      // Set up max int in XMM0
      asm.emitMOVLPD_Reg_Abs(XMM0, Magic.getTocPointer().plus(Entrypoints.maxlongFloatField.getOffset()));
      // Set up value in XMM1
      asm.emitMOVLPD_Reg_RegInd(XMM1, SP);
      adjustStack(WORDSIZE, true);
      // if value > maxint or NaN goto fr1; FP0 = value
      asm.emitUCOMISD_Reg_Reg(XMM0, XMM1);
      ForwardReference fr1 = asm.forwardJcc(Assembler.LLE);
      asm.emitCVTTSD2SIQ_Reg_Reg_Quad(T0, XMM1);
      ForwardReference fr2 = asm.forwardJMP();
      fr1.resolve(asm);
      ForwardReference fr3 = asm.forwardJcc(Assembler.PE); // if value == NaN goto fr3
      asm.emitMOV_Reg_Imm_Quad(T0, 0x7FFFFFFFFFFFFFFFL);
      ForwardReference fr4 = asm.forwardJMP();
      fr3.resolve(asm);
      asm.emitXOR_Reg_Reg(T0, T0);
      fr2.resolve(asm);
      fr4.resolve(asm);
      asm.emitPUSH_Reg(T0);
    }
  }

  /**
   * Emit code to implement the i2b bytecode
   */
  @Override
  protected final void emit_i2b() {
    // This could be coded as 2 instructions as follows:
    // asm.emitMOVSX_Reg_RegInd_Byte(T0, SP);
    // asm.emitMOV_RegInd_Reg(SP, T0);
    // Indirection via ESP requires an extra byte for the indirection, so the
    // total code size is 6 bytes. The 3 instruction version below is only 4
    // bytes long and faster on Pentium 4 benchmarks.
    asm.emitPOP_Reg(T0);
    asm.emitMOVSX_Reg_Reg_Byte(T0, T0);
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to implement the i2c bytecode
   */
  @Override
  protected final void emit_i2c() {
    // This could be coded as zeroing the high 16bits on stack:
    // asm.emitMOV_RegDisp_Imm_Word(SP, Offset.fromIntSignExtend(2), 0);
    // or as 2 instructions:
    // asm.emitMOVZX_Reg_RegInd_Word(T0, SP);
    // asm.emitMOV_RegInd_Reg(SP, T0);
    // Benchmarks show the following sequence to be more optimal on a Pentium 4
    asm.emitPOP_Reg(T0);
    asm.emitMOVZX_Reg_Reg_Word(T0, T0);
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to implement the i2s bytecode
   */
  @Override
  protected final void emit_i2s() {
    // This could be coded as 2 instructions as follows:
    // asm.emitMOVSX_Reg_RegInd_Word(T0, SP);
    // asm.emitMOV_RegInd_Reg(SP, T0);
    // Indirection via ESP requires an extra byte for the indirection, so the
    // total code size is 6 bytes. The 3 instruction version below is only 4
    // bytes long and faster on Pentium 4 benchmarks.
    asm.emitPOP_Reg(T0);
    asm.emitMOVSX_Reg_Reg_Word(T0, T0);
    asm.emitPUSH_Reg(T0);
  }

  /*
  * comparision ops
  */

  /**
   * Emit code to implement the lcmp bytecode
   */
  @Override
  protected final void emit_lcmp() {
    if (VM.BuildFor32Addr) {
      asm.emitPOP_Reg(T0);                // (S0:T0) = (high half value2: low half value2)
      asm.emitPOP_Reg(S0);
      asm.emitPOP_Reg(T1);                // (..:T1) = (.. : low half of value1)
      asm.emitSUB_Reg_Reg(T1, T0);        // T1 = T1 - T0
      asm.emitPOP_Reg(T0);                // (T0:..) = (high half of value1 : ..)
      // NB pop does not alter the carry register
      asm.emitSBB_Reg_Reg(T0, S0);        // T0 = T0 - S0 - CF
      ForwardReference fr1 = asm.forwardJcc(Assembler.LT);
      asm.emitOR_Reg_Reg(T0, T1);         // T0 = T0 | T1
      ForwardReference fr2 = asm.forwardJcc(Assembler.NE);
      asm.emitPUSH_Imm(0);                // push result on stack
      ForwardReference fr3 = asm.forwardJMP();
      fr2.resolve(asm);
      asm.emitPUSH_Imm(1);                // push result on stack
      ForwardReference fr4 = asm.forwardJMP();
      fr1.resolve(asm);
      asm.emitPUSH_Imm(-1);                // push result on stack
      fr3.resolve(asm);
      fr4.resolve(asm);
    } else {
      // TODO: consider optimizing to z = ((x - y) >> 63) - ((y - x) >> 63)
      asm.emitPOP_Reg(T0);                // T0 is long value
      adjustStack(WORDSIZE, true);        // throw away slot
      asm.emitPOP_Reg(T1);                // T1 is long value
      adjustStack(WORDSIZE, true);        // throw away slot
      asm.emitCMP_Reg_Reg_Quad(T1, T0);        // T1 = T1 - T0
      ForwardReference fr1 = asm.forwardJcc(Assembler.LT);
      ForwardReference fr2 = asm.forwardJcc(Assembler.NE);
      asm.emitPUSH_Imm(0);                // push result on stack
      ForwardReference fr3 = asm.forwardJMP();
      fr2.resolve(asm);
      asm.emitPUSH_Imm(1);                // push result on stack
      ForwardReference fr4 = asm.forwardJMP();
      fr1.resolve(asm);
      asm.emitPUSH_Imm(-1);                // push result on stack
      fr3.resolve(asm);
      fr4.resolve(asm);
    }
  }

  /**
   * Emit code to implement the fcmpl bytecode
   */
  @Override
  protected final void emit_fcmpl() {
    if (SSE2_BASE) {
      asm.emitMOVSS_Reg_RegInd(XMM0, SP);               // XMM0 = value2
      asm.emitMOVSS_Reg_RegDisp(XMM1, SP, ONE_SLOT);    // XMM1 = value1
      adjustStack(WORDSIZE*2, true);                    // throw away slots
      asm.emitUCOMISS_Reg_Reg(XMM1, XMM0);              // compare value1 and value2
    } else {
      asm.emitFLD_Reg_RegInd(FP0, SP);                  // Setup value2 into FP1,
      asm.emitFLD_Reg_RegDisp(FP0, SP, ONE_SLOT);       // value1 into FP0
      adjustStack(WORDSIZE*2, true);                    // throw away slots
      asm.emitFUCOMIP_Reg_Reg(FP0, FP1);                // compare and pop FPU *1
    }
    ForwardReference fr1 = asm.forwardJcc(Assembler.LLT);
    ForwardReference fr2 = asm.forwardJcc(Assembler.LGT);
    asm.emitPUSH_Imm(0);                                // push result on stack
    ForwardReference fr3 = asm.forwardJMP();
    fr2.resolve(asm);
    asm.emitPUSH_Imm(1);                                // push result on stack
    ForwardReference fr4 = asm.forwardJMP();
    fr1.resolve(asm);
    asm.emitPUSH_Imm(-1);                               // push result on stack
    fr3.resolve(asm);
    fr4.resolve(asm);
    if (!SSE2_BASE) {
      asm.emitFSTP_Reg_Reg(FP0, FP0);                   // pop FPU*1
    }
  }

  /**
   * Emit code to implement the fcmpg bytecode
   */
  @Override
  protected final void emit_fcmpg() {
    if (SSE2_BASE) {
      asm.emitMOVSS_Reg_RegInd(XMM0, SP);               // XMM0 = value2
      asm.emitMOVSS_Reg_RegDisp(XMM1, SP, ONE_SLOT);    // XMM1 = value1
      adjustStack(WORDSIZE*2, true);                    // throw away slots
      asm.emitUCOMISS_Reg_Reg(XMM1, XMM0);              // compare value1 and value2
    } else {
      asm.emitFLD_Reg_RegInd(FP0, SP);                  // Setup value2 into FP1,
      asm.emitFLD_Reg_RegDisp(FP0, SP, ONE_SLOT);       // value1 into FP0
      adjustStack(WORDSIZE*2, true);                    // throw away slots
      asm.emitFUCOMIP_Reg_Reg(FP0, FP1);                // compare and pop FPU *1
    }
    // TODO: It's bad to have 2 conditional jumps within 16bytes of each other
    ForwardReference fr1 = asm.forwardJcc(Assembler.LGT); // if > goto push 1
    ForwardReference fr3 = asm.forwardJcc(Assembler.NE);  // if < goto push -1
    ForwardReference fr2 = asm.forwardJcc(Assembler.PE);  // if unordered goto push 1
    asm.emitPUSH_Imm(0);                                // push result of 0 on stack
    ForwardReference fr4 = asm.forwardJMP();
    fr1.resolve(asm);
    fr2.resolve(asm);
    asm.emitPUSH_Imm(1);                                // push result of 1 on stack
    ForwardReference fr5 = asm.forwardJMP();
    fr3.resolve(asm);
    asm.emitPUSH_Imm(-1);                               // push result of -1 on stack
    fr4.resolve(asm);
    fr5.resolve(asm);
    if (!SSE2_BASE) {
      asm.emitFSTP_Reg_Reg(FP0, FP0);                   // pop FPU*1
    }
  }

  /**
   * Emit code to implement the dcmpl bytecode
   */
  @Override
  protected final void emit_dcmpl() {
    if (SSE2_BASE) {
      asm.emitMOVLPD_Reg_RegInd(XMM0, SP);              // XMM0 = value2
      asm.emitMOVLPD_Reg_RegDisp(XMM1, SP, TWO_SLOTS);  // XMM1 = value1
      adjustStack(WORDSIZE*4, true);                    // throw away slots
      asm.emitUCOMISD_Reg_Reg(XMM1, XMM0);              // compare value1 and value2
    } else {
      asm.emitFLD_Reg_RegInd_Quad(FP0, SP);             // Setup value2 into FP1,
      asm.emitFLD_Reg_RegDisp_Quad(FP0, SP, TWO_SLOTS); // value1 into FP0
      adjustStack(WORDSIZE*4, true);                    // throw away slots
      asm.emitFUCOMIP_Reg_Reg(FP0, FP1);                // compare and pop FPU *1
    }
    ForwardReference fr1 = asm.forwardJcc(Assembler.LLT);
    ForwardReference fr2 = asm.forwardJcc(Assembler.LGT);
    asm.emitPUSH_Imm(0);                                // push result on stack
    ForwardReference fr3 = asm.forwardJMP();
    fr2.resolve(asm);
    asm.emitPUSH_Imm(1);                                // push result on stack
    ForwardReference fr4 = asm.forwardJMP();
    fr1.resolve(asm);
    asm.emitPUSH_Imm(-1);                               // push result on stack
    fr3.resolve(asm);
    fr4.resolve(asm);
    if (!SSE2_BASE) {
      asm.emitFSTP_Reg_Reg(FP0, FP0);                   // pop FPU*1
    }
  }

  /**
   * Emit code to implement the dcmpg bytecode
   */
  @Override
  protected final void emit_dcmpg() {
    if (SSE2_BASE) {
      asm.emitMOVLPD_Reg_RegInd(XMM0, SP);              // XMM0 = value2
      asm.emitMOVLPD_Reg_RegDisp(XMM1, SP, TWO_SLOTS);  // XMM1 = value1
      adjustStack(WORDSIZE*4, true);                    // throw away slots
      asm.emitUCOMISD_Reg_Reg(XMM1, XMM0);              // compare value1 and value2
    } else {
      asm.emitFLD_Reg_RegInd_Quad(FP0, SP);             // Setup value2 into FP1,
      asm.emitFLD_Reg_RegDisp_Quad(FP0, SP, TWO_SLOTS); // value1 into FP0
      adjustStack(WORDSIZE*4, true);                    // throw away slots
      asm.emitFUCOMIP_Reg_Reg(FP0, FP1);                // compare and pop FPU *1
    }
    // TODO: It's bad to have 2 conditional jumps within 16bytes of each other
    ForwardReference fr1 = asm.forwardJcc(Assembler.LGT); // if > goto push1
    ForwardReference fr3 = asm.forwardJcc(Assembler.NE);  // if < goto push -1
    ForwardReference fr2 = asm.forwardJcc(Assembler.PE);  // if unordered goto push 1
    asm.emitPUSH_Imm(0);                                // push result of 0 on stack
    ForwardReference fr4 = asm.forwardJMP();
    fr1.resolve(asm);
    fr2.resolve(asm);
    asm.emitPUSH_Imm(1);                                // push result of 1 on stack
    ForwardReference fr5 = asm.forwardJMP();
    fr3.resolve(asm);
    asm.emitPUSH_Imm(-1);                               // push result of -1 on stack
    fr4.resolve(asm);
    fr5.resolve(asm);
    if (!SSE2_BASE) {
      asm.emitFSTP_Reg_Reg(FP0, FP0);                   // pop FPU*1
    }
  }

  /*
  * branching
  */

  /**
   * Emit code to implement the ifeg bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_ifeq(int bTarget) {
    asm.emitPOP_Reg(T0);
    asm.emitTEST_Reg_Reg(T0, T0);
    genCondBranch(Assembler.EQ, bTarget);
  }

  /**
   * Emit code to implement the ifne bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_ifne(int bTarget) {
    asm.emitPOP_Reg(T0);
    asm.emitTEST_Reg_Reg(T0, T0);
    genCondBranch(Assembler.NE, bTarget);
  }

  /**
   * Emit code to implement the iflt bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_iflt(int bTarget) {
    asm.emitPOP_Reg(T0);
    asm.emitTEST_Reg_Reg(T0, T0);
    genCondBranch(Assembler.LT, bTarget);
  }

  /**
   * Emit code to implement the ifge bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_ifge(int bTarget) {
    asm.emitPOP_Reg(T0);
    asm.emitTEST_Reg_Reg(T0, T0);
    genCondBranch(Assembler.GE, bTarget);
  }

  /**
   * Emit code to implement the ifgt bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_ifgt(int bTarget) {
    asm.emitPOP_Reg(T0);
    asm.emitTEST_Reg_Reg(T0, T0);
    genCondBranch(Assembler.GT, bTarget);
  }

  /**
   * Emit code to implement the ifle bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_ifle(int bTarget) {
    asm.emitPOP_Reg(T0);
    asm.emitTEST_Reg_Reg(T0, T0);
    genCondBranch(Assembler.LE, bTarget);
  }

  /**
   * Emit code to implement the if_icmpeq bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_if_icmpeq(int bTarget) {
    asm.emitPOP_Reg(S0);
    asm.emitPOP_Reg(T0);
    asm.emitCMP_Reg_Reg(T0, S0);
    genCondBranch(Assembler.EQ, bTarget);
  }

  /**
   * Emit code to implement the if_icmpne bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_if_icmpne(int bTarget) {
    asm.emitPOP_Reg(S0);
    asm.emitPOP_Reg(T0);
    asm.emitCMP_Reg_Reg(T0, S0);
    genCondBranch(Assembler.NE, bTarget);
  }

  /**
   * Emit code to implement the if_icmplt bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_if_icmplt(int bTarget) {
    asm.emitPOP_Reg(S0);
    asm.emitPOP_Reg(T0);
    asm.emitCMP_Reg_Reg(T0, S0);
    genCondBranch(Assembler.LT, bTarget);
  }

  /**
   * Emit code to implement the if_icmpge bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_if_icmpge(int bTarget) {
    asm.emitPOP_Reg(S0);
    asm.emitPOP_Reg(T0);
    asm.emitCMP_Reg_Reg(T0, S0);
    genCondBranch(Assembler.GE, bTarget);
  }

  /**
   * Emit code to implement the if_icmpgt bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_if_icmpgt(int bTarget) {
    asm.emitPOP_Reg(S0);
    asm.emitPOP_Reg(T0);
    asm.emitCMP_Reg_Reg(T0, S0);
    genCondBranch(Assembler.GT, bTarget);
  }

  /**
   * Emit code to implement the if_icmple bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_if_icmple(int bTarget) {
    asm.emitPOP_Reg(S0);
    asm.emitPOP_Reg(T0);
    asm.emitCMP_Reg_Reg(T0, S0);
    genCondBranch(Assembler.LE, bTarget);
  }

  /**
   * Emit code to implement the if_acmpeq bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_if_acmpeq(int bTarget) {
    asm.emitPOP_Reg(S0);
    asm.emitPOP_Reg(T0);
    if (VM.BuildFor32Addr) {
      asm.emitCMP_Reg_Reg(T0, S0);
    } else {
      asm.emitCMP_Reg_Reg_Quad(T0, S0);
    }
    genCondBranch(Assembler.EQ, bTarget);
  }

  /**
   * Emit code to implement the if_acmpne bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_if_acmpne(int bTarget) {
    asm.emitPOP_Reg(S0);
    asm.emitPOP_Reg(T0);
    if (VM.BuildFor32Addr) {
      asm.emitCMP_Reg_Reg(T0, S0);
    } else {
      asm.emitCMP_Reg_Reg_Quad(T0, S0);
    }
    genCondBranch(Assembler.NE, bTarget);
  }

  /**
   * Emit code to implement the ifnull bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_ifnull(int bTarget) {
    asm.emitPOP_Reg(T0);
    if (VM.BuildFor32Addr) {
      asm.emitTEST_Reg_Reg(T0, T0);
    } else {
      asm.emitTEST_Reg_Reg_Quad(T0, T0);
    }
    genCondBranch(Assembler.EQ, bTarget);
  }

  /**
   * Emit code to implement the ifnonnull bytecode
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_ifnonnull(int bTarget) {
    asm.emitPOP_Reg(T0);
    if (VM.BuildFor32Addr) {
      asm.emitTEST_Reg_Reg(T0, T0);
    } else {
      asm.emitTEST_Reg_Reg_Quad(T0, T0);
    }
    genCondBranch(Assembler.NE, bTarget);
  }

  /**
   * Emit code to implement the goto and gotow bytecodes
   * @param bTarget target bytecode of the branch
   */
  @Override
  protected final void emit_goto(int bTarget) {
    int mTarget = bytecodeMap[bTarget];
    asm.emitJMP_ImmOrLabel(mTarget, bTarget);
  }

  /**
   * Emit code to implement the jsr and jsrw bytecode
   * @param bTarget target bytecode of the jsr
   */
  @Override
  protected final void emit_jsr(int bTarget) {
    int mTarget = bytecodeMap[bTarget];
    asm.emitCALL_ImmOrLabel(mTarget, bTarget);
  }

  /**
   * Emit code to implement the ret bytecode
   * @param index local variable containing the return address
   */
  @Override
  protected final void emit_ret(int index) {
    Offset offset = localOffset(index);
    // Can be:
    // asm.emitJMP_RegDisp(ESP, offset);
    // but this will cause call-return branch prediction pairing to fail
    asm.emitPUSH_RegDisp(ESP, offset);
    asm.emitRET();
  }

  /**
   * Emit code to implement the tableswitch bytecode
   * @param defaultval bcIndex of the default target
   * @param low low value of switch
   * @param high high value of switch
   */
  @Override
  protected final void emit_tableswitch(int defaultval, int low, int high) {
    int bTarget = biStart + defaultval;
    int mTarget = bytecodeMap[bTarget];
    int n = high - low + 1;                       // n = number of normal cases (0..n-1)
    asm.emitPOP_Reg(T1);                          // T1 is index of desired case
    asm.emitSUB_Reg_Imm(T1, low);                 // relativize T1
    asm.emitCMP_Reg_Imm(T1, n);                   // 0 <= relative index < n

    if (!VM.runningTool && ((BaselineCompiledMethod) compiledMethod).hasCounterArray()) {
      int firstCounter = edgeCounterIdx;
      edgeCounterIdx += (n + 1);

      // Jump around code for default case
      ForwardReference fr = asm.forwardJcc(Assembler.LLT);
      incEdgeCounter(S0, null, firstCounter + n);
      asm.emitJMP_ImmOrLabel(mTarget, bTarget);
      fr.resolve(asm);

      // Increment counter for the appropriate case
      incEdgeCounter(S0, T1, firstCounter);
    } else {
      asm.emitJCC_Cond_ImmOrLabel(Assembler.LGE, mTarget, bTarget);   // if not, goto default case
    }

    // T0 = EIP at start of method
    asm.emitMETHODSTART_Reg(T0);
    // T0 += [T0 + T1<<2 + ??] - we will patch ?? when we know the placement of the table
    int toPatchAddress = asm.getMachineCodeIndex();
    if (VM.buildFor32Addr()) {
      asm.emitMOV_Reg_RegIdx(T1, T0, T1, Assembler.WORD, Offset.fromIntZeroExtend(Integer.MAX_VALUE));
      asm.emitADD_Reg_Reg(T0, T1);
    } else {
      asm.emitMOV_Reg_RegIdx(T1, T0, T1, Assembler.WORD, Offset.fromIntZeroExtend(Integer.MAX_VALUE));
      asm.emitADD_Reg_Reg_Quad(T0, T1);
    }
    // JMP T0
    asm.emitJMP_Reg(T0);
    asm.emitNOP((4-asm.getMachineCodeIndex()) & 3); // align table
    // create table of offsets from start of method
    asm.patchSwitchTableDisplacement(toPatchAddress);
    for (int i = 0; i < n; i++) {
      int offset = bcodes.getTableSwitchOffset(i);
      bTarget = biStart + offset;
      mTarget = bytecodeMap[bTarget];
      asm.emitOFFSET_Imm_ImmOrLabel(i, mTarget, bTarget);
    }
    bcodes.skipTableSwitchOffsets(n);
  }

  /**
   * Emit code to implement the lookupswitch bytecode.
   * Uses linear search, one could use a binary search tree instead,
   * but this is the baseline compiler, so don't worry about it.
   *
   * @param defaultval bcIndex of the default target
   * @param npairs number of pairs in the lookup switch
   */
  @Override
  protected final void emit_lookupswitch(int defaultval, int npairs) {
    asm.emitPOP_Reg(T0);
    for (int i = 0; i < npairs; i++) {
      int match = bcodes.getLookupSwitchValue(i);
      asm.emitCMP_Reg_Imm(T0, match);
      int offset = bcodes.getLookupSwitchOffset(i);
      int bTarget = biStart + offset;
      int mTarget = bytecodeMap[bTarget];
      if (!VM.runningTool && ((BaselineCompiledMethod) compiledMethod).hasCounterArray()) {
        // Flip conditions so we can jump over the increment of the taken counter.
        ForwardReference fr = asm.forwardJcc(Assembler.NE);
        incEdgeCounter(S0, null, edgeCounterIdx++);
        asm.emitJMP_ImmOrLabel(mTarget, bTarget);
        fr.resolve(asm);
      } else {
        asm.emitJCC_Cond_ImmOrLabel(Assembler.EQ, mTarget, bTarget);
      }
    }
    bcodes.skipLookupSwitchPairs(npairs);
    int bTarget = biStart + defaultval;
    int mTarget = bytecodeMap[bTarget];
    if (!VM.runningTool && ((BaselineCompiledMethod) compiledMethod).hasCounterArray()) {
      incEdgeCounter(S0, null, edgeCounterIdx++); // increment default counter
    }
    asm.emitJMP_ImmOrLabel(mTarget, bTarget);
  }

  /*
   * returns (from function; NOT ret)
   */

  /**
   * Emit code to implement the ireturn bytecode
   */
  @Override
  protected final void emit_ireturn() {
    if (method.isSynchronized()) genMonitorExit();
    asm.emitPOP_Reg(T0);
    genEpilogue(WORDSIZE, WORDSIZE);
  }

  /**
   * Emit code to implement the lreturn bytecode
   */
  @Override
  protected final void emit_lreturn() {
    if (method.isSynchronized()) genMonitorExit();
    if (VM.BuildFor32Addr) {
      asm.emitPOP_Reg(T1); // low half
      asm.emitPOP_Reg(T0); // high half
      genEpilogue(2*WORDSIZE, 2*WORDSIZE);
    } else {
      asm.emitPOP_Reg(T0);
      genEpilogue(2*WORDSIZE, WORDSIZE);
    }
  }

  /**
   * Emit code to implement the freturn bytecode
   */
  @Override
  protected final void emit_freturn() {
    if (method.isSynchronized()) genMonitorExit();
    if (SSE2_FULL) {
      asm.emitMOVSS_Reg_RegInd(XMM0, SP);
    } else {
      asm.emitFLD_Reg_RegInd(FP0, SP);
    }
    genEpilogue(WORDSIZE, 0);
  }

  /**
   * Emit code to implement the dreturn bytecode
   */
  @Override
  protected final void emit_dreturn() {
    if (method.isSynchronized()) genMonitorExit();
    if (SSE2_FULL) {
      asm.emitMOVLPD_Reg_RegInd(XMM0, SP);
    } else {
      asm.emitFLD_Reg_RegInd_Quad(FP0, SP);
    }
    genEpilogue(2*WORDSIZE, 0);
  }

  /**
   * Emit code to implement the areturn bytecode
   */
  @Override
  protected final void emit_areturn() {
    if (method.isSynchronized()) genMonitorExit();
    asm.emitPOP_Reg(T0);
    genEpilogue(WORDSIZE, WORDSIZE);
  }

  /**
   * Emit code to implement the return bytecode
   */
  @Override
  protected final void emit_return() {
    if (method.isSynchronized()) genMonitorExit();
    genEpilogue(0, 0);
  }

  /*
   * field access
   */

  /**
   * Emit code to implement a dynamically linked getstatic
   * @param fieldRef the referenced field
   */
  @Override
  protected final void emit_unresolved_getstatic(FieldReference fieldRef) {
    emitDynamicLinkingSequence(asm, T0, fieldRef, true);
    if (MemoryManagerConstants.NEEDS_GETSTATIC_READ_BARRIER && !fieldRef.getFieldContentsType().isPrimitiveType()) {
      Barriers.compileGetstaticBarrier(asm, T0, fieldRef.getId());
      return;
    }
    if (fieldRef.getSize() <= BYTES_IN_INT) {
      // get static field - [SP--] = [T0<<0+JTOC]
      if (VM.BuildFor32Addr) {
        asm.emitPUSH_RegOff(T0, Assembler.BYTE, Magic.getTocPointer().toWord().toOffset());
      } else {
        asm.emitMOV_Reg_RegOff(T0, T0, Assembler.BYTE, Magic.getTocPointer().toWord().toOffset());
        asm.emitPUSH_Reg(T0);
      }
    } else { // field is two words (double or long)
      if (VM.VerifyAssertions) VM._assert(fieldRef.getSize() == BYTES_IN_LONG);
      if (VM.BuildFor32Addr) {
        asm.emitPUSH_RegOff(T0, Assembler.BYTE, Magic.getTocPointer().toWord().toOffset().plus(WORDSIZE)); // get high part
        asm.emitPUSH_RegOff(T0, Assembler.BYTE, Magic.getTocPointer().toWord().toOffset());                // get low part
      } else {
        if (fieldRef.getNumberOfStackSlots() != 1) {
          adjustStack(-WORDSIZE, true);
        }
        asm.emitPUSH_RegOff(T0, Assembler.BYTE, Magic.getTocPointer().toWord().toOffset());
      }
    }
  }

  /**
   * Emit code to implement a getstatic
   * @param fieldRef the referenced field
   */
  @Override
  protected final void emit_resolved_getstatic(FieldReference fieldRef) {
    RVMField field = fieldRef.peekResolvedField();
    Offset fieldOffset = field.getOffset();
    if (MemoryManagerConstants.NEEDS_GETSTATIC_READ_BARRIER && !fieldRef.getFieldContentsType().isPrimitiveType() && !field.isUntraced()) {
      Barriers.compileGetstaticBarrierImm(asm, fieldOffset, fieldRef.getId());
      return;
    }
    if (fieldRef.getSize() <= BYTES_IN_INT) { // field is one word
      if (VM.BuildFor32Addr) {
        asm.emitPUSH_Abs(Magic.getTocPointer().plus(fieldOffset));
      } else {
        asm.emitMOV_Reg_Abs(T0, Magic.getTocPointer().plus(fieldOffset));
        asm.emitPUSH_Reg(T0);
      }
    } else { // field is two words (double or long)
      if (VM.VerifyAssertions) VM._assert(fieldRef.getSize() == BYTES_IN_LONG);
      if (VM.BuildFor32Addr) {
        asm.emitPUSH_Abs(Magic.getTocPointer().plus(fieldOffset).plus(WORDSIZE)); // get high part
        asm.emitPUSH_Abs(Magic.getTocPointer().plus(fieldOffset));                // get low part
      } else {
        if (fieldRef.getNumberOfStackSlots() != 1) {
          adjustStack(-WORDSIZE, true);
        }
        asm.emitPUSH_Abs(Magic.getTocPointer().plus(fieldOffset));
      }
    }
  }

  /**
   * Emit code to implement a dynamically linked putstatic
   * @param fieldRef the referenced field
   */
  @Override
  protected final void emit_unresolved_putstatic(FieldReference fieldRef) {
    emitDynamicLinkingSequence(asm, T0, fieldRef, true);
    if (MemoryManagerConstants.NEEDS_PUTSTATIC_WRITE_BARRIER && fieldRef.getFieldContentsType().isReferenceType()) {
      Barriers.compilePutstaticBarrier(asm, T0, fieldRef.getId());
      adjustStack(WORDSIZE, true);
    } else {
      if (fieldRef.getSize() <= BYTES_IN_INT) { // field is one word
        if (VM.BuildFor32Addr) {
          asm.emitPOP_RegOff(T0, Assembler.BYTE, Magic.getTocPointer().toWord().toOffset());
        } else {
          asm.emitPOP_Reg(T1);
          asm.emitMOV_RegOff_Reg(T0, Assembler.BYTE, Magic.getTocPointer().toWord().toOffset(), T1);
        }
      } else { // field is two words (double or long)
        if (VM.VerifyAssertions) VM._assert(fieldRef.getSize() == BYTES_IN_LONG);
        if (VM.BuildFor32Addr) {
          asm.emitPOP_RegOff(T0, Assembler.BYTE, Magic.getTocPointer().toWord().toOffset());                // store low part
          asm.emitPOP_RegOff(T0, Assembler.BYTE, Magic.getTocPointer().toWord().toOffset().plus(WORDSIZE)); // store high part
        } else {
          asm.emitPOP_RegOff(T0, Assembler.BYTE, Magic.getTocPointer().toWord().toOffset());
          if (fieldRef.getNumberOfStackSlots() != 1) {
            adjustStack(WORDSIZE, true);
          }
        }
      }
    }
  }

  /**
   * Emit code to implement a putstatic
   * @param fieldRef the referenced field
   */
  @Override
  protected final void emit_resolved_putstatic(FieldReference fieldRef) {
    RVMField field = fieldRef.peekResolvedField();
    Offset fieldOffset = field.getOffset();
    if (MemoryManagerConstants.NEEDS_PUTSTATIC_WRITE_BARRIER && field.isReferenceType() && !field.isUntraced()) {
      Barriers.compilePutstaticBarrierImm(asm, fieldOffset, fieldRef.getId());
      adjustStack(WORDSIZE, true);
    } else {
      if (field.getSize() <= BYTES_IN_INT) { // field is one word
        if (VM.BuildFor32Addr) {
          asm.emitPOP_Abs(Magic.getTocPointer().plus(fieldOffset));
        } else {
          asm.emitPOP_Reg(T1);
          asm.emitMOV_Abs_Reg(Magic.getTocPointer().plus(fieldOffset), T1);
        }
      } else { // field is two words (double or long)
        if (VM.VerifyAssertions) VM._assert(fieldRef.getSize() == BYTES_IN_LONG);
        if (VM.BuildFor32Addr) {
          asm.emitPOP_Abs(Magic.getTocPointer().plus(fieldOffset));          // store low part
          asm.emitPOP_Abs(Magic.getTocPointer().plus(fieldOffset).plus(WORDSIZE)); // store high part
        } else {
          asm.emitPOP_Abs(Magic.getTocPointer().plus(fieldOffset));
          if (fieldRef.getNumberOfStackSlots() != 1) {
            adjustStack(WORDSIZE, true);
          }
        }
      }
    }
  }

  /**
   * Emit code to implement a dynamically linked getfield
   * @param fieldRef the referenced field
   */
  @Override
  protected final void emit_unresolved_getfield(FieldReference fieldRef) {
    TypeReference fieldType = fieldRef.getFieldContentsType();
    emitDynamicLinkingSequence(asm, T0, fieldRef, true);
    if (fieldType.isReferenceType()) {
      // 32/64bit reference load
      if (MemoryManagerConstants.NEEDS_READ_BARRIER) {
        Barriers.compileGetfieldBarrier(asm, T0, fieldRef.getId());
      } else {
        asm.emitPOP_Reg(S0);                                  // S0 is object reference
        asm.emitPUSH_RegIdx(S0, T0, Assembler.BYTE, NO_SLOT); // place field value on stack
      }
    } else if (fieldType.isBooleanType()) {
      // 8bit unsigned load
      asm.emitPOP_Reg(S0);                                                // S0 is object reference
      asm.emitMOVZX_Reg_RegIdx_Byte(T1, S0, T0, Assembler.BYTE, NO_SLOT); // T1 is field value
      asm.emitPUSH_Reg(T1);                                               // place value on stack
    } else if (fieldType.isByteType()) {
      // 8bit signed load
      asm.emitPOP_Reg(S0);                                                // S0 is object reference
      asm.emitMOVSX_Reg_RegIdx_Byte(T1, S0, T0, Assembler.BYTE, NO_SLOT); // T1 is field value
      asm.emitPUSH_Reg(T1);                                               // place value on stack
    } else if (fieldType.isShortType()) {
      // 16bit signed load
      asm.emitPOP_Reg(S0);                                                // S0 is object reference
      asm.emitMOVSX_Reg_RegIdx_Word(T1, S0, T0, Assembler.BYTE, NO_SLOT); // T1 is field value
      asm.emitPUSH_Reg(T1);                                               // place value on stack
    } else if (fieldType.isCharType()) {
      // 16bit unsigned load
      asm.emitPOP_Reg(S0);                                                // S0 is object reference
      asm.emitMOVZX_Reg_RegIdx_Word(T1, S0, T0, Assembler.BYTE, NO_SLOT); // T1 is field value
      asm.emitPUSH_Reg(T1);                                               // place value on stack
    } else if (fieldType.isIntType() || fieldType.isFloatType() ||
        (VM.BuildFor32Addr && fieldType.isWordType())) {
      // 32bit load
      asm.emitPOP_Reg(S0);                                  // S0 is object reference
      asm.emitPUSH_RegIdx(S0, T0, Assembler.BYTE, NO_SLOT); // place field value on stack
    } else {
      // 64bit load
      if (VM.VerifyAssertions) {
        VM._assert(fieldType.isLongType() || fieldType.isDoubleType() ||
            (VM.BuildFor64Addr && fieldType.isWordType()));
      }
      asm.emitPOP_Reg(T1);           // T1 is object reference
      if (VM.BuildFor32Addr) {
        // NB this is a 64bit copy from memory to the stack so implement
        // as a slightly optimized Intel memory copy using the FPU
        adjustStack(-2*WORDSIZE, true); // adjust stack down to hold 64bit value
        if (SSE2_BASE) {
          asm.emitMOVQ_Reg_RegIdx(XMM0, T1, T0, Assembler.BYTE, NO_SLOT); // XMM0 is field value
          asm.emitMOVQ_RegInd_Reg(SP, XMM0); // place value on stack
        } else {
          asm.emitFLD_Reg_RegIdx_Quad(FP0, T1, T0, Assembler.BYTE, NO_SLOT); // FP0 is field value
          asm.emitFSTP_RegInd_Reg_Quad(SP, FP0); // place value on stack
        }
      } else {
        if (!fieldType.isWordType()) {
          adjustStack(-WORDSIZE, true); // add empty slot
        }
        asm.emitPUSH_RegIdx(T1, T0, Assembler.BYTE, NO_SLOT); // place value on stack
      }
    }
  }

  /**
   * Emit code to implement a getfield
   * @param fieldRef the referenced field
   */
  @Override
  protected final void emit_resolved_getfield(FieldReference fieldRef) {
    TypeReference fieldType = fieldRef.getFieldContentsType();
    RVMField field = fieldRef.peekResolvedField();
    Offset fieldOffset = field.getOffset();
    if (field.isReferenceType()) {
      // 32/64bit reference load
      if (MemoryManagerConstants.NEEDS_READ_BARRIER && !field.isUntraced()) {
        Barriers.compileGetfieldBarrierImm(asm, fieldOffset, fieldRef.getId());
      } else {
        asm.emitPOP_Reg(T0);                   // T0 is object reference
        asm.emitPUSH_RegDisp(T0, fieldOffset); // place field value on stack
      }
    } else if (fieldType.isBooleanType()) {
      // 8bit unsigned load
      asm.emitPOP_Reg(S0);                                 // S0 is object reference
      asm.emitMOVZX_Reg_RegDisp_Byte(T0, S0, fieldOffset); // T0 is field value
      asm.emitPUSH_Reg(T0);                                // place value on stack
    } else if (fieldType.isByteType()) {
      // 8bit signed load
      asm.emitPOP_Reg(S0);                                 // S0 is object reference
      asm.emitMOVSX_Reg_RegDisp_Byte(T0, S0, fieldOffset); // T0 is field value
      asm.emitPUSH_Reg(T0);                                // place value on stack
    } else if (fieldType.isShortType()) {
      // 16bit signed load
      asm.emitPOP_Reg(S0);                                 // S0 is object reference
      asm.emitMOVSX_Reg_RegDisp_Word(T0, S0, fieldOffset); // T0 is field value
      asm.emitPUSH_Reg(T0);                                // place value on stack
    } else if (fieldType.isCharType()) {
      // 16bit unsigned load
      asm.emitPOP_Reg(S0);                                 // S0 is object reference
      asm.emitMOVZX_Reg_RegDisp_Word(T0, S0, fieldOffset); // T0 is field value
      asm.emitPUSH_Reg(T0);                                // place value on stack
    } else if (fieldType.isIntType() || fieldType.isFloatType() ||
        (VM.BuildFor32Addr && fieldType.isWordType())) {
      // 32bit load
      asm.emitPOP_Reg(S0);                          // S0 is object reference
      asm.emitMOV_Reg_RegDisp(T0, S0, fieldOffset); // T0 is field value
      asm.emitPUSH_Reg(T0);                         // place value on stack
    } else {
      // 64bit load
      if (VM.VerifyAssertions) {
        VM._assert(fieldType.isLongType() || fieldType.isDoubleType() ||
            (VM.BuildFor64Addr && fieldType.isWordType()));
      }
      asm.emitPOP_Reg(T0); // T0 is object reference
      if (VM.BuildFor32Addr) {
        // NB this is a 64bit copy from memory to the stack so implement
        // as a slightly optimized Intel memory copy using the FPU
        adjustStack(-2*WORDSIZE, true); // adjust stack down to hold 64bit value
        if (SSE2_BASE) {
          asm.emitMOVQ_Reg_RegDisp(XMM0, T0, fieldOffset); // XMM0 is field value
          asm.emitMOVQ_RegInd_Reg(SP, XMM0); // replace reference with value on stack
        } else {
          asm.emitFLD_Reg_RegDisp_Quad(FP0, T0, fieldOffset); // FP0 is field value
          asm.emitFSTP_RegInd_Reg_Quad(SP, FP0); // replace reference with value on stack
        }
      } else {
        if (!fieldType.isWordType()) {
          adjustStack(-WORDSIZE, true); // add empty slot
        }
        asm.emitPUSH_RegDisp(T0, fieldOffset); // place value on stack
      }
    }
  }

  /**
   * Emit code to implement a dynamically linked putfield
   * @param fieldRef the referenced field
   */
  @Override
  protected final void emit_unresolved_putfield(FieldReference fieldRef) {
    TypeReference fieldType = fieldRef.getFieldContentsType();
    emitDynamicLinkingSequence(asm, T0, fieldRef, true);
    if (fieldType.isReferenceType()) {
      // 32/64bit reference store
      if (MemoryManagerConstants.NEEDS_WRITE_BARRIER) {
        Barriers.compilePutfieldBarrier(asm, T0, fieldRef.getId());
        adjustStack(2*WORDSIZE, true); // complete popping the value and reference
      } else {
        asm.emitPOP_Reg(T1);  // T1 is the value to be stored
        asm.emitPOP_Reg(S0);  // S0 is the object reference
        if (VM.BuildFor32Addr) {
          asm.emitMOV_RegIdx_Reg(S0, T0, Assembler.BYTE, NO_SLOT, T1); // [S0+T0] <- T1
        } else {
          asm.emitMOV_RegIdx_Reg_Quad(S0, T0, Assembler.BYTE, NO_SLOT, T1); // [S0+T0] <- T1
        }
      }
    } else if (fieldType.isBooleanType() || fieldType.isByteType()) {
      // 8bit store
      asm.emitPOP_Reg(T1);  // T1 is the value to be stored
      asm.emitPOP_Reg(S0);  // S0 is the object reference
      asm.emitMOV_RegIdx_Reg_Byte(S0, T0, Assembler.BYTE, NO_SLOT, T1); // [S0+T0] <- T1
    } else if (fieldType.isShortType() || fieldType.isCharType()) {
      // 16bit store
      asm.emitPOP_Reg(T1);  // T1 is the value to be stored
      asm.emitPOP_Reg(S0);  // S0 is the object reference
      asm.emitMOV_RegIdx_Reg_Word(S0, T0, Assembler.BYTE, NO_SLOT, T1); // [S0+T0] <- T1
    } else if (fieldType.isIntType() || fieldType.isFloatType() ||
        (VM.BuildFor32Addr && fieldType.isWordType())) {
      // 32bit store
      asm.emitPOP_Reg(T1);  // T1 is the value to be stored
      asm.emitPOP_Reg(S0);  // S0 is the object reference
      asm.emitMOV_RegIdx_Reg(S0, T0, Assembler.BYTE, NO_SLOT, T1); // [S0+T0] <- T1
    } else {
      // 64bit store
      if (VM.VerifyAssertions) {
        VM._assert(fieldType.isLongType() || fieldType.isDoubleType() ||
            (VM.BuildFor64Addr && fieldType.isWordType()));
      }
      if (VM.BuildFor32Addr) {
        // NB this is a 64bit copy from the stack to memory so implement
        // as a slightly optimized Intel memory copy using the FPU
        asm.emitMOV_Reg_RegDisp(S0, SP, TWO_SLOTS); // S0 is the object reference
        if (SSE2_BASE) {
          asm.emitMOVQ_Reg_RegInd(XMM0, SP); // XMM0 is the value to be stored
          asm.emitMOVQ_RegIdx_Reg(S0, T0, Assembler.BYTE, NO_SLOT, XMM0); // [S0+T0] <- XMM0
        } else {
          asm.emitFLD_Reg_RegInd_Quad(FP0, SP); // FP0 is the value to be stored
          asm.emitFSTP_RegIdx_Reg_Quad(S0, T0, Assembler.BYTE, NO_SLOT, FP0); // [S0+T0] <- FP0
        }
        if (!fieldType.isWordType()) {
          adjustStack(WORDSIZE*3, true); // complete popping the values and reference
        } else {
          adjustStack(WORDSIZE*2, true); // complete popping the values and reference
        }
      } else {
        asm.emitPOP_Reg(T1);  // T1 is the value to be stored
        if (!fieldType.isWordType()) {
          adjustStack(WORDSIZE, true); // throw away slot
        }
        asm.emitPOP_Reg(S0);  // T0 is the object reference
        asm.emitMOV_RegIdx_Reg_Quad(S0, T0, Assembler.BYTE, NO_SLOT, T1); // [S0+T0] <- T1
      }
    }
  }

  /**
   * Emit code to implement a putfield
   * @param fieldRef the referenced field
   */
  @Override
  protected final void emit_resolved_putfield(FieldReference fieldRef) {
    RVMField field = fieldRef.peekResolvedField();
    Offset fieldOffset = field.getOffset();
    Barriers.compileModifyCheck(asm, 4);
    if (field.isReferenceType()) {
      // 32/64bit reference store
      if (MemoryManagerConstants.NEEDS_WRITE_BARRIER && !field.isUntraced()) {
        Barriers.compilePutfieldBarrierImm(asm, fieldOffset, fieldRef.getId());
        adjustStack(WORDSIZE * 2, true); // complete popping the value and reference
      } else {
        asm.emitPOP_Reg(T0);  // T0 is the value to be stored
        asm.emitPOP_Reg(S0);  // S0 is the object reference
        // [S0+fieldOffset] <- T0
        if (VM.BuildFor32Addr) {
          asm.emitMOV_RegDisp_Reg(S0, fieldOffset, T0);
        } else {
          asm.emitMOV_RegDisp_Reg_Quad(S0, fieldOffset, T0);
        }
      }
    } else if (field.getSize() == BYTES_IN_BYTE) {
      // 8bit store
      asm.emitPOP_Reg(T0);  // T0 is the value to be stored
      asm.emitPOP_Reg(S0);  // S0 is the object reference
      // [S0+fieldOffset] <- T0
      asm.emitMOV_RegDisp_Reg_Byte(S0, fieldOffset, T0);
    } else if (field.getSize() == BYTES_IN_SHORT) {
      // 16bit store
      asm.emitPOP_Reg(T0);  // T0 is the value to be stored
      asm.emitPOP_Reg(S0);  // S0 is the object reference
      // [S0+fieldOffset] <- T0
      asm.emitMOV_RegDisp_Reg_Word(S0, fieldOffset, T0);
    } else if (field.getSize() == BYTES_IN_INT) {
      // 32bit store
      asm.emitPOP_Reg(T0);  // T0 is the value to be stored
      asm.emitPOP_Reg(S0);  // S0 is the object reference
      // [S0+fieldOffset] <- T0
      asm.emitMOV_RegDisp_Reg(S0, fieldOffset, T0);
    } else {
      // 64bit store
      if (VM.VerifyAssertions) {
        VM._assert(field.getSize() == BYTES_IN_LONG);
      }
      if (VM.BuildFor32Addr) {
        // NB this is a 64bit copy from the stack to memory so implement
        // as a slightly optimized Intel memory copy using the FPU
        asm.emitMOV_Reg_RegDisp(S0, SP, TWO_SLOTS); // S0 is the object reference
        if (SSE2_BASE) {
          asm.emitMOVQ_Reg_RegInd(XMM0, SP); // XMM0 is the value to be stored
          asm.emitMOVQ_RegDisp_Reg(S0, fieldOffset, XMM0); // [S0+fieldOffset] <- XMM0
        } else {
          asm.emitFLD_Reg_RegInd_Quad(FP0, SP); // FP0 is the value to be stored
          asm.emitFSTP_RegDisp_Reg_Quad(S0, fieldOffset, FP0);
        }
        adjustStack(WORDSIZE*3, true); // complete popping the values and reference
      } else {
        asm.emitPOP_Reg(T1);           // T1 is the value to be stored
        if (!field.getType().isWordType()) {
          adjustStack(WORDSIZE, true); // throw away slot
        }
        asm.emitPOP_Reg(S0);           // S0 is the object reference
        asm.emitMOV_RegDisp_Reg_Quad(S0, fieldOffset, T1); // [S0+fieldOffset] <- T1
      }
    }
  }

  /*
   * method invocation
   */

  /**
   * Emit code to implement a dynamically linked invokevirtual
   * @param methodRef the referenced method
   */
  @Override
  protected final void emit_unresolved_invokevirtual(MethodReference methodRef) {
    emitDynamicLinkingSequence(asm, T0, methodRef, true);            // T0 has offset of method
    int methodRefparameterWords = methodRef.getParameterWords() + 1; // +1 for "this" parameter
    Offset objectOffset =
      Offset.fromIntZeroExtend(methodRefparameterWords << LG_WORDSIZE).minus(WORDSIZE); // object offset into stack
    if (VM.BuildFor32Addr) {
      asm.emitMOV_Reg_RegDisp(T1, SP, objectOffset);                 // T1 has "this" parameter
    } else {
      asm.emitMOV_Reg_RegDisp_Quad(T1, SP, objectOffset);            // T1 has "this" parameter
    }
    baselineEmitLoadTIB(asm, S0, T1);                                // S0 has TIB
    asm.emitMOV_Reg_RegIdx(S0, S0, T0, Assembler.BYTE, NO_SLOT);     // S0 has address of virtual method
    genParameterRegisterLoad(methodRef, true);
    asm.emitCALL_Reg(S0);                                            // call virtual method
    genResultRegisterUnload(methodRef);                              // push return value, if any
  }

  /**
   * Emit code to implement invokevirtual
   * @param methodRef the referenced method
   */
  @Override
  protected final void emit_resolved_invokevirtual(MethodReference methodRef) {
    int methodRefparameterWords = methodRef.getParameterWords() + 1; // +1 for "this" parameter
    Offset methodRefOffset = methodRef.peekResolvedMethod().getOffset();
    Offset objectOffset =
        Offset.fromIntZeroExtend(methodRefparameterWords << LG_WORDSIZE).minus(WORDSIZE); // object offset into stack
    if (VM.BuildFor32Addr) {
      asm.emitMOV_Reg_RegDisp(T1, SP, objectOffset);                 // T1 has "this" parameter
    } else {
      asm.emitMOV_Reg_RegDisp_Quad(T1, SP, objectOffset);            // T1 has "this" parameter
    }
    baselineEmitLoadTIB(asm, S0, T1);                                // S0 has TIB
    genParameterRegisterLoad(methodRef, true);
    asm.emitCALL_RegDisp(S0, methodRefOffset);                       // call virtual method
    genResultRegisterUnload(methodRef);                              // push return value, if any
  }

  /**
   * Emit code to implement a dynamically linked invokespecial
   * @param methodRef The referenced method
   * @param target    The method to invoke
   */
  @Override
  protected final void emit_resolved_invokespecial(MethodReference methodRef, RVMMethod target) {
    if (target.isObjectInitializer()) {
      genParameterRegisterLoad(methodRef, true);
      asm.emitCALL_Abs(Magic.getTocPointer().plus(target.getOffset()));
      genResultRegisterUnload(target.getMemberRef().asMethodReference());
    } else {
      if (VM.VerifyAssertions) VM._assert(!target.isStatic());
      // invoke via class's tib slot
      Offset methodRefOffset = target.getOffset();
      if (VM.BuildFor32Addr) {
        asm.emitMOV_Reg_Abs(S0, Magic.getTocPointer().plus(target.getDeclaringClass().getTibOffset()));
      } else {
        asm.emitMOV_Reg_Abs_Quad(S0, Magic.getTocPointer().plus(target.getDeclaringClass().getTibOffset()));
      }
      genParameterRegisterLoad(methodRef, true);
      asm.emitCALL_RegDisp(S0, methodRefOffset);
      genResultRegisterUnload(methodRef);
    }
  }

  /**
   * Emit code to implement invokespecial
   * @param methodRef the referenced method
   */
  @Override
  protected final void emit_unresolved_invokespecial(MethodReference methodRef) {
    emitDynamicLinkingSequence(asm, S0, methodRef, true);
    genParameterRegisterLoad(methodRef, true);
    asm.emitCALL_RegDisp(S0, Magic.getTocPointer().toWord().toOffset());
    genResultRegisterUnload(methodRef);
  }

  /**
   * Emit code to implement a dynamically linked invokestatic
   * @param methodRef the referenced method
   */
  @Override
  protected final void emit_unresolved_invokestatic(MethodReference methodRef) {
    emitDynamicLinkingSequence(asm, S0, methodRef, true);
    genParameterRegisterLoad(methodRef, false);
    asm.emitCALL_RegDisp(S0, Magic.getTocPointer().toWord().toOffset());
    genResultRegisterUnload(methodRef);
  }

  /**
   * Emit code to implement invokestatic
   * @param methodRef the referenced method
   */
  @Override
  protected final void emit_resolved_invokestatic(MethodReference methodRef) {
    Offset methodOffset = methodRef.peekResolvedMethod().getOffset();
    genParameterRegisterLoad(methodRef, false);
    asm.emitCALL_Abs(Magic.getTocPointer().plus(methodOffset));
    genResultRegisterUnload(methodRef);
  }

  /**
   * Emit code to implement the invokeinterface bytecode
   * @param methodRef the referenced method
   */
  @Override
  protected final void emit_invokeinterface(MethodReference methodRef) {
    final int count = methodRef.getParameterWords() + 1; // +1 for "this" parameter

    RVMMethod resolvedMethod = null;
    resolvedMethod = methodRef.peekInterfaceMethod();

    // (1) Emit dynamic type checking sequence if required to do so inline.
    if (VM.BuildForIMTInterfaceInvocation) {
      if (methodRef.isMiranda()) {
        // TODO: It's not entirely clear that we can just assume that
        //       the class actually implements the interface.
        //       However, we don't know what interface we need to be checking
        //       so there doesn't appear to be much else we can do here.
      } else {
        if (resolvedMethod == null) {
          // Can't successfully resolve it at compile time.
          // Call uncommon case typechecking routine to do the right thing when this code actually executes.
          // T1 = "this" object
          if (VM.BuildFor32Addr) {
            if (count == 1) {
              asm.emitMOV_Reg_RegInd(T1, SP);
            } else {
              asm.emitMOV_Reg_RegDisp(T1, SP, Offset.fromIntZeroExtend((count - 1) << LG_WORDSIZE));
            }
          } else {
            if (count == 1) {
              asm.emitMOV_Reg_RegInd_Quad(T1, SP);
            } else {
              asm.emitMOV_Reg_RegDisp_Quad(T1, SP, Offset.fromIntZeroExtend((count - 1) << LG_WORDSIZE));
            }
          }
          asm.emitPUSH_Imm(methodRef.getId()); // push dict id of target
          asm.emitPUSH_Reg(T1);                // push "this"
          genParameterRegisterLoad(asm, 2);    // pass 2 parameter word
          // check that "this" class implements the interface
          asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.unresolvedInvokeinterfaceImplementsTestMethod.getOffset()));
        } else {
          RVMClass interfaceClass = resolvedMethod.getDeclaringClass();
          int interfaceIndex = interfaceClass.getDoesImplementIndex();
          int interfaceMask = interfaceClass.getDoesImplementBitMask();
          // T1 = "this" object
          if (VM.BuildFor32Addr) {
            if (count == 1) {
              asm.emitMOV_Reg_RegInd(T1, SP);
            } else {
              asm.emitMOV_Reg_RegDisp(T1, SP, Offset.fromIntZeroExtend((count - 1) << LG_WORDSIZE));
            }
          } else {
            if (count == 1) {
              asm.emitMOV_Reg_RegInd_Quad(T1, SP);
            } else {
              asm.emitMOV_Reg_RegDisp_Quad(T1, SP, Offset.fromIntZeroExtend((count - 1) << LG_WORDSIZE));
            }
          }
          baselineEmitLoadTIB(asm, S0, T1); // S0 = tib of "this" object
          if (VM.BuildFor32Addr) {
            asm.emitMOV_Reg_RegDisp(S0, S0, Offset.fromIntZeroExtend(TIB_DOES_IMPLEMENT_INDEX << LG_WORDSIZE));  // implements bit vector
          } else {
            asm.emitMOV_Reg_RegDisp_Quad(S0, S0, Offset.fromIntZeroExtend(TIB_DOES_IMPLEMENT_INDEX << LG_WORDSIZE));  // implements bit vector
          }

          if (DynamicTypeCheck.MIN_DOES_IMPLEMENT_SIZE <= interfaceIndex) {
            // must do arraybounds check of implements bit vector
            if (JavaHeaderConstants.ARRAY_LENGTH_BYTES == 4) {
              asm.emitCMP_RegDisp_Imm(S0, ObjectModel.getArrayLengthOffset(), interfaceIndex);
            } else {
              asm.emitCMP_RegDisp_Imm_Quad(S0, ObjectModel.getArrayLengthOffset(), interfaceIndex);
            }
            asm.emitBranchLikelyNextInstruction();
            ForwardReference fr = asm.forwardJcc(Assembler.LGT);
            asm.emitINT_Imm(RuntimeEntrypoints.TRAP_MUST_IMPLEMENT + RVM_TRAP_BASE);
            fr.resolve(asm);
          }

          // Test the appropriate bit and if set, branch around another trap imm
          if (interfaceIndex == 0) {
            asm.emitTEST_RegInd_Imm(S0, interfaceMask);
          } else {
            asm.emitTEST_RegDisp_Imm(S0, Offset.fromIntZeroExtend(interfaceIndex << LOG_BYTES_IN_INT), interfaceMask);
          }
          asm.emitBranchLikelyNextInstruction();
          ForwardReference fr = asm.forwardJcc(Assembler.NE);
          asm.emitINT_Imm(RuntimeEntrypoints.TRAP_MUST_IMPLEMENT + RVM_TRAP_BASE);
          fr.resolve(asm);
        }
      }
    }

    // (2) Emit interface invocation sequence.
    if (VM.BuildForIMTInterfaceInvocation) {
      InterfaceMethodSignature sig = InterfaceMethodSignature.findOrCreate(methodRef);

      // squirrel away signature ID
      ThreadLocalState.emitMoveImmToField(asm, ArchEntrypoints.hiddenSignatureIdField.getOffset(), sig.getId());
      // T1 = "this" object
      if (VM.BuildFor32Addr) {
        if (count == 1) {
          asm.emitMOV_Reg_RegInd(T1, SP);
        } else {
          asm.emitMOV_Reg_RegDisp(T1, SP, Offset.fromIntZeroExtend((count - 1) << LG_WORDSIZE));
        }
      } else {
        if (count == 1) {
          asm.emitMOV_Reg_RegInd_Quad(T1, SP);
        } else {
          asm.emitMOV_Reg_RegDisp_Quad(T1, SP, Offset.fromIntZeroExtend((count - 1) << LG_WORDSIZE));
        }
      }
      baselineEmitLoadTIB(asm, S0, T1);
      // Load the IMT Base into S0
      if (VM.BuildFor32Addr) {
        asm.emitMOV_Reg_RegDisp(S0, S0, Offset.fromIntZeroExtend(TIB_INTERFACE_DISPATCH_TABLE_INDEX << LG_WORDSIZE));
      } else {
        asm.emitMOV_Reg_RegDisp_Quad(S0, S0, Offset.fromIntZeroExtend(TIB_INTERFACE_DISPATCH_TABLE_INDEX << LG_WORDSIZE));
      }
      genParameterRegisterLoad(methodRef, true);
      asm.emitCALL_RegDisp(S0, sig.getIMTOffset()); // the interface call
    } else {
      int itableIndex = -1;
      if (VM.BuildForITableInterfaceInvocation && resolvedMethod != null) {
        // get the index of the method in the Itable
        itableIndex =
            InterfaceInvocation.getITableIndex(resolvedMethod.getDeclaringClass(),
                                                  methodRef.getName(),
                                                  methodRef.getDescriptor());
      }
      if (itableIndex == -1) {
        // itable index is not known at compile-time.
        // call "invokeInterface" to resolve object + method id into
        // method address
        int methodRefId = methodRef.getId();
        // "this" parameter is obj
        if (count == 1) {
          asm.emitPUSH_RegInd(SP);
        } else {
          asm.emitPUSH_RegDisp(SP, Offset.fromIntZeroExtend((count - 1) << LG_WORDSIZE));
        }
        asm.emitPUSH_Imm(methodRefId);             // id of method to call
        genParameterRegisterLoad(asm, 2);          // pass 2 parameter words
        // invokeinterface(obj, id) returns address to call
        asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.invokeInterfaceMethod.getOffset()));
        if (VM.BuildFor32Addr) {
          asm.emitMOV_Reg_Reg(S0, T0);             // S0 has address of method
        } else {
          asm.emitMOV_Reg_Reg_Quad(S0, T0);        // S0 has address of method
        }
        genParameterRegisterLoad(methodRef, true);
        asm.emitCALL_Reg(S0);                      // the interface method (its parameters are on stack)
      } else {
        // itable index is known at compile-time.
        // call "findITable" to resolve object + interface id into
        // itable address
        // T0 = "this" object
        if (VM.BuildFor32Addr) {
          if (count == 1) {
            asm.emitMOV_Reg_RegInd(T0, SP);
          } else {
            asm.emitMOV_Reg_RegDisp(T0, SP, Offset.fromIntZeroExtend((count - 1) << LG_WORDSIZE));
          }
        } else {
          if (count == 1) {
            asm.emitMOV_Reg_RegInd_Quad(T0, SP);
          } else {
            asm.emitMOV_Reg_RegDisp_Quad(T0, SP, Offset.fromIntZeroExtend((count - 1) << LG_WORDSIZE));
          }
        }
        baselineEmitLoadTIB(asm, S0, T0);
        asm.emitPUSH_Reg(S0);
        asm.emitPUSH_Imm(resolvedMethod.getDeclaringClass().getInterfaceId()); // interface id
        genParameterRegisterLoad(asm, 2);                                      // pass 2 parameter words
        asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.findItableMethod.getOffset())); // findItableOffset(tib, id) returns iTable
        if (VM.BuildFor32Addr) {
          asm.emitMOV_Reg_Reg(S0, T0);                                         // S0 has iTable
        } else {
          asm.emitMOV_Reg_Reg_Quad(S0, T0);                                    // S0 has iTable
        }
        genParameterRegisterLoad(methodRef, true);
        // the interface call
        asm.emitCALL_RegDisp(S0, Offset.fromIntZeroExtend(itableIndex << LG_WORDSIZE));
      }
    }
    genResultRegisterUnload(methodRef);
  }

  /*
   * other object model functions
   */

  /**
   * Emit code to allocate a scalar object
   * @param typeRef the RVMClass to instantiate
   */
  @Override
  protected final void emit_resolved_new(RVMClass typeRef) {
    int instanceSize = typeRef.getInstanceSize();
    Offset tibOffset = typeRef.getTibOffset();
    int whichAllocator = MemoryManager.pickAllocator(typeRef, method);
    int align = ObjectModel.getAlignment(typeRef, false);
    int offset = ObjectModel.getOffsetForAlignment(typeRef, false);
    int site = MemoryManager.getAllocationSite(true);
    asm.emitPUSH_Imm(instanceSize);
    asm.emitPUSH_Abs(Magic.getTocPointer().plus(tibOffset)); // put tib on stack
    asm.emitPUSH_Imm(typeRef.hasFinalizer() ? 1 : 0);        // does the class have a finalizer?
    asm.emitPUSH_Imm(whichAllocator);
    asm.emitPUSH_Imm(align);
    asm.emitPUSH_Imm(offset);
    asm.emitPUSH_Imm(site);
    genParameterRegisterLoad(asm, 7);                        // pass 7 parameter words
    asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.resolvedNewScalarMethod.getOffset()));
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to dynamically link and allocate a scalar object
   * @param typeRef typeReference to dynamically link & instantiate
   */
  @Override
  protected final void emit_unresolved_new(TypeReference typeRef) {
    int site = MemoryManager.getAllocationSite(true);
    asm.emitPUSH_Imm(typeRef.getId());
    asm.emitPUSH_Imm(site);            // site
    genParameterRegisterLoad(asm, 2);  // pass 2 parameter words
    asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.unresolvedNewScalarMethod.getOffset()));
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to allocate an array
   * @param array the RVMArray to instantiate
   */
  @Override
  protected final void emit_resolved_newarray(RVMArray array) {
    int width = array.getLogElementSize();
    Offset tibOffset = array.getTibOffset();
    int headerSize = ObjectModel.computeHeaderSize(array);
    int whichAllocator = MemoryManager.pickAllocator(array, method);
    int site = MemoryManager.getAllocationSite(true);
    int align = ObjectModel.getAlignment(array);
    int offset = ObjectModel.getOffsetForAlignment(array, false);
    // count is already on stack- nothing required
    asm.emitPUSH_Imm(width);                 // logElementSize
    asm.emitPUSH_Imm(headerSize);            // headerSize
    asm.emitPUSH_Abs(Magic.getTocPointer().plus(tibOffset));   // tib
    asm.emitPUSH_Imm(whichAllocator);        // allocator
    asm.emitPUSH_Imm(align);
    asm.emitPUSH_Imm(offset);
    asm.emitPUSH_Imm(site);
    genParameterRegisterLoad(asm, 8);        // pass 8 parameter words
    asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.resolvedNewArrayMethod.getOffset()));
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to dynamically link and allocate an array
   * @param tRef the type reference to dynamically link & instantiate
   */
  @Override
  protected final void emit_unresolved_newarray(TypeReference tRef) {
    int site = MemoryManager.getAllocationSite(true);
    // count is already on stack- nothing required
    asm.emitPUSH_Imm(tRef.getId());
    asm.emitPUSH_Imm(site);           // site
    genParameterRegisterLoad(asm, 3); // pass 3 parameter words
    asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.unresolvedNewArrayMethod.getOffset()));
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to allocate a multi-dimensional array
   * @param typeRef the type reference to instantiate
   * @param dimensions the number of dimensions
   */
  @Override
  protected final void emit_multianewarray(TypeReference typeRef, int dimensions) {
    // TODO: implement direct call to RuntimeEntrypoints.buildTwoDimensionalArray
    // Calculate the offset from FP on entry to newarray:
    //      1 word for each parameter, plus 1 for return address on
    //      stack and 1 for code technique in Linker
    final int PARAMETERS = 4;
    final int OFFSET_WORDS = PARAMETERS + 2;

    // setup parameters for newarrayarray routine
    asm.emitPUSH_Imm(method.getId());           // caller
    asm.emitPUSH_Imm(dimensions);               // dimension of arrays
    asm.emitPUSH_Imm(typeRef.getId());          // type of array elements
    asm.emitPUSH_Imm((dimensions + OFFSET_WORDS) << LG_WORDSIZE);  // offset to dimensions from FP on entry to newarray

    genParameterRegisterLoad(asm, PARAMETERS);
    asm.emitCALL_Abs(Magic.getTocPointer().plus(ArchEntrypoints.newArrayArrayMethod.getOffset()));
    adjustStack(dimensions * WORDSIZE, true);   // clear stack of dimensions
    asm.emitPUSH_Reg(T0);                       // push array ref on stack
  }

  /**
   * Emit code to implement the arraylength bytecode
   */
  @Override
  protected final void emit_arraylength() {
    asm.emitPOP_Reg(T0);                // T0 is array reference
    if (JavaHeaderConstants.ARRAY_LENGTH_BYTES == 4) {
      if (VM.BuildFor32Addr) {
        asm.emitPUSH_RegDisp(T0, ObjectModel.getArrayLengthOffset());
      } else {
        asm.emitMOV_Reg_RegDisp(T0, T0, ObjectModel.getArrayLengthOffset());
        asm.emitPUSH_Reg(T0);
      }
    } else {
      asm.emitPUSH_RegDisp(T0, ObjectModel.getArrayLengthOffset());
    }
  }

  /**
   * Emit code to implement the athrow bytecode
   */
  @Override
  protected final void emit_athrow() {
    genParameterRegisterLoad(asm, 1);          // pass 1 parameter word
    asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.athrowMethod.getOffset()));
  }

  /**
   * Emit code to implement the checkcast bytecode
   * @param typeRef the LHS type
   */
  @Override
  protected final void emit_checkcast(TypeReference typeRef) {
    asm.emitPUSH_RegInd(SP);                        // duplicate the object ref on the stack
    asm.emitPUSH_Imm(typeRef.getId());               // TypeReference id.
    genParameterRegisterLoad(asm, 2);                     // pass 2 parameter words
    asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.checkcastMethod.getOffset())); // checkcast(obj, type reference id);
  }

  /**
   * Emit code to implement the checkcast bytecode
   * @param type the LHS type
   */
  @Override
  protected final void emit_checkcast_resolvedInterface(RVMClass type) {
    int interfaceIndex = type.getDoesImplementIndex();
    int interfaceMask = type.getDoesImplementBitMask();

    if (VM.BuildFor32Addr) {
      asm.emitMOV_Reg_RegInd(S0, SP);      // load object from stack into S0
      asm.emitTEST_Reg_Reg(S0, S0);        // test for null
    } else {
      asm.emitMOV_Reg_RegInd_Quad(S0, SP); // load object from stack into S0
      asm.emitTEST_Reg_Reg_Quad(S0, S0);   // test for null
    }
    ForwardReference isNull = asm.forwardJcc(Assembler.EQ);

    baselineEmitLoadTIB(asm, S0, S0);      // S0 = TIB of object
    // S0 = implements bit vector
    if (VM.BuildFor32Addr) {
      asm.emitMOV_Reg_RegDisp(S0, S0, Offset.fromIntZeroExtend(TIB_DOES_IMPLEMENT_INDEX << LG_WORDSIZE));
    } else {
      asm.emitMOV_Reg_RegDisp_Quad(S0, S0, Offset.fromIntZeroExtend(TIB_DOES_IMPLEMENT_INDEX << LG_WORDSIZE));
    }

    if (DynamicTypeCheck.MIN_DOES_IMPLEMENT_SIZE <= interfaceIndex) {
      // must do arraybounds check of implements bit vector
      if (JavaHeaderConstants.ARRAY_LENGTH_BYTES == 4) {
        asm.emitCMP_RegDisp_Imm(S0, ObjectModel.getArrayLengthOffset(), interfaceIndex);
      } else {
        asm.emitCMP_RegDisp_Imm_Quad(S0, ObjectModel.getArrayLengthOffset(), interfaceIndex);
      }
      asm.emitBranchLikelyNextInstruction();
      ForwardReference fr = asm.forwardJcc(Assembler.LGT);
      asm.emitINT_Imm(RuntimeEntrypoints.TRAP_CHECKCAST + RVM_TRAP_BASE);
      fr.resolve(asm);
    }

    // Test the appropriate bit and if set, branch around another trap imm
    asm.emitTEST_RegDisp_Imm(S0, Offset.fromIntZeroExtend(interfaceIndex << LOG_BYTES_IN_INT), interfaceMask);
    asm.emitBranchLikelyNextInstruction();
    ForwardReference fr = asm.forwardJcc(Assembler.NE);
    asm.emitINT_Imm(RuntimeEntrypoints.TRAP_CHECKCAST + RVM_TRAP_BASE);
    fr.resolve(asm);
    isNull.resolve(asm);
  }

  /**
   * Emit code to implement the checkcast bytecode
   * @param type the LHS type
   */
  @Override
  protected final void emit_checkcast_resolvedClass(RVMClass type) {
    int LHSDepth = type.getTypeDepth();
    int LHSId = type.getId();

    if (VM.BuildFor32Addr) {
      asm.emitMOV_Reg_RegInd(S0, SP);      // load object from stack
      asm.emitTEST_Reg_Reg(S0, S0);        // test for null
    } else {
      asm.emitMOV_Reg_RegInd_Quad(S0, SP); // load object from stack
      asm.emitTEST_Reg_Reg_Quad(S0, S0);   // test for null
    }
    ForwardReference isNull = asm.forwardJcc(Assembler.EQ);

    baselineEmitLoadTIB(asm, S0, S0);      // S0 = TIB of object
    // S0 = superclass IDs
    if (VM.BuildFor32Addr) {
      asm.emitMOV_Reg_RegDisp(S0, S0, Offset.fromIntZeroExtend(TIB_SUPERCLASS_IDS_INDEX << LG_WORDSIZE));
    } else {
      asm.emitMOV_Reg_RegDisp_Quad(S0, S0, Offset.fromIntZeroExtend(TIB_SUPERCLASS_IDS_INDEX << LG_WORDSIZE));
    }
    if (DynamicTypeCheck.MIN_SUPERCLASS_IDS_SIZE <= LHSDepth) {
      // must do arraybounds check of superclass display
      if (JavaHeaderConstants.ARRAY_LENGTH_BYTES == 4) {
        asm.emitCMP_RegDisp_Imm(S0, ObjectModel.getArrayLengthOffset(), LHSDepth);
      } else {
        asm.emitCMP_RegDisp_Imm_Quad(S0, ObjectModel.getArrayLengthOffset(), LHSDepth);
      }
      asm.emitBranchLikelyNextInstruction();
      ForwardReference fr = asm.forwardJcc(Assembler.LGT);
      asm.emitINT_Imm(RuntimeEntrypoints.TRAP_CHECKCAST + RVM_TRAP_BASE);
      fr.resolve(asm);
    }

    // Load id from display at required depth and compare against target id.
    asm.emitMOVZX_Reg_RegDisp_Word(S0, S0, Offset.fromIntZeroExtend(LHSDepth << LOG_BYTES_IN_SHORT));
    asm.emitCMP_Reg_Imm(S0, LHSId);
    asm.emitBranchLikelyNextInstruction();
    ForwardReference fr = asm.forwardJcc(Assembler.EQ);
    asm.emitINT_Imm(RuntimeEntrypoints.TRAP_CHECKCAST + RVM_TRAP_BASE);
    fr.resolve(asm);
    isNull.resolve(asm);
  }

  /**
   * Emit code to implement the checkcast bytecode
   * @param type the LHS type
   */
  @Override
  protected final void emit_checkcast_final(RVMType type) {
    if (VM.BuildFor32Addr) {
      asm.emitMOV_Reg_RegInd(S0, SP);      // load object from stack
      asm.emitTEST_Reg_Reg(S0, S0);        // test for null
    } else {
      asm.emitMOV_Reg_RegInd_Quad(S0, SP); // load object from stack
      asm.emitTEST_Reg_Reg_Quad(S0, S0);   // test for null
    }
    ForwardReference isNull = asm.forwardJcc(Assembler.EQ);

    baselineEmitLoadTIB(asm, S0, S0);                           // TIB of object
    if (VM.BuildFor32Addr) {
      asm.emitCMP_Reg_Abs(S0, Magic.getTocPointer().plus(type.getTibOffset()));
    } else {
      asm.emitCMP_Reg_Abs_Quad(S0, Magic.getTocPointer().plus(type.getTibOffset()));
    }
    asm.emitBranchLikelyNextInstruction();
    ForwardReference fr = asm.forwardJcc(Assembler.EQ);
    asm.emitINT_Imm(RuntimeEntrypoints.TRAP_CHECKCAST + RVM_TRAP_BASE);
    fr.resolve(asm);
    isNull.resolve(asm);
  }

  /**
   * Emit code to implement the instanceof bytecode
   * @param typeRef the LHS type
   */
  @Override
  protected final void emit_instanceof(TypeReference typeRef) {
    asm.emitPUSH_Imm(typeRef.getId());
    genParameterRegisterLoad(asm, 2);          // pass 2 parameter words
    asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.instanceOfMethod.getOffset()));
    asm.emitPUSH_Reg(T0);
  }

  /**
   * Emit code to implement the instanceof bytecode
   * @param type the LHS type
   */
  @Override
  protected final void emit_instanceof_resolvedInterface(RVMClass type) {
    int interfaceIndex = type.getDoesImplementIndex();
    int interfaceMask = type.getDoesImplementBitMask();

    asm.emitPOP_Reg(S0);                 // load object from stack
    if (VM.BuildFor32Addr) {
      asm.emitTEST_Reg_Reg(S0, S0);      // test for null
    } else {
      asm.emitTEST_Reg_Reg_Quad(S0, S0); // test for null
    }
    ForwardReference isNull = asm.forwardJcc(Assembler.EQ);

    baselineEmitLoadTIB(asm, S0, S0);    // S0 = TIB of object
    // S0 = implements bit vector
    if (VM.BuildFor32Addr) {
      asm.emitMOV_Reg_RegDisp(S0, S0, Offset.fromIntZeroExtend(TIB_DOES_IMPLEMENT_INDEX << LG_WORDSIZE));
    } else {
      asm.emitMOV_Reg_RegDisp_Quad(S0, S0, Offset.fromIntZeroExtend(TIB_DOES_IMPLEMENT_INDEX << LG_WORDSIZE));
    }
    ForwardReference outOfBounds = null;
    if (DynamicTypeCheck.MIN_DOES_IMPLEMENT_SIZE <= interfaceIndex) {
      // must do arraybounds check of implements bit vector
      if (JavaHeaderConstants.ARRAY_LENGTH_BYTES == 4) {
        asm.emitCMP_RegDisp_Imm(S0, ObjectModel.getArrayLengthOffset(), interfaceIndex);
      } else {
        asm.emitCMP_RegDisp_Imm_Quad(S0, ObjectModel.getArrayLengthOffset(), interfaceIndex);
      }
      outOfBounds = asm.forwardJcc(Assembler.LLE);
    }

    // Test the implements bit and push true if it is set
    asm.emitTEST_RegDisp_Imm(S0, Offset.fromIntZeroExtend(interfaceIndex << LOG_BYTES_IN_INT), interfaceMask);
    ForwardReference notMatched = asm.forwardJcc(Assembler.EQ);
    asm.emitPUSH_Imm(1);
    ForwardReference done = asm.forwardJMP();

    // push false
    isNull.resolve(asm);
    if (outOfBounds != null) outOfBounds.resolve(asm);
    notMatched.resolve(asm);
    asm.emitPUSH_Imm(0);

    done.resolve(asm);
  }

  /**
   * Emit code to implement the instanceof bytecode
   * @param type the LHS type
   */
  @Override
  protected final void emit_instanceof_resolvedClass(RVMClass type) {
    int LHSDepth = type.getTypeDepth();
    int LHSId = type.getId();

    asm.emitPOP_Reg(S0);                 // load object from stack
    if (VM.BuildFor32Addr) {
      asm.emitTEST_Reg_Reg(S0, S0);      // test for null
    } else {
      asm.emitTEST_Reg_Reg_Quad(S0, S0); // test for null
    }
    ForwardReference isNull = asm.forwardJcc(Assembler.EQ);

    // get superclass display from object's TIB
    baselineEmitLoadTIB(asm, S0, S0);
    if (VM.BuildFor32Addr) {
      asm.emitMOV_Reg_RegDisp(S0, S0, Offset.fromIntZeroExtend(TIB_SUPERCLASS_IDS_INDEX << LG_WORDSIZE));
    } else {
      asm.emitMOV_Reg_RegDisp_Quad(S0, S0, Offset.fromIntZeroExtend(TIB_SUPERCLASS_IDS_INDEX << LG_WORDSIZE));
    }
    ForwardReference outOfBounds = null;
    if (DynamicTypeCheck.MIN_SUPERCLASS_IDS_SIZE <= LHSDepth) {
      // must do arraybounds check of superclass display
      if (JavaHeaderConstants.ARRAY_LENGTH_BYTES == 4) {
        asm.emitCMP_RegDisp_Imm(S0, ObjectModel.getArrayLengthOffset(), LHSDepth);
      } else {
        asm.emitCMP_RegDisp_Imm_Quad(S0, ObjectModel.getArrayLengthOffset(), LHSDepth);
      }
      outOfBounds = asm.forwardJcc(Assembler.LLE);
    }

    // Load id from display at required depth and compare against target id; push true if matched
    asm.emitMOVZX_Reg_RegDisp_Word(S0, S0, Offset.fromIntZeroExtend(LHSDepth << LOG_BYTES_IN_SHORT));
    asm.emitCMP_Reg_Imm(S0, LHSId);
    ForwardReference notMatched = asm.forwardJcc(Assembler.NE);
    asm.emitPUSH_Imm(1);
    ForwardReference done = asm.forwardJMP();

    // push false
    isNull.resolve(asm);
    if (outOfBounds != null) outOfBounds.resolve(asm);
    notMatched.resolve(asm);
    asm.emitPUSH_Imm(0);

    done.resolve(asm);
  }

  /**
   * Emit code to implement the instanceof bytecode
   * @param type the LHS type
   */
  @Override
  protected final void emit_instanceof_final(RVMType type) {
    asm.emitPOP_Reg(S0);                 // load object from stack
    if (VM.BuildFor32Addr) {
      asm.emitTEST_Reg_Reg(S0, S0);      // test for null
    } else {
      asm.emitTEST_Reg_Reg_Quad(S0, S0); // test for null
    }
    ForwardReference isNull = asm.forwardJcc(Assembler.EQ);

    // compare TIB of object to desired TIB and push true if equal
    baselineEmitLoadTIB(asm, S0, S0);
    if (VM.BuildFor32Addr) {
      asm.emitCMP_Reg_Abs(S0, Magic.getTocPointer().plus(type.getTibOffset()));
    } else {
      asm.emitCMP_Reg_Abs_Quad(S0, Magic.getTocPointer().plus(type.getTibOffset()));
    }
    ForwardReference notMatched = asm.forwardJcc(Assembler.NE);
    asm.emitPUSH_Imm(1);
    ForwardReference done = asm.forwardJMP();

    // push false
    isNull.resolve(asm);
    notMatched.resolve(asm);
    asm.emitPUSH_Imm(0);

    done.resolve(asm);
  }

  /**
   * Emit code to implement the monitorenter bytecode
   */
  @Override
  protected final void emit_monitorenter() {
    if (VM.BuildFor32Addr) {
      asm.emitMOV_Reg_RegInd(T0, SP);      // T0 is object reference
    } else {
      asm.emitMOV_Reg_RegInd_Quad(T0, SP); // T0 is object reference
    }
    genExplicitNullCheck(asm, T0);
    genParameterRegisterLoad(asm, 1);      // pass 1 parameter word
    asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.lockMethod.getOffset()));
  }

  /**
   * Emit code to implement the monitorexit bytecode
   */
  @Override
  protected final void emit_monitorexit() {
    genParameterRegisterLoad(asm, 1);          // pass 1 parameter word
    asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.unlockMethod.getOffset()));
  }

  //----------------//
  // implementation //
  //----------------//

  private void genPrologue() {
    if (shouldPrint) asm.comment("prologue for " + method);
    if (klass.hasBridgeFromNativeAnnotation()) {
      // replace the normal prologue with a special prolog
      JNICompiler.generateGlueCodeForJNIMethod(asm, method, compiledMethod.getId());
      // set some constants for the code generation of the rest of the method
      // firstLocalOffset is shifted down because more registers are saved
      firstLocalOffset = STACKFRAME_BODY_OFFSET - (JNICompiler.SAVED_GPRS_FOR_JNI << LG_WORDSIZE);
    } else {
      /* paramaters are on the stack and/or in registers;  There is space
       * on the stack for all the paramaters;  Parameter slots in the
       * stack are such that the first paramater has the higher address,
       * i.e., it pushed below all the other paramaters;  The return
       * address is the topmost entry on the stack.  The frame pointer
       * still addresses the previous frame.
       * The first word of the header, currently addressed by the stack
       * pointer, contains the return address.
       */

      /* establish a new frame:
       * push the caller's frame pointer in the stack, and
       * reset the frame pointer to the current stack top,
       * ie, the frame pointer addresses directly the word
       * that contains the previous frame pointer.
       * The second word of the header contains the frame
       * point of the caller.
       * The third word of the header contains the compiled method id of the called method.
       */
      asm.emitPUSH_RegDisp(TR, ArchEntrypoints.framePointerField.getOffset());        // store caller's frame pointer
      ThreadLocalState.emitMoveRegToField(asm,
					  ArchEntrypoints.framePointerField.getOffset(),
					  SP); // establish new frame
      /*
       * NOTE: until the end of the prologue SP holds the framepointer.
       */
      asm.emitMOV_RegDisp_Imm(SP,
                              Offset.fromIntSignExtend(STACKFRAME_METHOD_ID_OFFSET),
                              compiledMethod.getId()); // 3rd word of header

      /*
      * save registers
      */
      asm.emitMOV_RegDisp_Reg(SP, EDI_SAVE_OFFSET, EDI);          // save nonvolatile EDI register
      asm.emitMOV_RegDisp_Reg(SP, EBX_SAVE_OFFSET, EBX);            // save nonvolatile EBX register

      int savedRegistersSize;

      if (method.hasBaselineSaveLSRegistersAnnotation()) {
	asm.emitMOV_RegDisp_Reg(SP, EBP_SAVE_OFFSET, EBP);
	savedRegistersSize = SAVED_GPRS_FOR_SAVE_LS_REGISTERS << LG_WORDSIZE;
      } else {
	savedRegistersSize= SAVED_GPRS << LG_WORDSIZE;       // default
      }

      /* handle "dynamic brige" methods:
       * save all registers except FP, SP, TR, S0 (scratch), and
       * EDI and EBX saved above.
       */
      // TODO: (SJF): When I try to reclaim ESI, I may have to save it here?
      if (klass.hasDynamicBridgeAnnotation()) {
        savedRegistersSize += 2 << LG_WORDSIZE;
        asm.emitMOV_RegDisp_Reg(SP, T0_SAVE_OFFSET, T0);
        asm.emitMOV_RegDisp_Reg(SP, T1_SAVE_OFFSET, T1);
        if (SSE2_FULL) {
          // TODO: Store SSE2 Control word?
          asm.emitMOVQ_RegDisp_Reg(SP, XMM_SAVE_OFFSET.plus(0),  XMM0);
          asm.emitMOVQ_RegDisp_Reg(SP, XMM_SAVE_OFFSET.plus(8),  XMM1);
          asm.emitMOVQ_RegDisp_Reg(SP, XMM_SAVE_OFFSET.plus(16), XMM2);
          asm.emitMOVQ_RegDisp_Reg(SP, XMM_SAVE_OFFSET.plus(24), XMM3);
          savedRegistersSize += XMM_STATE_SIZE;
        } else {
          asm.emitFNSAVE_RegDisp(SP, FPU_SAVE_OFFSET);
          savedRegistersSize += FPU_STATE_SIZE;
        }
      }

      // copy registers to callee's stackframe
      firstLocalOffset = STACKFRAME_BODY_OFFSET - savedRegistersSize;
      Offset firstParameterOffset = Offset.fromIntSignExtend((parameterWords << LG_WORDSIZE) + WORDSIZE);
      genParameterCopy(firstParameterOffset, Offset.fromIntSignExtend(firstLocalOffset));

      int emptyStackOffset = firstLocalOffset - (method.getLocalWords() << LG_WORDSIZE) + WORDSIZE;
      asm.emitADD_Reg_Imm(SP, emptyStackOffset);               // set aside room for non parameter locals

      /* defer generating code which may cause GC until
       * locals were initialized. see emit_deferred_prologue
       */
      if (method.isForOsrSpecialization()) {
        return;
      }

      /*
       * generate stacklimit check
       */
      if (isInterruptible) {
        // S0<-limit
        ThreadLocalState.emitMoveFieldToReg(asm, S0, Entrypoints.stackLimitField.getOffset());

        asm.emitSUB_Reg_Reg(S0, SP);                                           // space left
        asm.emitADD_Reg_Imm(S0, method.getOperandWords() << LG_WORDSIZE);      // space left after this expression stack
        asm.emitBranchLikelyNextInstruction();
        ForwardReference fr = asm.forwardJcc(Assembler.LT);        // Jmp around trap if OK
        asm.emitINT_Imm(RuntimeEntrypoints.TRAP_STACK_OVERFLOW + RVM_TRAP_BASE);     // trap
        fr.resolve(asm);
      } else {
        // TODO!! make sure stackframe of uninterruptible method doesn't overflow guard page
      }

      if (!VM.runningTool && ((BaselineCompiledMethod) compiledMethod).hasCounterArray()) {
        // use (nonvolatile) EBX to hold base of this method's counter array
        if (MemoryManagerConstants.NEEDS_READ_BARRIER) {
          asm.emitPUSH_Abs(Magic.getTocPointer().plus(Entrypoints.edgeCountersField.getOffset()));
          asm.emitPUSH_Imm(getEdgeCounterIndex());
          Barriers.compileArrayLoadBarrier(asm, false);
          if (VM.BuildFor32Addr) {
            asm.emitMOV_Reg_Reg(EBX, T0);
          } else {
            asm.emitMOV_Reg_Reg_Quad(EBX, T0);
          }
        } else {
          asm.emitMOV_Reg_Abs(EBX, Magic.getTocPointer().plus(Entrypoints.edgeCountersField.getOffset()));
          asm.emitMOV_Reg_RegDisp(EBX, EBX, getEdgeCounterOffset());
        }
      }

      if (method.isSynchronized()) genMonitorEnter();

      genThreadSwitchTest(RVMThread.PROLOGUE);
    }
  }

  /**
   * Emit deferred prologue
   */
  @Override
  protected final void emit_deferred_prologue() {

    if (VM.VerifyAssertions) VM._assert(method.isForOsrSpecialization());

    if (isInterruptible) {
      // S0<-limit
      ThreadLocalState.emitMoveFieldToReg(asm, S0, Entrypoints.stackLimitField.getOffset());
      asm.emitSUB_Reg_Reg(S0, SP);                                       // spa
      asm.emitADD_Reg_Imm(S0, method.getOperandWords() << LG_WORDSIZE);  // spa
      asm.emitBranchLikelyNextInstruction();
      ForwardReference fr = asm.forwardJcc(Assembler.LT);    // Jmp around trap if
      asm.emitINT_Imm(RuntimeEntrypoints.TRAP_STACK_OVERFLOW + RVM_TRAP_BASE); // tra
      fr.resolve(asm);
    } else {
      // TODO!! make sure stackframe of uninterruptible method doesn't overflow
    }

    /* never do monitor enter for synced method since the specialized
     * code starts after original monitor enter.
     */

    genThreadSwitchTest(RVMThread.PROLOGUE);
  }

  private void genEpilogue(int bytesPopped) {
    if (klass.hasBridgeFromNativeAnnotation()) {
      // pop locals and parameters, get to saved GPR's
      asm.emitADD_Reg_Imm(SP, (this.method.getLocalWords() << LG_WORDSIZE));
      JNICompiler.generateEpilogForJNIMethod(asm, this.method);
    } else if (klass.hasDynamicBridgeAnnotation()) {
      // we never return from a DynamicBridge frame
      asm.emitINT_Imm(0xFF);
    } else {
      // normal method
      asm.emitADD_Reg_Imm(SP, fp2spOffset(NO_SLOT).toInt() - bytesPopped);     // SP becomes frame pointer
      asm.emitMOV_Reg_RegDisp(EDI, SP, EDI_SAVE_OFFSET);           // restore nonvolatile EDI register
      asm.emitMOV_Reg_RegDisp(EBX, SP, EBX_SAVE_OFFSET);             // restore nonvolatile EBX register
      if (method.hasBaselineSaveLSRegistersAnnotation()) {
	asm.emitMOV_Reg_RegDisp(EBP, SP, EBP_SAVE_OFFSET);             // restore nonvolatile EBP register
      }
      asm.emitPOP_RegDisp(TR, ArchEntrypoints.framePointerField.getOffset()); // discard frame
      asm.emitRET_Imm(parameterWords << LG_WORDSIZE);    // return to caller- pop parameters from stack
    }
  }

  private void genMonitorEnter() {
    if (method.isStatic()) {
      Offset klassOffset = Offset.fromIntSignExtend(Statics.findOrCreateObjectLiteral(klass.getClassForType()));
      // push java.lang.Class object for klass
      asm.emitPUSH_Abs(Magic.getTocPointer().plus(klassOffset));
    } else {
      // push "this" object
      asm.emitPUSH_RegDisp(ESP, localOffset(0));
    }
    // pass 1 parameter
    genParameterRegisterLoad(asm, 1);
    asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.lockMethod.getOffset()));
    // after this instruction, the method has the monitor
    lockOffset = asm.getMachineCodeIndex();
  }

  /**
   * Generate instructions to release lock on exit from a method
   */
  private void genMonitorExit() {
    if (method.isStatic()) {
      Offset klassOffset = Offset.fromIntSignExtend(Statics.findOrCreateObjectLiteral(klass.getClassForType()));
      // push java.lang.Class object for klass
      asm.emitPUSH_Abs(Magic.getTocPointer().plus(klassOffset));
    } else {
      asm.emitPUSH_RegDisp(ESP, localOffset(0));                    // push "this" object
    }
    genParameterRegisterLoad(asm, 1); // pass 1 parameter
    asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.unlockMethod.getOffset()));
  }

  /**
   * Generate an explicit null check, trapping if the register is null
   * otherwise falling through.
   * @param asm the assembler to generate into
   * @param objRefReg the register containing the array reference
   */
  @Inline
  private void genExplicitNullCheck(Assembler asm, GPR objRefReg) {
    // compare to zero
    asm.emitTEST_Reg_Reg(objRefReg, objRefReg);
    // Jmp around trap if index is OK
    asm.emitBranchLikelyNextInstruction();
    ForwardReference fr = asm.forwardJcc(Assembler.NE);
    // trap
    asm.emitINT_Imm(RuntimeEntrypoints.TRAP_NULL_POINTER + RVM_TRAP_BASE);
    fr.resolve(asm);
  }


  /**
   * Generate an array bounds check trapping if the array bound check fails,
   * otherwise falling through.
   * @param asm the assembler to generate into
   * @param indexReg the register containing the index
   * @param arrayRefReg the register containing the array reference
   */
  @Inline(value=Inline.When.AllArgumentsAreConstant)
  private void genBoundsCheck(Assembler asm, GPR indexReg, GPR arrayRefReg) {
    if (generateBoundsChecks) {
      // compare index to array length
      asm.emitCMP_RegDisp_Reg(arrayRefReg,
          ObjectModel.getArrayLengthOffset(),
          indexReg);
      // Jmp around trap if index is OK
      asm.emitBranchLikelyNextInstruction();
      ForwardReference fr = asm.forwardJcc(Assembler.LGT);
      // "pass" index param to C trap handler
      ThreadLocalState.emitMoveRegToField(asm,
          ArchEntrypoints.arrayIndexTrapParamField.getOffset(), indexReg);
      // trap
      asm.emitINT_Imm(RuntimeEntrypoints.TRAP_ARRAY_BOUNDS + RVM_TRAP_BASE);
      fr.resolve(asm);
    }
  }

  /**
   * Emit a conditional branch on the given condition and bytecode target.
   * The caller has just emitted the instruction sequence to set the condition codes.
   */
  private void genCondBranch(byte cond, int bTarget) {
    int mTarget = bytecodeMap[bTarget];
    if (!VM.runningTool && ((BaselineCompiledMethod) compiledMethod).hasCounterArray()) {
      // Allocate two counters: taken and not taken
      int entry = edgeCounterIdx;
      edgeCounterIdx += 2;

      // Flip conditions so we can jump over the increment of the taken counter.
      ForwardReference notTaken = asm.forwardJcc(asm.flipCode(cond));

      // Increment taken counter & jump to target
      incEdgeCounter(T1, null, entry + EdgeCounts.TAKEN);
      asm.emitJMP_ImmOrLabel(mTarget, bTarget);

      // Increment not taken counter
      notTaken.resolve(asm);
      incEdgeCounter(T1, null, entry + EdgeCounts.NOT_TAKEN);
    } else {
      asm.emitJCC_Cond_ImmOrLabel(cond, mTarget, bTarget);
    }
  }

  /**
   * Generate code to increment edge counter
   * @param scratch register to use as scratch
   * @param idx optional register holding index value or null
   * @param counterIdx index in to counters array
   */
  @Inline(value=Inline.When.ArgumentsAreConstant, arguments={1,2})
  private void incEdgeCounter(GPR scratch, GPR idx, int counterIdx) {
    if (VM.VerifyAssertions) VM._assert(((BaselineCompiledMethod) compiledMethod).hasCounterArray());
    if (idx == null) {
      asm.emitMOV_Reg_RegDisp(scratch, EBX, Offset.fromIntZeroExtend(counterIdx << LOG_BYTES_IN_INT));
    } else {
      asm.emitMOV_Reg_RegIdx(scratch, EBX, idx, Assembler.WORD, Offset.fromIntZeroExtend(counterIdx << LOG_BYTES_IN_INT));
    }
    asm.emitADD_Reg_Imm(scratch, 1);
    // Don't write back result if it would make the counter negative (ie
    // saturate at 0x7FFFFFFF)
    ForwardReference fr1 = asm.forwardJcc(Assembler.S);
    if (idx == null) {
      asm.emitMOV_RegDisp_Reg(EBX, Offset.fromIntSignExtend(counterIdx << LOG_BYTES_IN_INT), scratch);
    } else {
      asm.emitMOV_RegIdx_Reg(EBX, idx, Assembler.WORD, Offset.fromIntSignExtend(counterIdx << LOG_BYTES_IN_INT), scratch);
    }
    fr1.resolve(asm);
  }

  /**
   * Copy parameters from operand stack into registers.
   * Assumption: parameters are layed out on the stack in order
   * with SP pointing to the last parameter.
   * Also, this method is called before the generation of a "helper" method call.
   * Assumption: no floating-point parameters.
   * @param asm assembler to use for generation
   * @param params number of parameter words (including "this" if any).
   */
  static void genParameterRegisterLoad(Assembler asm, int params) {
    if (VM.VerifyAssertions) VM._assert(0 < params);
    if (0 < NUM_PARAMETER_GPRS) {
      if (params != 1) {
        if (VM.BuildFor32Addr) {
          asm.emitMOV_Reg_RegDisp(T0, SP, Offset.fromIntZeroExtend((params - 1) << LG_WORDSIZE));
        } else {
          asm.emitMOV_Reg_RegDisp_Quad(T0, SP, Offset.fromIntZeroExtend((params - 1) << LG_WORDSIZE));
        }
      } else {
        if (VM.BuildFor32Addr) {
          asm.emitMOV_Reg_RegInd(T0, SP);
        } else {
          asm.emitMOV_Reg_RegInd_Quad(T0, SP);
        }
      }
    }
    if (1 < params && 1 < NUM_PARAMETER_GPRS) {
      if (params != 2) {
        if (VM.BuildFor32Addr) {
          asm.emitMOV_Reg_RegDisp(T1, SP, Offset.fromIntZeroExtend((params - 2) << LG_WORDSIZE));
        } else {
          asm.emitMOV_Reg_RegDisp_Quad(T1, SP, Offset.fromIntZeroExtend((params - 2) << LG_WORDSIZE));
        }
      } else {
        if (VM.BuildFor32Addr) {
          asm.emitMOV_Reg_RegInd(T1, SP);
        } else {
          asm.emitMOV_Reg_RegInd_Quad(T1, SP);
        }
      }
    }
  }

  /**
   * Copy parameters from operand stack into registers.
   * Assumption: parameters are layed out on the stack in order
   * with SP pointing to the last parameter.
   * Also, this method is called before the generation of an explicit method call.
   * @param method is the method to be called.
   * @param hasThisParam is the method virtual?
   */
  private void genParameterRegisterLoad(MethodReference method, boolean hasThisParam) {
    int max = NUM_PARAMETER_GPRS + NUM_PARAMETER_FPRS;
    if (max == 0) return; // quit looking when all registers are full
    int gpr = 0;  // number of general purpose registers filled
    int fpr = 0;  // number of floating point  registers filled
    GPR T = T0; // next GPR to get a parameter
    int params = method.getParameterWords() + (hasThisParam ? 1 : 0);
    Offset offset = Offset.fromIntSignExtend((params - 1) << LG_WORDSIZE); // stack offset of first parameter word
    if (hasThisParam) {
      if (gpr < NUM_PARAMETER_GPRS) {
        if (WORDSIZE == 4) {
          asm.emitMOV_Reg_RegDisp(T, SP, offset);
        } else {
          asm.emitMOV_Reg_RegDisp_Quad(T, SP, offset);
        }
        T = T1; // at most 2 parameters can be passed in general purpose registers
        gpr++;
        max--;
      }
      offset = offset.minus(WORDSIZE);
    }
    for (TypeReference type : method.getParameterTypes()) {
      if (max == 0) return; // quit looking when all registers are full
      TypeReference t = type;
      if (t.isLongType()) {
        if (gpr < NUM_PARAMETER_GPRS) {
          if (WORDSIZE == 4) {
            asm.emitMOV_Reg_RegDisp(T, SP, offset); // lo register := hi mem (== hi order word)
            T = T1; // at most 2 parameters can be passed in general purpose registers
            gpr++;
            max--;
            if (gpr < NUM_PARAMETER_GPRS) {
              asm.emitMOV_Reg_RegDisp(T, SP, offset.minus(WORDSIZE));  // hi register := lo mem (== lo order word)
              gpr++;
              max--;
            }
          } else {
            asm.emitMOV_Reg_RegDisp_Quad(T, SP, offset.minus(WORDSIZE));
            T = T1; // at most 2 parameters can be passed in general purpose registers
            gpr++;
            max--;
          }
        }
        offset = offset.minus(2 * WORDSIZE);
      } else if (t.isFloatType()) {
        if (fpr < NUM_PARAMETER_FPRS) {
          if (SSE2_FULL) {
            asm.emitMOVSS_Reg_RegDisp(XMM.lookup(fpr), SP, offset);
          } else {
            asm.emitFLD_Reg_RegDisp(FP0, SP, offset);
          }
          fpr++;
          max--;
        }
        offset = offset.minus(WORDSIZE);
      } else if (t.isDoubleType()) {
        if (fpr < NUM_PARAMETER_FPRS) {
          if (SSE2_FULL) {
            asm.emitMOVLPD_Reg_RegDisp(XMM.lookup(fpr), SP, offset.minus(WORDSIZE));
          } else {
            asm.emitFLD_Reg_RegDisp_Quad(FP0, SP, offset.minus(WORDSIZE));
          }
          fpr++;
          max--;
        }
        offset = offset.minus(2 * WORDSIZE);
      } else if (t.isReferenceType() || t.isWordType()) {
        if (gpr < NUM_PARAMETER_GPRS) {
          if (WORDSIZE == 4) {
            asm.emitMOV_Reg_RegDisp(T, SP, offset); // lo register := hi mem (== hi order word)
          } else {
            asm.emitMOV_Reg_RegDisp_Quad(T, SP, offset);
          }
          T = T1; // at most 2 parameters can be passed in general purpose registers
          gpr++;
          max--;
        }
        offset = offset.minus(WORDSIZE);
      } else { // t is object, int, short, char, byte, or boolean
        if (gpr < NUM_PARAMETER_GPRS) {
          asm.emitMOV_Reg_RegDisp(T, SP, offset);
          T = T1; // at most 2 parameters can be passed in general purpose registers
          gpr++;
          max--;
        }
        offset = offset.minus(WORDSIZE);
      }
    }
    if (VM.VerifyAssertions) VM._assert(offset.EQ(Offset.fromIntSignExtend(-WORDSIZE)));
  }

  /**
   * Store parameters into local space of the callee's stackframe.
   * Taken: srcOffset - offset from frame pointer of first parameter in caller's stackframe.
   * Assumption: although some parameters may be passed in registers,
   * space for all parameters is layed out in order on the caller's stackframe.
   */
  private void genParameterCopy(Offset srcOffset) {
    int gpr = 0;  // number of general purpose registers unloaded
    int fpr = 0;  // number of floating point registers unloaded
    GPR T = T0; // next GPR to get a parameter
    int dstOffset = 0; // offset from the bottom of the locals for the current parameter
    if (!method.isStatic()) { // handle "this" parameter
      if (gpr < NUM_PARAMETER_GPRS) {
        asm.emitPUSH_Reg(T);
        T = T1; // at most 2 parameters can be passed in general purpose registers
        gpr++;
      } else { // no parameters passed in registers
        asm.emitPUSH_RegDisp(SP, srcOffset);
      }
      dstOffset -= WORDSIZE;
    }
    int[] fprOffset = new int[NUM_PARAMETER_FPRS]; // to handle floating point parameters in registers
    boolean[] is32bit = new boolean[NUM_PARAMETER_FPRS]; // to handle floating point parameters in registers
    int spIsOffBy = 0; // in the case of doubles and floats SP may drift from the expected value as we don't use push/pop
    for (TypeReference t : method.getParameterTypes()) {
      if (t.isLongType()) {
        if (spIsOffBy != 0) {
          // fix up SP if it drifted
          adjustStack(-spIsOffBy, true);
          spIsOffBy = 0;
        }
        if (gpr < NUM_PARAMETER_GPRS) {
          if (VM.BuildFor32Addr) {
            asm.emitPUSH_Reg(T);                          // hi mem := lo register (== hi order word)
            T = T1;                                       // at most 2 parameters can be passed in general purpose registers
            gpr++;
            if (gpr < NUM_PARAMETER_GPRS) {
              asm.emitPUSH_Reg(T);  // lo mem := hi register (== lo order word)
              gpr++;
            } else {
              asm.emitPUSH_RegDisp(SP, srcOffset); // lo mem from caller's stackframe
            }
          } else {
            adjustStack(-WORDSIZE, true);                 // create empty slot
            asm.emitPUSH_Reg(T);                          // push long
            T = T1;                                       // at most 2 parameters can be passed in general purpose registers
            gpr++;
          }
        } else {
          if (VM.BuildFor32Addr) {
            asm.emitPUSH_RegDisp(SP, srcOffset);   // hi mem from caller's stackframe
            asm.emitPUSH_RegDisp(SP, srcOffset);   // lo mem from caller's stackframe
          } else {
            adjustStack(-WORDSIZE, true);          // create empty slot
            asm.emitPUSH_RegDisp(SP, srcOffset);   // push long
          }
        }
        dstOffset -= 2*WORDSIZE;
      } else if (t.isFloatType()) {
        if (fpr < NUM_PARAMETER_FPRS) {
          spIsOffBy += WORDSIZE;
          fprOffset[fpr] = dstOffset;
          is32bit[fpr] = true;
          fpr++;
        } else {
          if (spIsOffBy != 0) {
            // fix up SP if it drifted
            adjustStack(-spIsOffBy, true);
            spIsOffBy = 0;
          }
          asm.emitPUSH_RegDisp(SP, srcOffset);
        }
        dstOffset -= WORDSIZE;
      } else if (t.isDoubleType()) {
        if (fpr < NUM_PARAMETER_FPRS) {
          spIsOffBy += 2*WORDSIZE;
          dstOffset -= WORDSIZE;
          fprOffset[fpr] = dstOffset;
          dstOffset -= WORDSIZE;
          is32bit[fpr] = false;
          fpr++;
        } else {
          if (spIsOffBy != 0) {
            // fix up SP if it drifted
            adjustStack(-spIsOffBy, true);
            spIsOffBy = 0;
          }
          if (VM.BuildFor32Addr) {
            asm.emitPUSH_RegDisp(SP, srcOffset);   // hi mem from caller's stackframe
            asm.emitPUSH_RegDisp(SP, srcOffset);   // lo mem from caller's stackframe
          } else {
            adjustStack(-WORDSIZE, true);          // create empty slot
            asm.emitPUSH_RegDisp(SP, srcOffset);   // push double
          }
          dstOffset -= 2*WORDSIZE;
        }
      } else { // t is object, int, short, char, byte, or boolean
        if (spIsOffBy != 0) {
          // fix up SP if it drifted
          adjustStack(-spIsOffBy, true);
          spIsOffBy = 0;
        }
        if (gpr < NUM_PARAMETER_GPRS) {
          asm.emitPUSH_Reg(T);
          T = T1; // at most 2 parameters can be passed in general purpose registers
          gpr++;
        } else {
          asm.emitPUSH_RegDisp(SP, srcOffset);
        }
        dstOffset -= WORDSIZE;
      }
    }
    if (spIsOffBy != 0) {
      // fix up SP if it drifted
      adjustStack(-spIsOffBy, true);
    }
    for (int i = fpr - 1; 0 <= i; i--) { // unload the floating point register stack (backwards)
      if (is32bit[i]) {
        if (SSE2_BASE) {
          asm.emitMOVSS_RegDisp_Reg(SP, Offset.fromIntSignExtend(fprOffset[i]-dstOffset-WORDSIZE), XMM.lookup(i));
        } else {
          asm.emitFSTP_RegDisp_Reg(SP, Offset.fromIntSignExtend(fprOffset[i]-dstOffset-WORDSIZE), FP0);
        }
      } else {
        if (SSE2_BASE) {
          asm.emitMOVSD_RegDisp_Reg(SP, Offset.fromIntSignExtend(fprOffset[i]-dstOffset-WORDSIZE), XMM.lookup(i));
        } else {
          asm.emitFSTP_RegDisp_Reg_Quad(SP, Offset.fromIntSignExtend(fprOffset[i]-dstOffset-WORDSIZE), FP0);
        }
      }
    }
  }

  /**
   * Push return value of method from register to operand stack.
   */
  private void genResultRegisterUnload(MethodReference m) {
    TypeReference t = m.getReturnType();

    if (t.isVoidType()) {
      // nothing to do
    } else if (t.isLongType()) {
      if (VM.BuildFor32Addr) {
        asm.emitPUSH_Reg(T0); // high half
        asm.emitPUSH_Reg(T1); // low half
      } else {
        adjustStack(-WORDSIZE, true);
        asm.emitPUSH_Reg(T0); // long value
      }
    } else if (t.isFloatType()) {
      adjustStack(-WORDSIZE, true);
      if (SSE2_FULL) {
        asm.emitMOVSS_RegInd_Reg(SP, XMM0);
      } else {
        asm.emitFSTP_RegInd_Reg(SP, FP0);
      }
    } else if (t.isDoubleType()) {
      adjustStack(-2*WORDSIZE, true);
      if (SSE2_FULL) {
        asm.emitMOVLPD_RegInd_Reg(SP, XMM0);
      } else {
        asm.emitFSTP_RegInd_Reg_Quad(SP, FP0);
      }
    } else { // t is object, int, short, char, byte, or boolean
      asm.emitPUSH_Reg(T0);
    }
  }

  /**
   * @param whereFrom is this thread switch from a PROLOGUE, BACKEDGE, or EPILOGUE?
   */
  private void genThreadSwitchTest(int whereFrom) {
    if (!isInterruptible) {
      return;
    }

    // thread switch requested ??
    ThreadLocalState.emitCompareFieldWithImm(asm, Entrypoints.takeYieldpointField.getOffset(), 0);
    ForwardReference fr1;
    if (whereFrom == RVMThread.PROLOGUE) {
      // Take yieldpoint if yieldpoint flag is non-zero (either 1 or -1)
      fr1 = asm.forwardJcc(Assembler.EQ);
      asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.yieldpointFromPrologueMethod.getOffset()));
    } else if (whereFrom == RVMThread.BACKEDGE) {
      // Take yieldpoint if yieldpoint flag is >0
      fr1 = asm.forwardJcc(Assembler.LE);
      asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.yieldpointFromBackedgeMethod.getOffset()));
    } else { // EPILOGUE
      // Take yieldpoint if yieldpoint flag is non-zero (either 1 or -1)
      fr1 = asm.forwardJcc(Assembler.EQ);
      asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.yieldpointFromEpilogueMethod.getOffset()));
    }
    fr1.resolve(asm);

    if (VM.BuildForAdaptiveSystem && options.INVOCATION_COUNTERS) {
      int id = compiledMethod.getId();
      InvocationCounts.allocateCounter(id);
      asm.emitMOV_Reg_Abs(ECX, Magic.getTocPointer().plus(AosEntrypoints.invocationCountsField.getOffset()));
      asm.emitSUB_RegDisp_Imm(ECX, Offset.fromIntZeroExtend(compiledMethod.getId() << 2), 1);
      ForwardReference notTaken = asm.forwardJcc(Assembler.GT);
      asm.emitPUSH_Imm(id);
      genParameterRegisterLoad(asm, 1);
      asm.emitCALL_Abs(Magic.getTocPointer().plus(AosEntrypoints.invocationCounterTrippedMethod.getOffset()));
      notTaken.resolve(asm);
    }
  }

  /**
   * Indicate if specified Magic method causes a frame to be created on the runtime stack.
   * @param methodToBeCalled RVMMethod of the magic method being called
   * @return true if method causes a stackframe to be created
   */
  public static boolean checkForActualCall(MethodReference methodToBeCalled) {
    Atom methodName = methodToBeCalled.getName();
    return methodName == MagicNames.invokeClassInitializer ||
           methodName == MagicNames.invokeMethodReturningVoid ||
           methodName == MagicNames.invokeMethodReturningInt ||
           methodName == MagicNames.invokeMethodReturningLong ||
           methodName == MagicNames.invokeMethodReturningFloat ||
           methodName == MagicNames.invokeMethodReturningDouble ||
           methodName == MagicNames.invokeMethodReturningObject ||
           methodName == MagicNames.addressArrayCreate;
  }

  private boolean genMagic(MethodReference m) {
    Atom methodName = m.getName();

    if (m.getType() == TypeReference.Address) {
      // Address magic

      TypeReference[] types = m.getParameterTypes();

      // Loads all take the form:
      // ..., Address, [Offset] -> ..., Value

      if (methodName == MagicNames.loadAddress ||
          methodName == MagicNames.prepareAddress ||
          methodName == MagicNames.prepareObjectReference ||
          methodName == MagicNames.loadWord ||
          methodName == MagicNames.loadObjectReference ||
          methodName == MagicNames.prepareWord ||
          methodName == MagicNames.loadInt ||
          methodName == MagicNames.prepareInt ||
          methodName == MagicNames.loadFloat) {

        if (types.length == 0) {
          // No offset
          asm.emitPOP_Reg(T0);                      // address
          asm.emitPUSH_RegInd(T0);                  // pushes [T0+0]
        } else {
          // Load at offset
          asm.emitPOP_Reg(S0);                  // offset
          asm.emitPOP_Reg(T0);                  // object ref
          asm.emitPUSH_RegIdx(T0, S0, Assembler.BYTE, NO_SLOT); // pushes [T0+S0]
        }
        return true;
      }

      if (methodName == MagicNames.loadByte) {
        if (types.length == 0) {
          // No offset
          asm.emitPOP_Reg(T0);                  // base
          asm.emitMOVSX_Reg_RegInd_Byte(T0, T0);
          asm.emitPUSH_Reg(T0);
        } else {
          // Load at offset
          asm.emitPOP_Reg(S0);                  // offset
          asm.emitPOP_Reg(T0);                  // base
          asm.emitMOVSX_Reg_RegIdx_Byte(T0, T0, S0, Assembler.BYTE, NO_SLOT); // load and sign extend byte [T0+S0]
          asm.emitPUSH_Reg(T0);
        }
        return true;
      }

      if (methodName == MagicNames.loadShort) {
        if (types.length == 0) {
          // No offset
          asm.emitPOP_Reg(T0);                  // base
          asm.emitMOVSX_Reg_RegInd_Word(T0, T0);
          asm.emitPUSH_Reg(T0);
        } else {
          // Load at offset
          asm.emitPOP_Reg(S0);                  // offset
          asm.emitPOP_Reg(T0);                  // base
          asm.emitMOVSX_Reg_RegIdx_Word(T0, T0, S0, Assembler.BYTE, NO_SLOT); // load and sign extend word [T0+S0]
          asm.emitPUSH_Reg(T0);
        }
        return true;
      }

      if (methodName == MagicNames.loadChar) {

        if (types.length == 0) {
          // No offset
          asm.emitPOP_Reg(T0);                  // base
          asm.emitMOVZX_Reg_RegInd_Word(T0, T0);
          asm.emitPUSH_Reg(T0);
        } else {
          // Load at offset
          asm.emitPOP_Reg(S0);                  // offset
          asm.emitPOP_Reg(T0);                  // base
          asm.emitMOVZX_Reg_RegIdx_Word(T0, T0, S0, Assembler.BYTE, NO_SLOT); // load and zero extend word [T0+S0]
          asm.emitPUSH_Reg(T0);
        }
        return true;
      }

      if (methodName == MagicNames.prepareLong ||
          methodName == MagicNames.loadLong ||
          methodName == MagicNames.loadDouble) {

        if (types.length == 0) {
          // No offset
          asm.emitPOP_Reg(T0);                // base
          asm.emitPUSH_RegDisp(T0, ONE_SLOT); // pushes [T0+4]
          asm.emitPUSH_RegInd(T0);            // pushes [T0]
        } else {
          // Load at offset
          asm.emitPOP_Reg(S0);                  // offset
          asm.emitPOP_Reg(T0);                  // base
          asm.emitPUSH_RegIdx(T0, S0, Assembler.BYTE, ONE_SLOT); // pushes [T0+S0+4]
          asm.emitPUSH_RegIdx(T0, S0, Assembler.BYTE, NO_SLOT);  // pushes [T0+S0]
        }
        return true;
      }

      // Stores all take the form:
      // ..., Address, [Offset], Value -> ...
      if (methodName == MagicNames.store) {
        // Always at least one parameter to a store.
        // First parameter is the type to be stored.
        TypeReference storeType = types[0];

        if (storeType == TypeReference.Int ||
            storeType == TypeReference.Address ||
            storeType == TypeReference.ObjectReference ||
            storeType == TypeReference.Word ||
            storeType == TypeReference.Float) {

          if (types.length == 1) {
            // No offset
            asm.emitPOP_Reg(T0);                   // value
            asm.emitPOP_Reg(S0);                   // address
            asm.emitMOV_RegInd_Reg(S0, T0);         // [S0+0] <- T0
          } else {
            // Store at offset
            asm.emitPOP_Reg(S0);                   // offset
            asm.emitPOP_Reg(T0);                   // value
            asm.emitPOP_Reg(T1);                   // address
            asm.emitMOV_RegIdx_Reg(T1, S0, Assembler.BYTE, NO_SLOT, T0); // [T1+S0] <- T0
          }
          return true;
        }

        if (storeType == TypeReference.Byte) {
          if (types.length == 1) {
            // No offset
            asm.emitPOP_Reg(T0);                   // value
            asm.emitPOP_Reg(T1);                   // base
            asm.emitMOV_RegInd_Reg_Byte(T1, T0);
          } else {
            // Store at offset
            asm.emitPOP_Reg(S0);                   // offset
            asm.emitPOP_Reg(T0);                   // value
            asm.emitPOP_Reg(T1);                   // base
            asm.emitMOV_RegIdx_Reg_Byte(T1, S0, Assembler.BYTE, NO_SLOT, T0); // [T1+S0] <- (byte) T0
          }
          return true;
        }

        if (storeType == TypeReference.Short || storeType == TypeReference.Char) {

          if (types.length == 1) {
            // No offset
            asm.emitPOP_Reg(T0);                   // value
            asm.emitPOP_Reg(T1);                   // base
            asm.emitMOV_RegInd_Reg_Word(T1, T0);
          } else {
            // Store at offset
            asm.emitPOP_Reg(S0);                   // offset
            asm.emitPOP_Reg(T0);                   // value
            asm.emitPOP_Reg(T1);                   // base
            asm.emitMOV_RegIdx_Reg_Word(T1, S0, Assembler.BYTE, NO_SLOT, T0); // [T1+S0] <- (word) T0
          }
          return true;
        }

        if (storeType == TypeReference.Double || storeType == TypeReference.Long) {

          if (types.length == 1) {
            // No offset
            asm.emitMOV_Reg_RegInd(T0, SP);             // value high
            asm.emitMOV_Reg_RegDisp(T1, SP, TWO_SLOTS); // base
            asm.emitMOV_RegInd_Reg(T1, T0);             // [T1] <- T0
            asm.emitMOV_Reg_RegDisp(T0, SP, ONE_SLOT);  // value low
            asm.emitMOV_RegDisp_Reg(T1, ONE_SLOT, T0);  // [T1+4] <- T0
            asm.emitADD_Reg_Imm(SP, WORDSIZE * 3);      // pop stack locations
          } else {
            // Store at offset
            asm.emitMOV_Reg_RegDisp(T0, SP, ONE_SLOT);          // value high
            asm.emitMOV_Reg_RegInd(S0, SP);     // offset
            asm.emitMOV_Reg_RegDisp(T1, SP, THREE_SLOTS);     // base
            asm.emitMOV_RegIdx_Reg(T1, S0, Assembler.BYTE, NO_SLOT, T0); // [T1+S0] <- T0
            asm.emitMOV_Reg_RegDisp(T0, SP, TWO_SLOTS);     // value low
            asm.emitMOV_RegIdx_Reg(T1, S0, Assembler.BYTE, ONE_SLOT, T0); // [T1+S0+4] <- T0
            asm.emitADD_Reg_Imm(SP, WORDSIZE * 4); // pop stack locations
          }
          return true;
        }
      } else if (methodName == MagicNames.attempt) {
        // All attempts are similar 32 bit values.
        if (types.length == 3) {
          // Offset passed
          asm.emitPOP_Reg(S0);        // S0 = offset
        }
        asm.emitPOP_Reg(T1);          // newVal
        asm.emitPOP_Reg(EAX);         // oldVal (EAX is implicit arg to LCMPX
        if (types.length == 3) {
          asm.emitADD_Reg_RegInd(S0, SP);  // S0 += base
        } else {
          // No offset
          asm.emitMOV_Reg_RegInd(S0, SP);  // S0 = base
        }
        asm.emitLockNextInstruction();
        asm.emitCMPXCHG_RegInd_Reg(S0, T1);   // atomic compare-and-exchange
        asm.emitMOV_RegInd_Imm(SP, 1);        // 'push' true (overwriting base)
        asm.emitBranchLikelyNextInstruction();
        ForwardReference fr = asm.forwardJcc(Assembler.EQ); // skip if compare fails
        asm.emitMOV_RegInd_Imm(SP, 0);        // 'push' false (overwriting base)
        fr.resolve(asm);
        return true;
      }
    }

    if (methodName == MagicNames.attemptInt ||
        methodName == MagicNames.attemptObject ||
        methodName == MagicNames.attemptAddress ||
        methodName == MagicNames.attemptWord) {
      // attempt gets called with four arguments: base, offset, oldVal, newVal
      // returns ([base+offset] == oldVal)
      // if ([base+offset] == oldVal) [base+offset] := newVal
      // (operation on memory is atomic)
      asm.emitPOP_Reg(T1);            // newVal
      asm.emitPOP_Reg(EAX);           // oldVal (EAX is implicit arg to LCMPXCNG
      asm.emitPOP_Reg(S0);            // S0 = offset
      asm.emitADD_Reg_RegInd(S0, SP);  // S0 += base
      asm.emitLockNextInstruction();
      asm.emitCMPXCHG_RegInd_Reg(S0, T1);   // atomic compare-and-exchange
      asm.emitMOV_RegInd_Imm(SP, 1);        // 'push' true (overwriting base)
      asm.emitBranchLikelyNextInstruction();
      ForwardReference fr = asm.forwardJcc(Assembler.EQ); // skip if compare fails
      asm.emitMOV_RegInd_Imm(SP, 0);        // 'push' false (overwriting base)
      fr.resolve(asm);
      return true;
    }

    if (methodName == MagicNames.attemptLong) {
      // attempt gets called with four arguments: base, offset, oldVal, newVal
      // returns ([base+offset] == oldVal)
      // if ([base+offset] == oldVal) [base+offset] := newVal
      // (operation on memory is atomic)
      //t1:t0 with s0:ebx
      asm.emitMOV_Reg_RegDisp(T1, SP, THREE_SLOTS);
      asm.emitMOV_Reg_RegDisp(T0, SP, TWO_SLOTS);     // T1:T0 (EDX:EAX) -> oldVal
      asm.emitMOV_RegDisp_Reg(SP, THREE_SLOTS, EBX);  // Save EBX
      asm.emitMOV_RegDisp_Reg(SP, TWO_SLOTS, ESI);    // Save ESI
      asm.emitMOV_Reg_RegInd(EBX, SP);
      asm.emitMOV_Reg_RegDisp(S0, SP, ONE_SLOT);      // S0:EBX (ECX:EBX) -> newVal
      asm.emitMOV_Reg_RegDisp(ESI, SP, FIVE_SLOTS);   // ESI := base
      asm.emitADD_Reg_RegDisp(ESI, SP, FOUR_SLOTS);   // ESI += offset
      asm.emitLockNextInstruction();
      asm.emitCMPXCHG8B_RegInd(ESI);                  // atomic compare-and-exchange
      ForwardReference fr1 = asm.forwardJcc(Assembler.NE); // skip if compare fails
      asm.emitMOV_RegDisp_Imm(SP, FIVE_SLOTS, 1);     // 'push' true (overwriting base)
      ForwardReference fr2 = asm.forwardJMP();     // skip if compare fails
      fr1.resolve(asm);
      asm.emitMOV_RegDisp_Imm(SP, FIVE_SLOTS, 0);     // 'push' false (overwriting base)
      fr2.resolve(asm);
      asm.emitMOV_Reg_RegDisp(EBX, SP, THREE_SLOTS);  // Restore EBX
      asm.emitMOV_Reg_RegDisp(ESI, SP, TWO_SLOTS);    // Restore ESI
      asm.emitADD_Reg_Imm(SP, WORDSIZE*5);            // adjust SP popping the 4 args (6 slots) and pushing the result
      return true;
    }

    if (methodName == MagicNames.saveThreadState) {
      Offset offset = ArchEntrypoints.saveThreadStateInstructionsField.getOffset();
      genParameterRegisterLoad(1); // pass 1 parameter word
      asm.emitCALL_Abs(Magic.getTocPointer().plus(offset));
      return true;
    }

    if (methodName == MagicNames.threadSwitch) {
      Offset offset = ArchEntrypoints.threadSwitchInstructionsField.getOffset();
      genParameterRegisterLoad(2); // pass 2 parameter words
      asm.emitCALL_Abs(Magic.getTocPointer().plus(offset));
      return true;
    }

    if (methodName == MagicNames.restoreHardwareExceptionState) {
      Offset offset = ArchEntrypoints.restoreHardwareExceptionStateInstructionsField.getOffset();
      genParameterRegisterLoad(1); // pass 1 parameter word
      asm.emitCALL_Abs(Magic.getTocPointer().plus(offset));
      return true;
    }

    if (methodName == MagicNames.invokeClassInitializer) {
      asm.emitPOP_Reg(S0);
      asm.emitCALL_Reg(S0); // call address just popped
      return true;
    }

    if (m.isSysCall()) {
      TypeReference[] args = m.getParameterTypes();
      TypeReference rtype = m.getReturnType();
      Offset offsetToJavaArg = THREE_SLOTS; // the three regs saved in (2)
      int paramBytes = 0;

      // (1) save three RVM nonvolatile/special registers
      //     we don't have to save EBP: the callee will
      //     treat it as a framepointer and save/restore
      //     it for us.
      asm.emitPUSH_Reg(EBX);
      asm.emitPUSH_Reg(ESI);
      asm.emitPUSH_Reg(EDI);

      // (2) Push args to target function (reversed), except for the first argument
      for (int i = args.length - 1; i >= 1; i--) {
        TypeReference arg = args[i];
        if (arg.isLongType() || arg.isDoubleType()) {
          asm.emitPUSH_RegDisp(SP, offsetToJavaArg.plus(4));
          asm.emitPUSH_RegDisp(SP, offsetToJavaArg.plus(4));
          offsetToJavaArg = offsetToJavaArg.plus(16);
          paramBytes += 8;
        } else {
          asm.emitPUSH_RegDisp(SP, offsetToJavaArg);
          offsetToJavaArg = offsetToJavaArg.plus(8);
          paramBytes += 4;
        }
      }

      // (3) invoke target function with address given by the first argument
      asm.emitMOV_Reg_RegDisp(S0, SP, offsetToJavaArg);
      asm.emitCALL_Reg(S0);

      // (4) pop space for arguments
      asm.emitADD_Reg_Imm(SP, paramBytes);

      // (5) restore RVM registers
      asm.emitPOP_Reg(EDI);
      asm.emitPOP_Reg(ESI);
      asm.emitPOP_Reg(EBX);

      // (6) pop expression stack (including the first parameter)
      asm.emitADD_Reg_Imm(SP, paramBytes + 4);

      // (7) push return value
      if (rtype.isLongType()) {
        asm.emitPUSH_Reg(T1);
        asm.emitPUSH_Reg(T0);
      } else if (rtype.isDoubleType()) {
        asm.emitSUB_Reg_Imm(SP, 8);
        asm.emitFSTP_RegInd_Reg_Quad(SP, FP0);
      } else if (rtype.isFloatType()) {
        asm.emitSUB_Reg_Imm(SP, 4);
        asm.emitFSTP_RegInd_Reg(SP, FP0);
      } else if (!rtype.isVoidType()) {
        asm.emitPUSH_Reg(T0);
      }

      return true;
    }

    if (methodName == MagicNames.getFramePointer) {
      asm.emitLEA_Reg_RegDisp(S0, SP, fp2spOffset(NO_SLOT));
      asm.emitPUSH_Reg(S0);
      return true;
    }

    if (methodName == MagicNames.getCallerFramePointer) {
      asm.emitPOP_Reg(T0);                                       // Callee FP
      asm.emitPUSH_RegDisp(T0, Offset.fromIntSignExtend(STACKFRAME_FRAME_POINTER_OFFSET)); // Caller FP
      return true;
    }

    if (methodName == MagicNames.setCallerFramePointer) {
      asm.emitPOP_Reg(T0);  // value
      asm.emitPOP_Reg(S0);  // fp
      asm.emitMOV_RegDisp_Reg(S0, Offset.fromIntSignExtend(STACKFRAME_FRAME_POINTER_OFFSET), T0); // [S0+SFPO] <- T0
      return true;
    }

    if (methodName == MagicNames.getCompiledMethodID) {
      asm.emitPOP_Reg(T0);                                   // Callee FP
      asm.emitPUSH_RegDisp(T0, Offset.fromIntSignExtend(STACKFRAME_METHOD_ID_OFFSET)); // Callee CMID
      return true;
    }

    if (methodName == MagicNames.setCompiledMethodID) {
      asm.emitPOP_Reg(T0);  // value
      asm.emitPOP_Reg(S0);  // fp
      asm.emitMOV_RegDisp_Reg(S0, Offset.fromIntSignExtend(STACKFRAME_METHOD_ID_OFFSET), T0); // [S0+SMIO] <- T0
      return true;
    }

    if (methodName == MagicNames.getReturnAddressLocation) {
      asm.emitPOP_Reg(T0);                                        // Callee FP
      asm.emitADD_Reg_Imm(T0, STACKFRAME_RETURN_ADDRESS_OFFSET);  // location containing callee return address
      asm.emitPUSH_Reg(T0);                                       // Callee return address
      return true;
    }

    if (methodName == MagicNames.getTocPointer || methodName == MagicNames.getJTOC) {
      asm.emitPUSH_Imm((int)Magic.getTocPointer().toWord().toLong());
      return true;
    }

    // get the processor register (TR)
    if (methodName == MagicNames.getThreadRegister) {
      asm.emitPUSH_Reg(TR);
      return true;
    }

    // set the processor register (TR)
    if (methodName == MagicNames.setThreadRegister) {
      asm.emitPOP_Reg(TR);
      return true;
    }

    // Get the value in ESI
    if (methodName == MagicNames.getESIAsThread) {
      asm.emitPUSH_Reg(ESI);
      return true;
    }

    // Set the value in ESI
    if (methodName == MagicNames.setESIAsThread) {
      asm.emitPOP_Reg(ESI);
      return true;
    }

    if (methodName == MagicNames.getIntAtOffset ||
        methodName == MagicNames.getWordAtOffset ||
        methodName == MagicNames.getObjectAtOffset ||
        methodName == MagicNames.getTIBAtOffset ||
        methodName == MagicNames.prepareInt ||
        methodName == MagicNames.prepareObject ||
        methodName == MagicNames.prepareAddress ||
        methodName == MagicNames.prepareWord) {
      if (m.getParameterTypes().length == 3) {
        // discard locationMetadata parameter
        asm.emitPOP_Reg(T0); // locationMetadata, not needed by baseline compiler.
      }
      asm.emitPOP_Reg(T0);                  // object ref
      asm.emitPOP_Reg(S0);                  // offset
      asm.emitPUSH_RegIdx(T0, S0, Assembler.BYTE, NO_SLOT); // pushes [T0+S0]
      return true;
    }

    if (methodName == MagicNames.getUnsignedByteAtOffset) {
      asm.emitPOP_Reg(T0);                  // object ref
      asm.emitPOP_Reg(S0);                  // offset
      asm.emitMOVZX_Reg_RegIdx_Byte(T0, T0, S0, Assembler.BYTE, NO_SLOT); // load and zero extend byte [T0+S0]
      asm.emitPUSH_Reg(T0);
      return true;
    }

    if (methodName == MagicNames.getByteAtOffset) {
      asm.emitPOP_Reg(T0);                  // object ref
      asm.emitPOP_Reg(S0);                  // offset
      asm.emitMOVSX_Reg_RegIdx_Byte(T0, T0, S0, Assembler.BYTE, NO_SLOT); // load and zero extend byte [T0+S0]
      asm.emitPUSH_Reg(T0);
      return true;
    }

    if (methodName == MagicNames.getCharAtOffset) {
      asm.emitPOP_Reg(T0);                  // object ref
      asm.emitPOP_Reg(S0);                  // offset
      asm.emitMOVZX_Reg_RegIdx_Word(T0, T0, S0, Assembler.BYTE, NO_SLOT); // load and zero extend char [T0+S0]
      asm.emitPUSH_Reg(T0);
      return true;
    }

    if (methodName == MagicNames.getShortAtOffset) {
      asm.emitPOP_Reg(T0);                  // object ref
      asm.emitPOP_Reg(S0);                  // offset
      asm.emitMOVSX_Reg_RegIdx_Word(T0, T0, S0, Assembler.BYTE, NO_SLOT); // load and zero extend char [T0+S0]
      asm.emitPUSH_Reg(T0);
      return true;
    }

    if (methodName == MagicNames.setIntAtOffset ||
        methodName == MagicNames.setWordAtOffset ||
        methodName == MagicNames.setObjectAtOffset) {
      if (m.getParameterTypes().length == 4) {
        // discard locationMetadata parameter
        asm.emitPOP_Reg(T0); // locationMetadata, not needed by baseline compiler.
      }
      asm.emitPOP_Reg(T0);                   // value
      asm.emitPOP_Reg(S0);                   // offset
      asm.emitPOP_Reg(T1);                   // obj ref
      asm.emitMOV_RegIdx_Reg(T1, S0, Assembler.BYTE, NO_SLOT, T0); // [T1+S0] <- T0
      return true;
    }

    if (methodName == MagicNames.setByteAtOffset) {
      asm.emitPOP_Reg(T0);                   // value
      asm.emitPOP_Reg(S0);                   // offset
      asm.emitPOP_Reg(T1);                   // obj ref
      asm.emitMOV_RegIdx_Reg_Byte(T1, S0, Assembler.BYTE, NO_SLOT, T0); // [T1+S0] <- (byte) T0
      return true;
    }

    if (methodName == MagicNames.setCharAtOffset) {
      asm.emitPOP_Reg(T0);                   // value
      asm.emitPOP_Reg(S0);                   // offset
      asm.emitPOP_Reg(T1);                   // obj ref
      asm.emitMOV_RegIdx_Reg_Word(T1, S0, Assembler.BYTE, NO_SLOT, T0); // [T1+S0] <- (char) T0
      return true;
    }

    if (methodName == MagicNames.prepareLong ||
        methodName == MagicNames.getLongAtOffset || methodName == MagicNames.getDoubleAtOffset) {
      asm.emitPOP_Reg(T0);                  // object ref
      asm.emitPOP_Reg(S0);                  // offset
      asm.emitPUSH_RegIdx(T0, S0, Assembler.BYTE, ONE_SLOT); // pushes [T0+S0+4]
      asm.emitPUSH_RegIdx(T0, S0, Assembler.BYTE, NO_SLOT); // pushes [T0+S0]
      return true;
    }

    if (methodName == MagicNames.setLongAtOffset || methodName == MagicNames.setDoubleAtOffset) {
      asm.emitMOV_Reg_RegInd(T0, SP);          // value high
      asm.emitMOV_Reg_RegDisp(S0, SP, TWO_SLOTS);     // offset
      asm.emitMOV_Reg_RegDisp(T1, SP, THREE_SLOTS);     // obj ref
      asm.emitMOV_RegIdx_Reg(T1, S0, Assembler.BYTE, NO_SLOT, T0); // [T1+S0] <- T0
      asm.emitMOV_Reg_RegDisp(T0, SP, ONE_SLOT);     // value low
      asm.emitMOV_RegIdx_Reg(T1, S0, Assembler.BYTE, ONE_SLOT, T0); // [T1+S0+4] <- T0
      asm.emitADD_Reg_Imm(SP, WORDSIZE * 4); // pop stack locations
      return true;
    }

    if (methodName == MagicNames.addressArrayCreate) {
      // no resolution problem possible.
      emit_resolved_newarray(m.getType().resolve().asArray());
      return true;
    }

    if (methodName == MagicNames.addressArrayLength) {
      emit_arraylength();  // argument order already correct
      return true;
    }

    if (methodName == MagicNames.addressArrayGet) {
      if (m.getType() == TypeReference.CodeArray) {
        emit_baload();
      } else {
        if (VM.BuildFor32Addr) {
          emit_iaload();
        } else if (VM.BuildFor64Addr) {
          emit_laload();
        } else {
          VM._assert(NOT_REACHED);
        }
      }
      return true;
    }

    if (methodName == MagicNames.addressArraySet) {
      if (m.getType() == TypeReference.CodeArray) {
        emit_bastore();
      } else {
        if (VM.BuildFor32Addr) {
          emit_iastore();
        } else if (VM.BuildFor64Addr) {
          VM._assert(false);  // not implemented
        } else {
          VM._assert(NOT_REACHED);
        }
      }
      return true;
    }

    if (methodName == MagicNames.getMemoryInt ||
        methodName == MagicNames.getMemoryWord ||
        methodName == MagicNames.getMemoryAddress) {
      asm.emitPOP_Reg(T0);      // address
      asm.emitPUSH_RegInd(T0); // pushes [T0+0]
      return true;
    }

    if (methodName == MagicNames.setMemoryInt || methodName == MagicNames.setMemoryWord) {
      if (m.getParameterTypes().length == 3) {
        // discard locationMetadata parameter
        asm.emitPOP_Reg(T0); // locationMetadata, not needed by baseline compiler.
      }
      asm.emitPOP_Reg(T0);  // value
      asm.emitPOP_Reg(S0);  // address
      asm.emitMOV_RegInd_Reg(S0, T0); // [S0+0] <- T0
      return true;
    }

    if (methodName == MagicNames.objectAsAddress ||
        methodName == MagicNames.addressAsByteArray ||
        methodName == MagicNames.addressAsObject ||
        methodName == MagicNames.addressAsTIB ||
        methodName == MagicNames.objectAsType ||
        methodName == MagicNames.objectAsShortArray ||
        methodName == MagicNames.objectAsIntArray ||
        methodName == MagicNames.objectAsThread ||
        methodName == MagicNames.threadAsCollectorThread ||
        methodName == MagicNames.floatAsIntBits ||
        methodName == MagicNames.intBitsAsFloat ||
        methodName == MagicNames.doubleAsLongBits ||
        methodName == MagicNames.longBitsAsDouble) {
      // no-op (a type change, not a representation change)
      return true;
    }

    // code for      RVMType Magic.getObjectType(Object object)
    if (methodName == MagicNames.getObjectType) {
      asm.emitPOP_Reg(T0);                               // object ref
      baselineEmitLoadTIB(asm, S0, T0);
      asm.emitPUSH_RegDisp(S0, Offset.fromIntZeroExtend(TIB_TYPE_INDEX << LG_WORDSIZE)); // push RVMType slot of TIB
      return true;
    }

    if (methodName == MagicNames.getArrayLength) {
      asm.emitPOP_Reg(T0);                      // object ref
      asm.emitPUSH_RegDisp(T0, ObjectModel.getArrayLengthOffset());
      return true;
    }

    if (methodName == MagicNames.sync) {  // nothing required on IA32
      return true;
    }

    if (methodName == MagicNames.isync) { // nothing required on IA32
      return true;
    }

    // baseline compiled invocation only: all paramaters on the stack
    // hi mem
    //      Code
    //      GPRs
    //      FPRs
    //      Spills
    // low-mem
    if (methodName == MagicNames.invokeMethodReturningVoid) {
      Offset offset = ArchEntrypoints.reflectiveMethodInvokerInstructionsField.getOffset();
      genParameterRegisterLoad(5); // pass 5 parameter words
      asm.emitCALL_Abs(Magic.getTocPointer().plus(offset));
      return true;
    }

    if (methodName == MagicNames.invokeMethodReturningInt) {
      Offset offset = ArchEntrypoints.reflectiveMethodInvokerInstructionsField.getOffset();
      genParameterRegisterLoad(5); // pass 5 parameter words
      asm.emitCALL_Abs(Magic.getTocPointer().plus(offset));
      asm.emitPUSH_Reg(T0);
      return true;
    }

    if (methodName == MagicNames.invokeMethodReturningLong) {
      Offset offset = ArchEntrypoints.reflectiveMethodInvokerInstructionsField.getOffset();
      genParameterRegisterLoad(5); // pass 5 parameter words
      asm.emitCALL_Abs(Magic.getTocPointer().plus(offset));
      asm.emitPUSH_Reg(T0); // high half
      asm.emitPUSH_Reg(T1); // low half
      return true;
    }

    if (methodName == MagicNames.invokeMethodReturningFloat) {
      Offset offset = ArchEntrypoints.reflectiveMethodInvokerInstructionsField.getOffset();
      genParameterRegisterLoad(5); // pass 5 parameter words
      asm.emitCALL_Abs(Magic.getTocPointer().plus(offset));
      asm.emitSUB_Reg_Imm(SP, 4);
      if (SSE2_FULL) {
        asm.emitMOVSS_RegInd_Reg(SP, XMM0);
      } else {
        asm.emitFSTP_RegInd_Reg(SP, FP0);
      }
      return true;
    }

    if (methodName == MagicNames.invokeMethodReturningDouble) {
      Offset offset = ArchEntrypoints.reflectiveMethodInvokerInstructionsField.getOffset();
      genParameterRegisterLoad(5); // pass 5 parameter words
      asm.emitCALL_Abs(Magic.getTocPointer().plus(offset));
      asm.emitSUB_Reg_Imm(SP, 8);
      if (SSE2_FULL) {
        asm.emitMOVLPD_RegInd_Reg(SP, XMM0);
      } else {
        asm.emitFSTP_RegInd_Reg_Quad(SP, FP0);
      }
      return true;
    }

    if (methodName == MagicNames.invokeMethodReturningObject) {
      Offset offset = ArchEntrypoints.reflectiveMethodInvokerInstructionsField.getOffset();
      genParameterRegisterLoad(5); // pass 5 parameter words
      asm.emitCALL_Abs(Magic.getTocPointer().plus(offset));
      asm.emitPUSH_Reg(T0);
      return true;
    }

    // baseline invocation
    // one paramater, on the stack  -- actual code
    if (methodName == MagicNames.dynamicBridgeTo) {
      if (VM.VerifyAssertions) VM._assert(klass.hasDynamicBridgeAnnotation());

      // save the branch address for later
      asm.emitPOP_Reg(S0);             // S0<-code address

      asm.emitADD_Reg_Imm(SP, fp2spOffset(NO_SLOT).toInt() - 4); // just popped 4 bytes above.

      if (SSE2_FULL) {
        // TODO: Restore SSE2 Control word?
        asm.emitMOVQ_Reg_RegDisp(XMM0, SP, XMM_SAVE_OFFSET.plus(0));
        asm.emitMOVQ_Reg_RegDisp(XMM1, SP, XMM_SAVE_OFFSET.plus(8));
        asm.emitMOVQ_Reg_RegDisp(XMM2, SP, XMM_SAVE_OFFSET.plus(16));
        asm.emitMOVQ_Reg_RegDisp(XMM3, SP, XMM_SAVE_OFFSET.plus(24));
      } else {
        // restore FPU state
        asm.emitFRSTOR_RegDisp(SP, FPU_SAVE_OFFSET);
      }

      // restore GPRs
      asm.emitMOV_Reg_RegDisp(T0, SP, T0_SAVE_OFFSET);
      asm.emitMOV_Reg_RegDisp(T1, SP, T1_SAVE_OFFSET);
      asm.emitMOV_Reg_RegDisp(EBX, SP, EBX_SAVE_OFFSET);
      asm.emitMOV_Reg_RegDisp(EDI, SP, EDI_SAVE_OFFSET);

      // pop frame
      asm.emitPOP_RegDisp(TR, ArchEntrypoints.framePointerField.getOffset()); // FP<-previous FP

      // branch
      asm.emitJMP_Reg(S0);
      return true;
    }

    if (methodName == MagicNames.returnToNewStack) {
      // SP gets frame pointer for new stack
      asm.emitPOP_Reg(SP);

      // restore nonvolatile registers
      asm.emitMOV_Reg_RegDisp(EDI, SP, EDI_SAVE_OFFSET);
      asm.emitMOV_Reg_RegDisp(EBX, SP, EBX_SAVE_OFFSET);

      // discard current stack frame
      asm.emitPOP_RegDisp(TR, ArchEntrypoints.framePointerField.getOffset());

      // return to caller- pop parameters from stack
      asm.emitRET_Imm(parameterWords << LG_WORDSIZE);
      return true;
    }

    // software prefetch
    if (methodName == MagicNames.prefetch || methodName == MagicNames.prefetchNTA) {
      asm.emitPOP_Reg(T0);
      asm.emitPREFETCHNTA_Reg(T0);
      return true;
    }

    if (methodName == MagicNames.getTimeBase) {
      asm.emitRDTSC();       // read timestamp counter instruction
      asm.emitPUSH_Reg(EDX); // upper 32 bits
      asm.emitPUSH_Reg(EAX); // lower 32 bits
      return true;
    }

    if (methodName == MagicNames.pause) {
      asm.emitPAUSE();       // read timestamp counter instruction
      return true;
    }

    if (methodName == MagicNames.sqrt) {
      if (SSE2_BASE) {
        TypeReference argType = method.getParameterTypes()[0];
        if (argType == TypeReference.Float) {
          asm.emitSQRTSS_Reg_RegInd(XMM0, SP);            // XMM0 = sqrt(value)
          asm.emitMOVSS_RegInd_Reg(SP, XMM0);            // set result on stack
        } else {
          if (VM.VerifyAssertions)
            VM._assert(argType == TypeReference.Double);
          asm.emitSQRTSD_Reg_RegInd(XMM0, SP);            // XMM0 = sqrt(value)
          asm.emitMOVLPD_RegInd_Reg(SP, XMM0);            // set result on stack
        }
      } else {
        if (VM.VerifyAssertions)
          VM._assert(false,"Hardware SQRT is only supported in SSE");
      }
      return true;
    }

    if (methodName == MagicNames.wordFromInt ||
        methodName == MagicNames.wordFromObject ||
        methodName == MagicNames.wordFromIntZeroExtend ||
        methodName == MagicNames.wordFromIntSignExtend ||
        methodName == MagicNames.wordToInt ||
        methodName == MagicNames.wordToAddress ||
        methodName == MagicNames.wordToOffset ||
        methodName == MagicNames.wordToObject ||
        methodName == MagicNames.wordToObjectReference ||
        methodName == MagicNames.wordToExtent ||
        methodName == MagicNames.wordToWord ||
        methodName == MagicNames.codeArrayAsObject ||
        methodName == MagicNames.tibAsObject) {
      if (VM.BuildFor32Addr) return true;     // no-op for 32-bit
      if (VM.VerifyAssertions) VM._assert(false);
    }

    if (methodName == MagicNames.wordToLong) {
      if (VM.BuildFor32Addr) {
        asm.emitPOP_Reg(T0);
        asm.emitPUSH_Imm(0); // upper 32 bits
        asm.emitPUSH_Reg(T0); // lower 32 bits
        return true;
      } // else fill unused stackslot
      if (VM.VerifyAssertions) VM._assert(false);
    }

    if (methodName == MagicNames.wordAnd) {
      asm.emitPOP_Reg(T0);
      asm.emitAND_RegInd_Reg(SP, T0);
      return true;
    }

    if (methodName == MagicNames.wordOr) {
      asm.emitPOP_Reg(T0);
      asm.emitOR_RegInd_Reg(SP, T0);
      return true;
    }

    if (methodName == MagicNames.wordXor) {
      asm.emitPOP_Reg(T0);
      asm.emitXOR_RegInd_Reg(SP, T0);
      return true;
    }

    if (methodName == MagicNames.wordNot) {
      asm.emitNOT_RegInd(SP);
      return true;
    }

    if (methodName == MagicNames.wordPlus) {
      asm.emitPOP_Reg(T0);
      asm.emitADD_RegInd_Reg(SP, T0);
      return true;
    }

    if (methodName == MagicNames.wordMinus || methodName == MagicNames.wordDiff) {
      asm.emitPOP_Reg(T0);
      asm.emitSUB_RegInd_Reg(SP, T0);
      return true;
    }

    if (methodName == MagicNames.wordZero || methodName == MagicNames.wordNull) {
      asm.emitPUSH_Imm(0);
      return true;
    }

    if (methodName == MagicNames.wordOne) {
      asm.emitPUSH_Imm(1);
      return true;
    }

    if (methodName == MagicNames.wordMax) {
      asm.emitPUSH_Imm(-1);
      return true;
    }

    if (methodName == MagicNames.wordLT) {
      generateAddrComparison(Assembler.LLT);
      return true;
    }
    if (methodName == MagicNames.wordLE) {
      generateAddrComparison(Assembler.LLE);
      return true;
    }
    if (methodName == MagicNames.wordGT) {
      generateAddrComparison(Assembler.LGT);
      return true;
    }
    if (methodName == MagicNames.wordGE) {
      generateAddrComparison(Assembler.LGE);
      return true;
    }
    if (methodName == MagicNames.wordsLT) {
      generateAddrComparison(Assembler.LT);
      return true;
    }
    if (methodName == MagicNames.wordsLE) {
      generateAddrComparison(Assembler.LE);
      return true;
    }
    if (methodName == MagicNames.wordsGT) {
      generateAddrComparison(Assembler.GT);
      return true;
    }
    if (methodName == MagicNames.wordsGE) {
      generateAddrComparison(Assembler.GE);
      return true;
    }
    if (methodName == MagicNames.wordEQ) {
      generateAddrComparison(Assembler.EQ);
      return true;
    }
    if (methodName == MagicNames.wordNE) {
      generateAddrComparison(Assembler.NE);
      return true;
    }
    if (methodName == MagicNames.wordIsZero) {
      asm.emitPUSH_Imm(0);
      generateAddrComparison(Assembler.EQ);
      return true;
    }
    if (methodName == MagicNames.wordIsNull) {
      asm.emitPUSH_Imm(0);
      generateAddrComparison(Assembler.EQ);
      return true;
    }
    if (methodName == MagicNames.wordIsMax) {
      asm.emitPUSH_Imm(-1);
      generateAddrComparison(Assembler.EQ);
      return true;
    }
    if (methodName == MagicNames.wordLsh) {
      if (VM.BuildFor32Addr) {
        asm.emitPOP_Reg(ECX);
        asm.emitSHL_RegInd_Reg(SP, ECX);
      } else {
        VM._assert(false);
      }
      return true;
    }
    if (methodName == MagicNames.wordRshl) {
      if (VM.BuildFor32Addr) {
        asm.emitPOP_Reg(ECX);
        asm.emitSHR_RegInd_Reg(SP, ECX);
      } else {
        VM._assert(false);
      }
      return true;
    }
    if (methodName == MagicNames.wordRsha) {
      if (VM.BuildFor32Addr) {
        asm.emitPOP_Reg(ECX);
        asm.emitSAR_RegInd_Reg(SP, ECX);
      } else {
        VM._assert(false);
      }
      return true;
    }
    return false;

  }

  private void generateAddrComparison(byte comparator) {
    asm.emitPOP_Reg(S0);
    asm.emitPOP_Reg(T0);
    asm.emitCMP_Reg_Reg(T0, S0);
    ForwardReference fr1 = asm.forwardJcc(comparator);
    asm.emitPUSH_Imm(0);
    ForwardReference fr2 = asm.forwardJMP();
    fr1.resolve(asm);
    asm.emitPUSH_Imm(1);
    fr2.resolve(asm);
  }

  // Offset of Java local variable (off stack pointer)
  // assuming ESP is still positioned as it was at the
  // start of the current bytecode (biStart)
  private Offset localOffset(int local) {
    return Offset.fromIntZeroExtend((stackHeights[biStart] - local) << LG_WORDSIZE);
  }

  // Translate a FP offset into an SP offset
  // assuming ESP is still positioned as it was at the
  // start of the current bytecode (biStart)
  private Offset fp2spOffset(Offset offset) {
    int offsetToFrameHead = (stackHeights[biStart] << LG_WORDSIZE) - firstLocalOffset;
    return offset.plus(offsetToFrameHead);
  }

  private void emitDynamicLinkingSequence(GPR reg, MemberReference ref, boolean couldBeZero) {
    int memberId = ref.getId();
    Offset memberOffset = Offset.fromIntZeroExtend(memberId << 2);
    Offset tableOffset = Entrypoints.memberOffsetsField.getOffset();
    if (couldBeZero) {
      Offset resolverOffset = Entrypoints.resolveMemberMethod.getOffset();
      int retryLabel = asm.getMachineCodeIndex();            // branch here after dynamic class loading
      asm.emitMOV_Reg_Abs(reg, Magic.getTocPointer().plus(tableOffset));      // reg is offsets table
      asm.emitMOV_Reg_RegDisp(reg,
                              reg,
                              memberOffset);      // reg is offset of member, or 0 if member's class isn't loaded
      if (NEEDS_DYNAMIC_LINK == 0) {
        asm.emitTEST_Reg_Reg(reg, reg);                      // reg ?= NEEDS_DYNAMIC_LINK, is field's class loaded?
      } else {
        asm.emitCMP_Reg_Imm(reg, NEEDS_DYNAMIC_LINK);        // reg ?= NEEDS_DYNAMIC_LINK, is field's class loaded?
      }
      ForwardReference fr = asm.forwardJcc(Assembler.NE);       // if so, skip call instructions
      asm.emitPUSH_Imm(memberId);                            // pass member's dictId
      genParameterRegisterLoad(1);                           // pass 1 parameter word
      asm.emitCALL_Abs(Magic.getTocPointer().plus(resolverOffset));            // does class loading as sideffect
      asm.emitJMP_Imm(retryLabel);                          // reload reg with valid value
      fr.resolve(asm);                                       // come from Jcc above.
    } else {
      asm.emitMOV_Reg_Abs(reg, Magic.getTocPointer().plus(tableOffset));      // reg is offsets table
      asm.emitMOV_Reg_RegDisp(reg, reg, memberOffset);      // reg is offset of member
    }
  }

  /**
   * Emit code to invoke a compiled method (with known jtoc offset).
   * Treat it like a resolved invoke static, but take care of
   * this object in the case.
   *
   * I havenot thought about GCMaps for invoke_compiledmethod
   * TODO: Figure out what the above GCMaps comment means and fix it!
   */
  @Override
  protected final void emit_invoke_compiledmethod(CompiledMethod cm) {
    Offset methodOffset = cm.getOsrJTOCoffset();
    boolean takeThis = !cm.method.isStatic();
    MethodReference ref = cm.method.getMemberRef().asMethodReference();
    genParameterRegisterLoad(ref, takeThis);
    asm.emitCALL_Abs(Magic.getTocPointer().plus(methodOffset));
    genResultRegisterUnload(ref);
  }

  /**
   * Implementation for OSR load return address bytecode
   */
  @Override
  protected final void emit_loadretaddrconst(int bcIndex) {
    asm.generateLoadReturnAddress(bcIndex);
  }

  /**
   * Generate branch for pending goto OSR mechanism
   * @param bTarget is optional, it emits a JUMP instruction, but the caller
   * is responsible for patching the target address by calling the resolve method
   * of the returned forward reference.
   */
  @Override
  protected final ForwardReference emit_pending_goto(int bTarget) {
    return asm.generatePendingJMP(bTarget);
  }
}

/*
Local Variables:
   c-basic-offset: 2
End:
*/
