/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

/**
 * Defines header bits and associated utility routines 
 * used by all watson memory managers.
 *
 * @see VM_ObjectModel
 * @see VM_AllocatorHeader
 * 
 * @author David Bacon
 * @author Steve Fink
 * @author Dave Grove
 */
public class VM_CommonAllocatorHeader implements VM_Uninterruptible {

  static final int GC_MARK_BIT_IDX     = 0; // must be lsb of available bits !!!!!!!
  static final int GC_BARRIER_BIT_IDX  = 1;

  static final int GC_MARK_BIT_MASK    = (1 << GC_MARK_BIT_IDX);
  static final int GC_BARRIER_BIT_MASK = (1 << GC_BARRIER_BIT_IDX);

  static final int GC_FORWARDING_MASK  = GC_MARK_BIT_MASK | GC_BARRIER_BIT_MASK;
  static final int GC_BEING_FORWARDED  = GC_BARRIER_BIT_MASK | VM_Collector.MARK_VALUE;
  static final int GC_FORWARDED        = VM_Collector.MARK_VALUE;

  /**
   * How many available bits does the GC header want to use?
   */
  static final int COMMON_REQUESTED_BITS = 2;

  /*
   * Barrier Bit
   */

  /**
   * test to see if the barrier bit is set
   */
  static boolean testBarrierBit(Object ref) {
    return VM_ObjectModel.testAvailableBit(ref, GC_BARRIER_BIT_IDX);
  }

  /**
   * clear the barrier bit (indicates that object is in write buffer)
   */
  static void clearBarrierBit(Object ref) {
    VM_ObjectModel.setAvailableBit(ref, GC_BARRIER_BIT_IDX, false);
  }

  /**
   * set the barrier bit (indicates that object needs to be put in write buffer
   * if a reference is stored into it).
   */
  static void setBarrierBit(Object ref) {
    VM_ObjectModel.setAvailableBit(ref, GC_BARRIER_BIT_IDX, true);
  }



  /*
   * Mark Bit
   */

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
   * used to mark boot image objects during a parallel scan of objects during GC
   */
  static boolean testAndMark(Object base, int value) {
    VM_Magic.pragmaInline();
    int oldValue;
    do {
      oldValue = VM_ObjectModel.prepareAvailableBits(base);
      int markBit = oldValue & GC_MARK_BIT_MASK;
      if (markBit == value) return false;
    } while (!VM_ObjectModel.attemptAvailableBits(base, oldValue, oldValue ^ GC_MARK_BIT_MASK));
    return true;
  }



  /*
   * Forwarding pointers
   */

    
  /**
   * Either return the forwarding pointer 
   * if the object is already forwarded (or being forwarded)
   * or write the bit pattern that indicates that the object is being forwarded
   */
  static ADDRESS attemptToForward(Object base) {
    VM_Magic.pragmaInline();
    int oldValue;
    do {
      oldValue = VM_ObjectModel.prepareAvailableBits(base);
      int markBit = oldValue & GC_MARK_BIT_MASK;
      if (markBit == VM_Collector.MARK_VALUE) return oldValue;
    } while (!VM_ObjectModel.attemptAvailableBits(base, oldValue, oldValue | GC_BEING_FORWARDED));
    return oldValue;
  }

  /**
   * Non-atomic read of forwarding pointer word
   */
  static ADDRESS getForwardingWord(Object base) {
    return VM_ObjectModel.readAvailableBitsWord(base);
  }

  /**
   * Has the object been forwarded?
   */
  static boolean isForwarded(Object base) {
    return stateIsForwarded(getForwardingWord(base));
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
    return VM_Magic.addressAsObject(getForwardingWord(base) & ~GC_FORWARDING_MASK);
  }

  /**
   * Non-atomic write of forwarding pointer word
   * (assumption, thread doing the set has done attempt to forward
   *  and owns the right to copy the object)
   */
  static void setForwardingPointer(Object base, Object ptr) {
    VM_ObjectModel.writeAvailableBitsWord(base, VM_Magic.objectAsAddress(ptr) | GC_FORWARDED);
  }
}
