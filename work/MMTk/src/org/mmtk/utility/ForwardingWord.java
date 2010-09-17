/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.utility;

import org.mmtk.plan.sapphire.Sapphire;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Word;

/**
 * This class provides generic support for object forwarding, which is specific
 * to a few policies that support copying.  The broad idea is two-fold: 1) the
 * two low-order bits of the GC byte (which are also the two low-order bits of
 * the header word) are used to indicate whether an object has been forwarded
 * or is being forwarded, and 2) if an object has been forwarded then the entire
 * header word of the dead object is used to store a pointer to the forwarded
 * pointer.   This is a standard implementation of forwarding.<p>
 *
 * The two lowest order bits are used for object forwarding because forwarding
 * generally must steal the unused two low order bits of the forwarding pointer.
 */
@Uninterruptible
public class ForwardingWord {
  /*
   *  The forwarding process uses three states to deal with a GC race:
   *  1.      !FORWARDED: Unforwarded
   *  2. BEING_FORWARDED: Being forwarded (forwarding is underway)
   *  3.       FORWARDED: Forwarded
   */
  /** If this bit is set, then forwarding of this object is incomplete */
  public static final byte BUSY = 1; // ...01
  /** If this bit is set, then forwarding of this object has commenced */
  private static final byte FORWARDED = 2; // ...10
  /** This mask is used to reveal which state this object is in with respect to forwarding */
  public static final byte FORWARDING_MASK =  3; // ...11

  private static final byte BUSY_MASK = 1; // ..01
  public static final byte FORWARDED_MASK = 2; // ..10

  public static final int FORWARDING_BITS = 2;


  /**
   * Either return the forwarding pointer if the object is already
   * forwarded (or being forwarded) or write the bit pattern that
   * indicates that the object is being forwarded
   *
   * @param object The object to be forwarded
   * @return The forwarding pointer for the object if it has already
   * been forwarded.
   */
  @Inline
  public static Word attemptToForward(ObjectReference object) {
    Word oldValue;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      if ((byte) (oldValue.toInt() & FORWARDING_MASK) != 0)
        return oldValue;
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, oldValue.or(Word.fromIntZeroExtend(BUSY))));
    return oldValue;
  }
  
  @Inline
  public static Word attemptToDirectlyMarkForward(ObjectReference object) {
    Word oldValue;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      if ((byte) (oldValue.toInt() & FORWARDING_MASK) != 0)
        return oldValue;
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, oldValue.or(Word.fromIntZeroExtend(FORWARDED))));
    return oldValue;
  }

  public static Word atomicMarkBusy(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(Sapphire.inFromSpace(object.toAddress()));
    }
    Word oldValue;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      if ((byte) (oldValue.toInt() & BUSY_MASK) == BUSY)
        continue;
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, oldValue.or(Word.fromIntZeroExtend(BUSY))));
    return oldValue.or(Word.fromIntZeroExtend(BUSY));
  }
  
  public static void nonAtomicMarkForwarded(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(Sapphire.inFromSpace(object.toAddress()));
    }
    Word oldValue = VM.objectModel.readAvailableBitsWord(object);
    if (VM.VERIFY_ASSERTIONS)
      VM.assertions._assert(((byte) (oldValue.toInt() & BUSY_MASK) == BUSY));
    VM.objectModel.writeAvailableBitsWord(object, oldValue.or(Word.fromIntZeroExtend(FORWARDED))); // mark forwarded
  }

  public static void markNotBusy(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(Sapphire.inFromSpace(object.toAddress()));
      VM.assertions._assert(isBusy(object));
    }
    Word oldValue = VM.objectModel.readAvailableBitsWord(object);
    VM.objectModel.writeAvailableBitsWord(object, oldValue.and(Word.fromIntZeroExtend(BUSY_MASK).not())); // zero busy mark
  }
  
  public static void markNotBusy(ObjectReference object, Word lastValue) {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(Sapphire.inFromSpace(object.toAddress()));
      if(!isBusy(object)) {
        Log.writeln("Oh dear object should still be marked busy and is not");
        Log.write("Previous value was "); Log.writeln(lastValue);
        Log.write("Current value is "); Log.writeln(VM.objectModel.readAvailableBitsWord(object));
        VM.assertions.fail("DEAD");
      }
    }
    Word oldValue = VM.objectModel.readAvailableBitsWord(object);
    VM.objectModel.writeAvailableBitsWord(object, oldValue.and(Word.fromIntZeroExtend(BUSY_MASK).not())); // zero busy mark
  }

  public static ObjectReference spinAndGetForwardedObject(ObjectReference object, Word statusWord) {
    /* We must wait (spin) if the object is not yet fully forwarded */
    while ((statusWord.toInt() & FORWARDING_MASK) == BUSY)
      statusWord = VM.objectModel.readAvailableBitsWord(object);

    /* Now extract the object reference from the forwarding word and return it */
    if ((statusWord.toInt() & FORWARDED_MASK) == FORWARDED)
      return statusWord.and(Word.fromIntZeroExtend(FORWARDING_MASK).not()).toAddress().toObjectReference();
    else
      return object;
  }

  public static ObjectReference forwardObject(ObjectReference object, int allocator) {
    ObjectReference newObject = VM.objectModel.copy(object, allocator);
    VM.objectModel.writeAvailableBitsWord(object, newObject.toAddress().toWord().or(Word.fromIntZeroExtend(FORWARDED)));
    return newObject;
  }

  /**
   * Non-atomic write of forwarding pointer word (assumption, thread
   * doing the set has done attempt to forward and owns the right to
   * copy the object)
   *
   * @param object The object whose forwarding pointer is to be set
   * @param ptr The forwarding pointer to be stored in the object's
   * forwarding word
   */
  @Inline
  public static void setForwardingPointer(ObjectReference object,
                                           ObjectReference ptr) {
    VM.assertions.fail("Should not be called"); // LPJH: Sapphire debugging
    VM.objectModel.writeAvailableBitsWord(object, ptr.toAddress().toWord().or(Word.fromIntZeroExtend(FORWARDED)));
  }

  @Inline
  public static void setReplicatingFP(ObjectReference fromSpace, ObjectReference toSpace) {
    // busy should be set for us, write FP, cancel busy then set FORWARDED
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(Sapphire.inFromSpace(fromSpace.toAddress()));
      VM.assertions._assert(!Sapphire.inFromSpace(toSpace.toAddress())); // toSpace might be los or some other space
      VM.assertions._assert(isBusy(fromSpace));
      VM.assertions._assert(!isForwarded(fromSpace));
      VM.assertions._assert(!isBusy(toSpace));
      VM.assertions._assert(!isForwarded(toSpace)); // ToSpace should not be marked FORWARDED (or have BP)
    }
    VM.objectModel.writeReplicatingFP(fromSpace, toSpace); // write FP into spare word
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!isForwarded(fromSpace));
      VM.assertions._assert(isBusy(fromSpace));
      VM.assertions._assert(!isBusy(toSpace));
      VM.assertions._assert(!isForwarded(toSpace));
    }
  }

  // public static void setReplicatingBP(ObjectReference fromSpace, ObjectReference toSpace) {
  // // toSpace obj should not have forwarded or busy set
  // if (VM.VERIFY_ASSERTIONS) {
  // VM.assertions._assert(SS.inFromSpace(fromSpace.toAddress()));
  // VM.assertions._assert(!SS.inFromSpace(toSpace.toAddress())); // toSpace might be los or some other space
  // VM.assertions._assert(isBusy(fromSpace));
  // VM.assertions._assert(!isBusy(toSpace)); // toSpace status word should be cleared during postCopy
  // VM.assertions._assert(!isForwarded(toSpace)); // toSpace status word should be cleared during postCopy
  // VM.assertions._assert(!isForwarded(fromSpace)); // not marked forwarded until after postCopy
  // }
  // VM.objectModel.writeReplicatingFP(toSpace, fromSpace); // write BP into spare word
  // Word forwardingWord = VM.objectModel.readAvailableBitsWord(toSpace);
  // VM.objectModel.writeAvailableBitsWord(toSpace, forwardingWord.or(Word.fromIntZeroExtend(FORWARDED))); // mark toSpace as
  // // forwarded
  // if (VM.VERIFY_ASSERTIONS) {
  // VM.assertions._assert(isBusy(fromSpace));
  // VM.assertions._assert(!isBusy(toSpace)); // toSpace status word should be cleared during postCopy
  // VM.assertions._assert(isForwarded(toSpace)); // toSpace status word should be cleared during postCopy
  // VM.assertions._assert(!isForwarded(fromSpace)); // not marked forwarded until after postCopy
  // }
  // }

  @Inline
  public static ObjectReference getReplicatingFP(ObjectReference object) {
    return VM.objectModel.getReplicatingFP(object);
  }

  /**
   * Has an object been forwarded?
   *
   * @param object The object to be checked
   * @return True if the object has been forwarded
   */
  @Inline
  public static boolean isForwarded(ObjectReference object) {
    return (VM.objectModel.readAvailableByte(object) & FORWARDED_MASK) == FORWARDED;
  }

  @Inline
  public static boolean isBusy(ObjectReference object) {
    return (VM.objectModel.readAvailableByte(object) & BUSY_MASK) == BUSY;
  }

  /**
   * Has an object been forwarded or is it being forwarded?
   *
   * @param object The object to be checked
   * @return True if the object has been forwarded
   */
  @Inline
  public static boolean isForwardedOrBeingForwarded(ObjectReference object) {
    return (VM.objectModel.readAvailableByte(object) & FORWARDING_MASK) != 0;
  }

  /**
   * Has an object been forwarded or being forwarded?
   *
   * @param object The object to be checked
   * @return True if the object has been forwarded
   */
  @Inline
  public static boolean stateIsForwardedOrBeingForwarded(Word header) {
    return (header.toInt() & FORWARDING_MASK) != 0;
  }

  /**
   * Has an object been forwarded or being forwarded?
   *
   * @param object The object to be checked
   * @return True if the object has been forwarded
   */
  @Inline
  public static boolean stateIsBeingForwarded(Word header) {
    return (header.toInt() & FORWARDING_MASK) == BUSY;
  }

  /**
   * Clear the GC forwarding portion of the header for an object.
   *
   * @param object the object ref to the storage to be initialized
   */
  @Inline
  public static void clearForwardingBits(ObjectReference object) {
    VM.objectModel.writeAvailableByte(object, (byte) (VM.objectModel.readAvailableByte(object) & ~FORWARDING_MASK));
  }

  @Inline
  public static ObjectReference extractForwardingPointer(Word forwardingWord) {
    return forwardingWord.and(Word.fromIntZeroExtend(FORWARDING_MASK).not()).toAddress().toObjectReference();
  }
}
