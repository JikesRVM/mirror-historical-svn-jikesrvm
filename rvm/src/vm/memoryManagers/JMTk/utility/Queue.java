/*
 * (C) Copyright Department of Computer Science,
 *     Australian National University. 2002
 */
package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Uninterruptible;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;

/**
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */ 
public class Queue implements Constatants {
  public final static String Id = "$Id$"; 

  ////////////////////////////////////////////////////////////////////////////
  //
  // Protected instance methods
  //
  protected final int bufferOffset(VM_Address buf) throws VM_PragmaInline {
    return (buf.toInt() & (BUFFER_SIZE - 1));
  }
  protected final VM_Address bufferStart(VM_Address buf) throws VM_PragmaInline {
    return VM_Address.fromInt(buf.toInt & ~(BUFFER_SIZE - 1));
  }

  protected final VM_Address bufferFirst(VM_Address buf) throws VM_PragmaInline {
    return bufferStart(buf);
  }
  protected final VM_Address bufferLast(VM_Address buf, int arity) throws VM_PragmaInline {
    return bufferStart(buf).add(bufferLastOffset(arity));
  }
  protected final VM_Address bufferLast(VM_Address buf) throws VM_PragmaInline {
    return bufferLast(1);
  }
  protected final int bufferLastOffset(int arity) throws VM_PragmaInline {
    return USABLE_BUFFER_BYTES - BYTES_IN_WORD 
      - (USABLE_BUFFER_BYTES % (arity<<LOG_BYTES_IN_WORD));
  }
  protected final int bufferLastOffset() throws VM_PragmaInline {
    return bufferLastOffset(1);
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Private and protected static final fields (aka constants)
  //
  private static final int LOG_BYTES_IN_BUFFER = LOG_BYTES_IN_PAGE;
  protected static final int BYTES_IN_BUFFER = 1<<LOG_BUFFER_SIZE;
  protected static final int NEXT_FIELD_OFFSET = BYTES_IN_WORD;
  protected static final int META_DATA_BYTES = BYTES_IN_WORD;
  private static final int USABLE_BUFFER_BYTES = BYTES_IN_BUFFER-META_DATA_BYTES;
  private static final VM_Address TAIL_INITIAL_VALUE = VM_Address.fromInt(0);
}
