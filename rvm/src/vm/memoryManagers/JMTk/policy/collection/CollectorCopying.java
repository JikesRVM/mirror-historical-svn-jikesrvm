/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 * All rights reserved.
 */
/**
 * This class implements collector behavior for a simple copying collector.
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
final class CopyingCollector implements Constants extends Collector {
  public final static String Id = "$Id$"; 

  /**
   * Trace a reference to an object under a copying collection policy.
   * If the object is not already marked, copy (forward) the object
   * and enqueue the forwarded object for subsequent processing. The
   * object is marked as (an atomic) side-effect of checking whether
   * already marked. If the object was already marked, then find the
   * forwarded object. Establish a new reference by subtracting the
   * offset from the original object and reference from the forwarded
   * object.  Return the new (adjusted) reference.
   *
   * @param reference A reference to be traced, possibly an internal pointer.
   * @param object A reference to the object containing
   * <code>reference</code>.  Normally <code>ref</code> and
   * <code>obj</code> will be identical.  Only in the case where
   * <code>ref</code> is an interior pointer will they differ.
   * @return A reference that has been adjusted to account for the
   * forwarding of the containing object, <code>object</code>.
   */
  public static Address traceReference(Address reference, Address object) {
    Extent offset = object.subtract(reference);
    if (!marked(object)) {
      object = copy(object);
      MM.enqueue(object);
    } else
      object = getForwardedObject(object);

    return object.add(offset);
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
    return Runtime.testAndSetMarkBit(object);
  }
  private static Address getForwardedObject(Address object) {
    return Runtime.getForwardedObject(object);
  }
}
