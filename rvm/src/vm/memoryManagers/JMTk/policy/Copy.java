/*
 * (C) Copyright Department of Computer Science,
 *     Australian National University. 2002
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;
import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Magic;

/**
 * This class implements a simple copying allocator/collector.
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
final class Copy extends BasePolicy implements Constants {
  public final static String Id = "$Id$"; 

  /**
   * Trace an object under a copying collection policy.
   * If the object is already copied, the copy is returned.
   * Otherwise, a copy is created and returned.
   * In either case, the object will be marked on return.
   *
   * @param object The object to be copied.
   */
  public static VM_Address traceObject(VM_Address object) {

    int forwardingPtr = CopyingHeader.attemptToForward(object);
    VM_Magic.isync();   // prevent instructions moving infront of attemptToForward

    // Somebody got to it first.
    //
    if (CopyingHeader.stateIsForwardedOrBeingForwarded(forwardingPtr)) {
      while (CopyingHeader.stateIsBeingForwarded(forwardingPtr)) 
	forwardingPtr = CopyingHeader.getForwardingWord(object);
      VM_Magic.isync();  // prevent following instructions from being moved in front of waitloop
      VM_Address newObject = VM_Address.fromInt(forwardingPtr & ~CopyingHeader.GC_FORWARDING_MASK);
      return newObject;
    }
    
    VM_Address newObject = VM_Interface.allocateCopy(object);
    VM_Interface.getPlan().enqueue(newObject);
    return newObject;
  }


}
