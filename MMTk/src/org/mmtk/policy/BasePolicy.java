/*
 * (C) Copyright IBM Corp. 2001
 * (C) Copyright Department of Computer Science,
 *     Australian National University. 2002
 */
/**
 * @author David Bacon
 * @author Steve Fink
 * @author Dave Grove
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 */
public class BasePolicy implements HeaderConstants {
  

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
   * used to mark boot image objects during a parallel scan of objects during GC
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
}
