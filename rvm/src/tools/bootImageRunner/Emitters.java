// package com.ibm.JikesRVM.GenerateInterfaceDeclarations;

import com.ibm.JikesRVM.classloader.VM_Field;
import com.ibm.JikesRVM.classloader.VM_Class;
import com.ibm.JikesRVM.classloader.VM_Atom;
import  java.io.*;
import  java.io.PrintStream;
import  java.util.*;
import java.lang.reflect.*;
import com.ibm.JikesRVM.*;
import com.ibm.JikesRVM.classloader.*;


class Emitters extends Shared {

  private Class vmClass;

  public Emitters(Class vmClass) {
    this.vmClass = vmClass;
  }

  public void emitStuff(int bootImageAddress) 
    throws Exception
  {
    p("/*------ MACHINE GENERATED by ");
    p("GenerateInterfaceDeclarations.java: DO NOT EDIT");
    p("------*/\n\n");

    pln("#if defined NEED_BOOT_RECORD_DECLARATIONS || defined NEED_VIRTUAL_MACHINE_DECLARATIONS");
    pln("#include <inttypes.h>");
    Field build32 = vmClass.getField("BuildFor32Addr");
    if (build32.getBoolean(null)) {
      pln("#define VM_Address uint32_t");
      pln("#define VM_Word uint32_t");
      pln("#define JavaObject_t uint32_t");
    } else {
      pln("#define VM_Address uint64_t");
      pln("#define VM_Word uint64_t");
      pln("#define JavaObject_t uint64_t");
    }
    pln("#endif /* NEED_BOOT_RECORD_DECLARATIONS || NEED_VIRTUAL_MACHINE_DECLARATIONS */");
    pln();

    pln("#ifdef NEED_BOOT_RECORD_DECLARATIONS");
    emitBootRecordDeclarations();
    pln("#endif /* NEED_BOOT_RECORD_DECLARATIONS */");
    pln();

    pln("#ifdef NEED_BOOT_RECORD_INITIALIZATION");
    emitBootRecordInitialization();
    pln("#endif /* NEED_BOOT_RECORD_INITIALIZATION */");
    pln();

    emitGNUClasspathVersion();

    pln("#ifdef NEED_VIRTUAL_MACHINE_DECLARATIONS");
    emitVirtualMachineDeclarations(bootImageAddress);
    pln("#endif /* NEED_VIRTUAL_MACHINE_DECLARATIONS */");
    pln();

    pln("#ifdef NEED_ASSEMBLER_DECLARATIONS");
    emitAssemblerDeclarations();
    pln("#endif /* NEED_ASSEMBLER_DECLARATIONS */");
  }

  /** Get the version of the GNU Classpath library from
        gnu.classpath.configuration */
  public void emitGNUClasspathVersion() 
    throws Exception
  {
    pln("#ifdef NEED_GNU_CLASSPATH_VERSION");
    /*  We have to use reflection here.

        The case to consider: 

        A VM (not necessarily Jikes RVM) that uses GNU Classpath version X,
        but where we are building Jikes RVM with GNU Classpath version Y
    */
    Class classpathConfig = getClassNamed("gnu.classpath.Configuration");
    Field versionField = classpathConfig.getField("CLASSPATH_VERSION");
    String ver = (String) versionField.get("CLASSPATH_VERSION");
    
    p("static const char*classpath_version              = \"" + ver + "\";\n");
    pln("#endif /* NEED_GNU_CLASSPATH_VERSION */");
    pln();

  }

  private static class SortableField implements Comparable {
    final VM_Field f;
    final int offset;
    SortableField (VM_Field ff) { f = ff; offset = f.getOffset(); }
    public int compareTo (Object y) {
      if (y instanceof SortableField) {
        int offset2 = ((SortableField) y).offset;
        if (offset > offset2) return 1;
        if (offset < offset2) return -1;
        return 0;
      }
      return 1;
    }
  }

  void emitCDeclarationsForJavaType (String Cname, VM_Class cls) {
    // How many instance fields are there?
    //
    VM_Field[] allFields = cls.getDeclaredFields();
    int fieldCount = 0;
    for (int i=0; i<allFields.length; i++)
      if (!allFields[i].isStatic())
        fieldCount++;

    // Sort them in ascending offset order
    //
    SortableField [] fields = new SortableField[fieldCount];
    for (int i=0, j=0; i<allFields.length; i++)
      if (!allFields[i].isStatic())
        fields[j++] = new SortableField(allFields[i]);
    Arrays.sort(fields);

    // Set up cursor - scalars will waste 4 bytes on 64-bit arch
    //
    //    boolean needsAlign = VM.BuildFor64Addr;
    boolean needsAlign = vmBool("BuildFor64Addr");
    int addrSize = vmBool("BuildFor32Addr") ? 4 : 8;
    int current = fields[0].offset;
    if (needsAlign && ((current & 7) != 0))
        current -= 4;
    if (current >= 0) 
        pln("Are scalars no longer backwards?  If so, check this code.");

    // Emit field declarations
    //
    p("struct " + Cname + " {\n");
    for (int i = 0; i<fields.length; i++) {
      VM_Field field = fields[i].f;
      VM_TypeReference t = field.getType();
      int offset = field.getOffset();
      String name = field.getName().toString();
      // Align by blowing 4 bytes if needed
      if (needsAlign && current + 4 == offset) {
          pln("  uint32_t    padding" + i + ";");
          current += 4;
      }
      if (current != offset) 
        pln("current = " + current + " and offset = " + offset + " are neither identical not differ by 4");
      if (t.isIntType()) {
        current += 4;
        p("   uint32_t " + name + ";\n");
      } else if (t.isLongType()) {
        current += 8;
        p("   uint64_t " + name + ";\n");
      } else if (t.isWordType()) {
        p("   VM_Address " + name + ";\n");
        current += addrSize;
      } else if (t.isArrayType() && t.getArrayElementType().isWordType()) {
        p("   VM_Address * " + name + ";\n");
        current += addrSize;
      } else if (t.isArrayType() && t.getArrayElementType().isIntType()) {
        p("   unsigned int * " + name + ";\n");
        current += addrSize;
      } else if (t.isReferenceType()) {
        p("   JavaObject_t " + name + ";\n");
        current += addrSize;
      } else {
        ep("Unexpected field " + name.toString() + " with type " + t + "\n");
        throw new RuntimeException("unexpected field type");
      }
    }

    p("};\n");
  }


  void emitBootRecordDeclarations () {
    VM_Atom className = VM_Atom.findOrCreateAsciiAtom("com/ibm/JikesRVM/VM_BootRecord");
    VM_Atom classDescriptor = className.descriptorFromClassName();
    VM_Class bootRecord = null;
    try {
      bootRecord = VM_TypeReference.findOrCreate(altCL, classDescriptor).resolve().asClass();
    } catch (NoClassDefFoundError e) {
      epln("Failed to load VM_BootRecord!");
      System.exit(-1);
    }
    emitCDeclarationsForJavaType("VM_BootRecord", bootRecord);
  }




  // Emit declarations for VM_BootRecord object.
  //
  void emitBootRecordInitialization() {
    VM_Atom className = VM_Atom.findOrCreateAsciiAtom("com/ibm/JikesRVM/VM_BootRecord");
    VM_Atom classDescriptor = className.descriptorFromClassName();
    VM_Class bootRecord = null;
    try {
      bootRecord = VM_TypeReference.findOrCreate(altCL, classDescriptor).resolve().asClass();
    } catch (NoClassDefFoundError e) {
      reportTrouble("Failed to load VM_BootRecord", e);
      System.exit(-1);          // unreached
    }
    VM_Field[] fields = bootRecord.getDeclaredFields();

    // emit function declarations
    //
    for (int i = fields.length; --i >= 0;) {
      VM_Field field = fields[i];
      if (field.isStatic())
        continue;
      String fieldName = field.getName().toString();
      int suffixIndex = fieldName.indexOf("IP");
      if (suffixIndex > 0) {
        // java field "xxxIP" corresponds to C function "xxx"
        String functionName = fieldName.substring(0, suffixIndex);
        // e. g.,
        // extern "C" void sysFOOf();
        p("extern \"C\" int " + functionName + "();\n");
      }
    }

    // emit field initializers
    //
    p("extern \"C\" void setLinkage(VM_BootRecord* br){\n");
    for (int i = fields.length; --i >= 0;) {
      VM_Field field = fields[i];
      if (field.isStatic())
        continue;

      String fieldName = field.getName().toString();
      int suffixIndex = fieldName.indexOf("IP");
      if (suffixIndex > 0) {
        // java field "xxxIP" corresponds to C function "xxx"
        String functionName = fieldName.substring(0, suffixIndex);
        if (vmBool("BuildForAix") 
            || (vmBool("BuildForLinux") && vmBool("BuildFor64Addr"))) {
          // e. g.,
          // sysFOOIP = ((AixLinkageLayout *)&sysFOO)->ip;
          p("  br->" + fieldName + " = ((AixLinkageLayout *)&" + functionName + ")->ip;\n"); 
        } else {
          // e. g.,
          //sysFOOIP = (int) sysFOO; 
          p("  br->" + fieldName + " = (int) " + functionName + ";\n");
        }
      }

      suffixIndex = fieldName.indexOf("TOC");
      if (suffixIndex > 0) {
        // java field "xxxTOC" corresponds to C function "xxx"
        String functionName = fieldName.substring(0, suffixIndex);
        if (vmBool("BuildForAix") 
            || (vmBool("BuildForLinux") && vmBool("BuildFor64Addr"))) {
          // e. g.,
          // sysTOC = ((AixLinkageLayout *)&sys)->toc;
          p("  br->" + fieldName + " = ((AixLinkageLayout *)&" + functionName + ")->toc;\n"); 
        } else {
          p("  br->" + fieldName + " = 0;\n");
        }
      }
    }

    p("}\n");
  }


  // Emit virtual machine class interface information.
  //
  void emitVirtualMachineDeclarations (int bootImageAddress) 
    throws NoSuchFieldException, IllegalAccessException
  {
    // load address for the boot image
    //
    p("static const int bootImageAddress                        = 0x"
        + Integer.toHexString(bootImageAddress) + ";\n");

    // values in VM_Constants, from VM_Configuration
    //
    Class vc = getClassNamed("com.ibm.JikesRVM.VM_Constants");
    IntEmitter i = new IntEmitter(vc, "VM_Constants_");
    // String[] fieldNames;

    //-#if RVM_FOR_POWERPC
    // Trust the preprocessor: 
    //    if (VM.BuildForPowerPC) {
        //Try 2:
        //      i.emit("JTOC_POINTER");
        // Try 1:
      //      p("static const int VM_Constants_JTOC_POINTER               = "
      //          + VM_Constants.JTOC_POINTER + ";\n");

    if (vmBool("BuildForPowerPC")) {
      i.emit(new String[] {
        "JTOC_POINTER", "FRAME_POINTER", "PROCESSOR_REGISTER",
        "FIRST_VOLATILE_GPR", "DIVIDE_BY_ZERO_MASK", "DIVIDE_BY_ZERO_TRAP",
        "MUST_IMPLEMENT_MASK", "MUST_IMPLEMENT_TRAP", "STORE_CHECK_MASK", 
        "STORE_CHECK_TRAP", "ARRAY_INDEX_MASK", "ARRAY_INDEX_TRAP", 
        "ARRAY_INDEX_REG_MASK", "ARRAY_INDEX_REG_SHIFT", 
        "CONSTANT_ARRAY_INDEX_MASK", "CONSTANT_ARRAY_INDEX_TRAP", 
        "CONSTANT_ARRAY_INDEX_INFO", "WRITE_BUFFER_OVERFLOW_MASK", 
        "WRITE_BUFFER_OVERFLOW_TRAP", "STACK_OVERFLOW_MASK",
        "STACK_OVERFLOW_HAVE_FRAME_TRAP", "STACK_OVERFLOW_TRAP", 
        "CHECKCAST_MASK", "CHECKCAST_TRAP", "REGENERATE_MASK", 
        "REGENERATE_TRAP", "NULLCHECK_MASK", "NULLCHECK_TRAP",
        "JNI_STACK_TRAP_MASK", "JNI_STACK_TRAP", 
        "STACKFRAME_NEXT_INSTRUCTION_OFFSET", "STACKFRAME_ALIGNMENT" 
      });
    }
    
    //    }
    //-#endif
    
    //-#if RVM_FOR_IA32
    if (vmBool("BuildForIA32")) {
      i.emit(new String[] {
        "EAX", "ECX", "EDX", "EBX", "ESP", "EBP", 
        "ESI", "EDI", "STACKFRAME_BODY_OFFSET", 
        "STACKFRAME_RETURN_ADDRESS_OFFSET", "RVM_TRAP_BASE" });
    }
    //-#endif
    //    i.emit(fieldNames);

    /* Fields shared among distributions. */
    i.emit(new String[] {
      "STACK_SIZE_GUARD", "INVISIBLE_METHOD_ID",
      "TL_THREAD_ID_SHIFT", "STACKFRAME_HEADER_SIZE",
      "STACKFRAME_METHOD_ID_OFFSET", 
      "STACKFRAME_FRAME_POINTER_OFFSET"
    });

    i.emitAddress("STACKFRAME_SENTINEL_FP");

    // values in VM_ObjectModel
    //
    pln("static const int VM_ObjectModel_ARRAY_LENGTH_OFFSET = " + 
                       VM_ObjectModel.getArrayLengthOffset() + "\n;");

    // values in VM_Scheduler
    //
    vc = getClassNamed("com.ibm.JikesRVM.VM_Scheduler");
    i = new IntEmitter(vc, "VM_Scheduler_");
    i.emit("PRIMORDIAL_PROCESSOR_ID");
    i.emit("PRIMORDIAL_THREAD_INDEX");
    pln();

    // values in VM_ThreadEventConstants
    //
    vc = getClassNamed("com.ibm.JikesRVM.ThreadEventConstants");
    DoubleEmitter d = new DoubleEmitter(vc, "VM_ThreadEventConstants_");
    d.emit("WAIT_INFINITE");

    // values in VM_ThreadIOQueue
    //
    i = new IntEmitter(getClassNamed("com.ibm.JikesRVM.VM_ThreadIOQueue"),
                       "VM_ThreadIOQueue_");
    i.emit("READ_OFFSET");
    i.emit("WRITE_OFFSET");
    i.emit("EXCEPT_OFFSET");
    pln();

    // values in VM_ThreadIOConstants
    //
    
    i = new IntEmitter(getClassNamed("com.ibm.JikesRVM.VM_ThreadIOConstants"),
                       "VM_ThreadIOConstants_");
    i.emit("FD_READY");
    //    p("static const int VM_ThreadIOConstants_FD_READY = " +
    //        VM_ThreadIOConstants.FD_READY + ";\n");
    p("static const int VM_ThreadIOConstants_FD_READY_BIT = " +
        VM_ThreadIOConstants.FD_READY_BIT + ";\n");
    p("static const int VM_ThreadIOConstants_FD_INVALID = " +
        VM_ThreadIOConstants.FD_INVALID + ";\n");
    p("static const int VM_ThreadIOConstants_FD_INVALID_BIT = " +
        VM_ThreadIOConstants.FD_INVALID_BIT + ";\n");
    p("static const int VM_ThreadIOConstants_FD_MASK = " +
        VM_ThreadIOConstants.FD_MASK + ";\n");
    p("\n");

    // values in VM_ThreadProcessWaitQueue
    //
    p("static const int VM_ThreadProcessWaitQueue_PROCESS_FINISHED = " +
        VM_ThreadProcessWaitQueue.PROCESS_FINISHED + ";\n");

    // values in VM_Runtime
    //
    p("static const int VM_Runtime_TRAP_UNKNOWN        = "
        + VM_Runtime.TRAP_UNKNOWN + ";\n");
    p("static const int VM_Runtime_TRAP_NULL_POINTER   = "
        + VM_Runtime.TRAP_NULL_POINTER + ";\n");
    p("static const int VM_Runtime_TRAP_ARRAY_BOUNDS   = "
        + VM_Runtime.TRAP_ARRAY_BOUNDS + ";\n");
    p("static const int VM_Runtime_TRAP_DIVIDE_BY_ZERO = "
        + VM_Runtime.TRAP_DIVIDE_BY_ZERO + ";\n");
    p("static const int VM_Runtime_TRAP_STACK_OVERFLOW = "
        + VM_Runtime.TRAP_STACK_OVERFLOW + ";\n");
    p("static const int VM_Runtime_TRAP_CHECKCAST      = "
        + VM_Runtime.TRAP_CHECKCAST + ";\n");
    p("static const int VM_Runtime_TRAP_REGENERATE     = "
        + VM_Runtime.TRAP_REGENERATE + ";\n");
    p("static const int VM_Runtime_TRAP_JNI_STACK     = "
        + VM_Runtime.TRAP_JNI_STACK + ";\n");
    p("static const int VM_Runtime_TRAP_MUST_IMPLEMENT = "
        + VM_Runtime.TRAP_MUST_IMPLEMENT + ";\n");
    p("static const int VM_Runtime_TRAP_STORE_CHECK = "
        + VM_Runtime.TRAP_STORE_CHECK + ";\n");
    pln();

    // values in VM_FileSystem
    //
    p("static const int VM_FileSystem_OPEN_READ                 = "
        + VM_FileSystem.OPEN_READ + ";\n");
    p("static const int VM_FileSystem_OPEN_WRITE                 = "
        + VM_FileSystem.OPEN_WRITE + ";\n");
    p("static const int VM_FileSystem_OPEN_MODIFY                 = "
        + VM_FileSystem.OPEN_MODIFY + ";\n");
    p("static const int VM_FileSystem_OPEN_APPEND                 = "
        + VM_FileSystem.OPEN_APPEND + ";\n");
    p("static const int VM_FileSystem_SEEK_SET                 = "
        + VM_FileSystem.SEEK_SET + ";\n");
    p("static const int VM_FileSystem_SEEK_CUR                 = "
        + VM_FileSystem.SEEK_CUR + ";\n");
    p("static const int VM_FileSystem_SEEK_END                 = "
        + VM_FileSystem.SEEK_END + ";\n");
    p("static const int VM_FileSystem_STAT_EXISTS                 = "
        + VM_FileSystem.STAT_EXISTS + ";\n");
    p("static const int VM_FileSystem_STAT_IS_FILE                 = "
        + VM_FileSystem.STAT_IS_FILE + ";\n");
    p("static const int VM_FileSystem_STAT_IS_DIRECTORY                 = "
        + VM_FileSystem.STAT_IS_DIRECTORY + ";\n");
    p("static const int VM_FileSystem_STAT_IS_READABLE                 = "
        + VM_FileSystem.STAT_IS_READABLE + ";\n");
    p("static const int VM_FileSystem_STAT_IS_WRITABLE                 = "
        + VM_FileSystem.STAT_IS_WRITABLE + ";\n");
    p("static const int VM_FileSystem_STAT_LAST_MODIFIED                 = "
        + VM_FileSystem.STAT_LAST_MODIFIED + ";\n");
    p("static const int VM_FileSystem_STAT_LENGTH                 = "
        + VM_FileSystem.STAT_LENGTH + ";\n");

    // fields in VM_Processor
    //
    int offset;
    offset = VM_Entrypoints.threadSwitchRequestedField.getOffset();
    p("static const int VM_Processor_threadSwitchRequested_offset = "
        + offset + ";\n");
    offset = VM_Entrypoints.activeThreadStackLimitField.getOffset();
    offset = VM_Entrypoints.activeThreadStackLimitField.getOffset();
    p("static const int VM_Processor_activeThreadStackLimit_offset = "
                     + offset + ";\n");
    offset = VM_Entrypoints.pthreadIDField.getOffset();
    p("static const int VM_Processor_pthread_id_offset = "
                     + offset + ";\n");
    offset = VM_Entrypoints.epochField.getOffset();
    p("static const int VM_Processor_epoch_offset = "
                     + offset + ";\n");
    offset = VM_Entrypoints.activeThreadField.getOffset();
    p("static const int VM_Processor_activeThread_offset = "
                     + offset + ";\n");
    offset = VM_Entrypoints.threadIdField.getOffset();
    p("static const int VM_Processor_threadId_offset = "
                     + offset + ";\n");
    //-#if RVM_FOR_IA32
    if (vmBool("BuildForIA32")) {
      offset = VM_Entrypoints.framePointerField.getOffset();
      p("static const int VM_Processor_framePointer_offset = "
        + offset + ";\n");
      offset = VM_Entrypoints.jtocField.getOffset();
      p("static const int VM_Processor_jtoc_offset = "
        + offset + ";\n");
      offset = VM_Entrypoints.arrayIndexTrapParamField.getOffset();
      p("static const int VM_Processor_arrayIndexTrapParam_offset = "
        + offset + ";\n");
    }
    //-#endif

    // fields in VM_Thread
    //
    offset = VM_Entrypoints.threadStackField.getOffset();
    p("static const int VM_Thread_stack_offset = " + offset + ";\n");
    offset = VM_Entrypoints.stackLimitField.getOffset();
    p("static const int VM_Thread_stackLimit_offset = " + offset + ";\n");
    offset = VM_Entrypoints.threadHardwareExceptionRegistersField.getOffset();
    p("static const int VM_Thread_hardwareExceptionRegisters_offset = "
                     + offset + ";\n");
    offset = VM_Entrypoints.jniEnvField.getOffset();
    p("static const int VM_Thread_jniEnv_offset = "
                     + offset + ";\n");

    // fields in VM_Registers
    //
    offset = VM_Entrypoints.registersGPRsField.getOffset();
    p("static const int VM_Registers_gprs_offset = " + offset + ";\n");
    offset = VM_Entrypoints.registersFPRsField.getOffset();
    p("static const int VM_Registers_fprs_offset = " + offset + ";\n");
    offset = VM_Entrypoints.registersIPField.getOffset();
    p("static const int VM_Registers_ip_offset = " + offset + ";\n");
    //-#if RVM_FOR_IA32
    if (vmBool("BuildForIA32")) {
      offset = VM_Entrypoints.registersFPField.getOffset();
      p("static const int VM_Registers_fp_offset = " + offset + ";\n");
    }
    //-#endif
    //-#if RVM_FOR_POWERPC
    if (vmBool("BuildForPowerPC")) {
      offset = VM_Entrypoints.registersLRField.getOffset();
      p("static const int VM_Registers_lr_offset = " + offset + ";\n");
    }
    //-#endif

    offset = VM_Entrypoints.registersInUseField.getOffset();
    p("static const int VM_Registers_inuse_offset = " + 
                     offset + ";\n");

    // fields in VM_JNIEnvironment
    offset = VM_Entrypoints.JNIExternalFunctionsField.getOffset();
    p("static const int VM_JNIEnvironment_JNIExternalFunctions_offset = " +
      offset + ";\n");

    // fields in java.net.InetAddress
    //
    offset = VM_Entrypoints.inetAddressAddressField.getOffset();
    p("static const int java_net_InetAddress_address_offset = "
                     + offset + ";\n");
    offset = VM_Entrypoints.inetAddressFamilyField.getOffset();
    p("static const int java_net_InetAddress_family_offset = "
                     + offset + ";\n");

    // fields in java.net.SocketImpl
    //
    offset = VM_Entrypoints.socketImplAddressField.getOffset();
    p("static const int java_net_SocketImpl_address_offset = "
                     + offset + ";\n");
    offset = VM_Entrypoints.socketImplPortField.getOffset();
    p("static const int java_net_SocketImpl_port_offset = "
                     + offset + ";\n");

    // fields in com.ibm.JikesRVM.memoryManagers.JMTk.BasePlan
    offset = VM_Entrypoints.gcStatusField.getOffset();
    p("static const int com_ibm_JikesRVM_memoryManagers_JMTk_BasePlan_gcStatusOffset = "
                     + offset + ";\n");
  }


  // Emit assembler constants.
  //
  void emitAssemblerDeclarations () {

    //-#if RVM_FOR_POWERPC
    if (vmBool("BuildForPowerPC")) {
      //-#if RVM_FOR_OSX
      if (vmBool("BuildForOSX")) {
        pln("#define FP r"   + VM_BaselineConstants.FP);
        pln("#define JTOC r" + VM_BaselineConstants.JTOC);
        pln("#define PROCESSOR_REGISTER r"    + VM_BaselineConstants.PROCESSOR_REGISTER);
        pln("#define S0 r"   + VM_BaselineConstants.S0);
        pln("#define T0 r"   + VM_BaselineConstants.T0);
        pln("#define T1 r"   + VM_BaselineConstants.T1);
        pln("#define T2 r"   + VM_BaselineConstants.T2);
        pln("#define T3 r"   + VM_BaselineConstants.T3);
        pln("#define STACKFRAME_NEXT_INSTRUCTION_OFFSET " + VM_Constants.STACKFRAME_NEXT_INSTRUCTION_OFFSET);
      } else {
        //-#else
        //        if (vmBool("BuildForPowerPC")) {
          pln(".set FP,"   + VM_BaselineConstants.FP);
          pln(".set JTOC," + VM_BaselineConstants.JTOC);
          pln(".set PROCESSOR_REGISTER,"    
              + VM_BaselineConstants.PROCESSOR_REGISTER);
          pln(".set S0,"   + VM_BaselineConstants.S0);
          pln(".set T0,"   + VM_BaselineConstants.T0);
          pln(".set T1,"   + VM_BaselineConstants.T1);
          pln(".set T2,"   + VM_BaselineConstants.T2);
          pln(".set T3,"   + VM_BaselineConstants.T3);
          pln(".set STACKFRAME_NEXT_INSTRUCTION_OFFSET," 
              + VM_Constants.STACKFRAME_NEXT_INSTRUCTION_OFFSET);
          //        }
        if (! vmBool("BuildForAix")) 
          pln(".set T4,"   + (VM_BaselineConstants.T3 + 1));
      }
    }
    //-#endif
    //-#endif

    //-#if RVM_FOR_IA32
    if (vmBool("BuildForIA32")) {
      p("#define JTOC %" 
        + VM_RegisterConstants.GPR_NAMES[VM_BaselineConstants.JTOC]
          + ";\n");
      p("#define PR %"   
        + VM_RegisterConstants.GPR_NAMES[VM_BaselineConstants.ESI]
          + ";\n");
    }
    //-#endif

  }
  /** Return the value of the static field named @param fieldName in the
   * alternate VM class. */ 
  boolean vmBool(String fieldName) 
    //    throws IllegalAccessException
  {
    Field f;
    try {
      f = vmClass.getField(fieldName);
    } catch (NoSuchFieldException e) {
      reportTrouble("Unable to find a boolean field named " + fieldName
                    + "in the VM class", e);
      return false;             // unreached.  Yuck.
    }
    try {
      return f.getBoolean(null);
    } catch (IllegalAccessException e) {
      reportTrouble("Protection error while reading the boolean field named " + fieldName
                    + "in the VM class", e);
      System.exit(-1);          // unreached
      return false;             // ditto
    }
  }
}
