/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 * All rights reserved.
 */
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
final class CollectorImmortal implements Constants extends Collector {
  public final static String Id = "$Id$"; 

  /**
   * Trace a reference to an object under an immortal collection
   * policy.  If the object is not already marked, enqueue the object
   * for subsequent processing. The object is marked as (an atomic)
   * side-effect of checking whether already marked.
   *
   * @param object The object to be traced.
   */
  public static void traceReference(Address object) {
    if (!marked(object)) {
      MM.enqueue(object);
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

  /**
   * Return true if the object is marked, atomically mark the object
   * as a side effect.
   *
   * @param object The object to be marked.
   * @return True if the object was not already marked, false if it
   * was already marked.
   */
  private static boolean marked(Address object) {
    return Runtime.testAndSetMarkBit(object, immortalMarkState);
  }

  private static boolean immortalMarkState;
}
