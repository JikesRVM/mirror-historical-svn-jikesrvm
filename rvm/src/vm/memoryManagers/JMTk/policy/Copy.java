/*
 * (C) Copyright Department of Computer Science,
 *     Australian National University. 2002
 */
/**
 * This class implements a simple copying allocator/collector.
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
final class Copy implements Constants extends BasePolicy {
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
    if (!testAndMark(object)) {
      object = copy(object);
      MM.enqueue(object);
    } else
      object = getForwardingPointer(object);

    return object.add(offset);
  }

  /*
   * Forwarding pointers
   */
  static final int GC_FORWARDING_MASK  = GC_FORWARDED | GC_BEING_FORWARDED;

  /**
   * Either return the forwarding pointer 
   * if the object is already forwarded (or being forwarded)
   * or write the bit pattern that indicates that the object is being forwarded
   */
  static int attemptToForward(Object base) throws VM_PragmaInline {
    int oldValue;
    do {
      oldValue = VM_ObjectModel.prepareAvailableBits(base);
      if ((oldValue & GC_FORWARDING_MASK) == GC_FORWARDED) return oldValue;
    } while (!VM_ObjectModel.attemptAvailableBits(base, oldValue, oldValue | GC_BEING_FORWARDED));
    return oldValue;
  }

  /**
   * Non-atomic read of forwarding pointer word
   */
  static int getForwardingWord(Object base) {
    return VM_ObjectModel.readAvailableBitsWord(base);
  }

  /**
   * Has the object been forwarded?
   */
  static boolean isForwarded(Object base) {
    return stateIsForwarded(getForwardingWord(base));
  }

  /**
   * Has the object been forwarded?
   */
  static boolean isBeingForwarded(Object base) {
    return stateIsBeingForwarded(getForwardingWord(base));
  }

  /**
   * is the state of the forwarding word forwarded?
   */
  static boolean stateIsForwarded(int fw) {
    return (fw & GC_FORWARDING_MASK) == GC_FORWARDED;
  }

  /**
   * is the state of the forwarding word being forwarded?
   */
  static boolean stateIsBeingForwarded(int fw) {
    return (fw & GC_FORWARDING_MASK) == GC_BEING_FORWARDED;
  }

  /**
   * is the state of the forwarding word being forwarded?
   */
  static boolean stateIsForwardedOrBeingForwarded(int fw) {
    return (fw & GC_FORWARDED) != 0;
  }

  /**
   * Non-atomic read of forwarding pointer word
   */
  static Object getForwardingPointer(Object base) {
    int forwarded = getForwardingWord(base);
    return VM_Magic.addressAsObject(VM_Address.fromInt(forwarded & ~GC_FORWARDING_MASK));
  }

  /**
   * Non-atomic write of forwarding pointer word
   * (assumption, thread doing the set has done attempt to forward
   *  and owns the right to copy the object)
   */
  static void setForwardingPointer(Object base, Object ptr) {
    VM_ObjectModel.writeAvailableBitsWord(base, VM_Magic.objectAsAddress(ptr).toInt() | GC_FORWARDED);
  }

}
