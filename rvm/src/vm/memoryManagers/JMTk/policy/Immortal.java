/*
 * (C) Copyright Department of Computer Science,
 *     Australian National University. 2002
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;
import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_ObjectModel;

import com.ibm.JikesRVM.VM_Scheduler;
import com.ibm.JikesRVM.VM_Uninterruptible;
import com.ibm.JikesRVM.VM_Magic;

/**
 * This class implements collector behavior for a simple immortal
 * collection policy.  Under this policy all that is required is for
 * the "collector" to propogate marks in a liveness trace.  It does
 * not actually collect.
 *
 * @author Perry Cheng
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
final class Immortal extends BasePolicy implements Constants, VM_Uninterruptible {
  public final static String Id = "$Id$"; 


  ////////////////////////////////////////////////////////////////////////////
  //
  // Object header manipulations
  //

  /**
   * test to see if the mark bit has the given value
   */
  static boolean testMarkBit(Object ref, int value) {
    return (VM_ObjectModel.readAvailableBitsWord(ref) & value) != 0;
  }

  /**
   * write the given value in the mark bit.
   */
  static void writeMarkBit(Object ref, int value) {
    int oldValue = VM_ObjectModel.readAvailableBitsWord(ref);
    int newValue = (oldValue & ~GC_MARK_BIT_MASK) | value;
    VM_ObjectModel.writeAvailableBitsWord(ref, newValue);
  }

  /**
   * atomically write the given value in the mark bit.
   */
  static void atomicWriteMarkBit(Object ref, int value) {
    while (true) {
      int oldValue = VM_ObjectModel.prepareAvailableBits(ref);
      int newValue = (oldValue & ~GC_MARK_BIT_MASK) | value;
      if (VM_ObjectModel.attemptAvailableBits(ref, oldValue, newValue)) break;
    }
  }

  /**
   * Used to mark boot image objects during a parallel scan of objects during GC
   * Returns true if marking was done.
   */
  static boolean testAndMark(Object ref, int value) {
    int oldValue;
    do {
      oldValue = VM_ObjectModel.prepareAvailableBits(ref);
      int markBit = oldValue & GC_MARK_BIT_MASK;
      if (markBit == value) return false;
    } while (!VM_ObjectModel.attemptAvailableBits(ref, oldValue, oldValue ^ GC_MARK_BIT_MASK));
    return true;
  }

  static final int GC_MARK_BIT_MASK    = 0x1;
  private static int immortalMarkState = 0x0;

  /**
   * Trace a reference to an object under an immortal collection
   * policy.  If the object is not already marked, enqueue the object
   * for subsequent processing. The object is marked as (an atomic)
   * side-effect of checking whether already marked.
   *
   * @param object The object to be traced.
   */

  public static VM_Address traceObject(VM_Address object) {
    if (testAndMark(object, immortalMarkState)) 
      VM_Interface.getPlan().enqueue(object);
    return object;
  }

  /**
   * Prepare for a new collection increment.  For the immortal
   * collector we must flip the state of the mark bit between
   * collections.
   */
  public static void prepare(VMResource vm, MemoryResource mr) { 
    immortalMarkState = GC_MARK_BIT_MASK - immortalMarkState;
  }

  public static void release(VMResource vm, MemoryResource mr) { 
  }

  public static boolean isLive(VM_Address obj) {
    return true;
  }

}
