/*
 * (C) Copyright Department of Computer Science,
 *     Australian National University. 2002
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.VM_Address;

/**
 * This class implements collector behavior for a simple immortal
 * collection policy.  Under this policy all that is required is for
 * the "collector" to propogate marks in a liveness trace.  It does
 * not actually collect.
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
final class Immortal extends BasePolicy implements Constants {
  public final static String Id = "$Id$"; 

  /**
   * Trace a reference to an object under an immortal collection
   * policy.  If the object is not already marked, enqueue the object
   * for subsequent processing. The object is marked as (an atomic)
   * side-effect of checking whether already marked.
   *
   * @param object The object to be traced.
   */
  public static void traceReference(VM_Address object) {
    if (!testAndMark(object, immortalMarkState)) {
      getPlan().enqueue(object);
    }
  }

  /**
   * Prepare for a new collection increment.  For the immortal
   * collector we must flip the state of the mark bit between
   * collections.
   */
  public static void prepare() {
    immortalMarkState = !immortalMarkState;
  }

  private static boolean immortalMarkState;
}
