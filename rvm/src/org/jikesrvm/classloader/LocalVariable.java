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
package org.jikesrvm.classloader;

import java.io.DataInputStream;
import java.io.IOException;

/** Internal representation of LocalVariableTable attribute. */
public class LocalVariable {

  /**
   * @param constantPool The constant pool.
   * @param input The input stream.s
   * @return local variable table.
   */
  static LocalVariable[] readRVMLocalVariableTable(int[] constantPool,
      DataInputStream input) throws IOException {
    int table_length = input.readUnsignedShort();
    LocalVariable[] table = new LocalVariable[table_length];
    for(int i =0; i < table_length; i++) {
      int start_pc = input.readUnsignedShort();
      int length = input.readUnsignedShort();
      int name_index = input.readUnsignedShort();
      int descriptor_index = input.readUnsignedShort();
      int index = input.readUnsignedShort();
      Atom name = RVMClass.getUtf(constantPool, name_index);
      Atom desciptor = RVMClass.getUtf(constantPool, descriptor_index);
      table[i] = new LocalVariable(start_pc, length, name, desciptor, index);
    }
    return table;
  }

  /**
   * see JVM SPEC [4.7.9 The LocalVariableTable Attribute]
   */
  private final int start_pc;

  private final int length;

  private final Atom name;

  private final Atom descriptor;

  private final int index;

  private LocalVariable(int start_pc, int length, 
      Atom name, Atom descriptor,int index) {
    this.descriptor = descriptor;
    this.index = index;
    this.length = length;
    this.name = name;
    this.start_pc = start_pc;
  }
    
  /** Getters. */
  public final int getStart_pc() { return start_pc;}
  public final int getLength() { return length; }
  public final Atom getName() { return name;}
  public final Atom getDescriptor() {return descriptor;}
  public final int getIndex() {return index;}
}
