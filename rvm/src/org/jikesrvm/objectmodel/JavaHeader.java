package org.jikesrvm.objectmodel;

import org.jikesrvm.memorymanagers.mminterface.MM_Constants;
import org.jikesrvm.scheduler.VM_Scheduler;
import org.jikesrvm.VM;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Word;

public class JavaHeader {
  /** Check header assertions */
  static final void _assert(boolean b) {
    if (VM.VerifyAssertions)
      VM._assert(b);
  }

  /** Lay out of status word */
  private static abstract class StatusWordLayout {
    /** @return offset of lock bits within status word */
    abstract int getLockOffset();

    /** @return number of lock bits within status word */
    abstract int getNumLockBits();

    /** @return offset of hash bits within status word */
    abstract int getHashOffset();

    /** @return number of hash bits within status word */
    abstract int getNumHashBits();

    /** @return offset of available bits within status word */
    abstract int getAvailableOffset();

    /** @return number of available bits within status word */
    abstract int getNumAvailableBits();
  }

  /**
   * Layout of the default status word. Jikes RVM uses a 10 bit hash code that
   * is completely stored in the status word, which is laid out as shown below:
   *
   * <pre>
   *      TTTT TTTT TTTT TTTT TTTT HHHH HHHH HHAA
   * T = thin lock bits
   * H = hash code
   * A = available for use by GCHeader and/or MiscHeader.
   * </pre>
   */
  private static final class DefaultStatusWordLayout extends StatusWordLayout {
    /** @return offset of lock bits within status word */
    @Override
    int getLockOffset() {
      return 12;
    }

    /** @return number of lock bits within status word */
    @Override
    int getNumLockBits() {
      return 20;
    }

    /** @return offset of hash bits within status word */
    @Override
    int getHashOffset() {
      return 2;
    }

    /** @return number of hash bits within status word */
    @Override
    int getNumHashBits() {
      return 8;
    }

    /** @return offset of available bits within status word */
    @Override
    int getAvailableOffset() {
      return 0;
    }

    /** @return number of available bits within status word */
    @Override
    int getNumAvailableBits() {
      return 2;
    }
  }

  /**
   * Layout of status word under address based hashing. Jikes RVM uses two bits
   * of the status word to record the hash code state in a typical three state
   * scheme ({@link #HASH_STATE_UNHASHED}, {@link #HASH_STATE_HASHED}, and
   * {@link #HASH_STATE_HASHED_AND_MOVED}). In this case, the status word is
   * laid out as shown below:
   *
   * <pre>
   *      TTTT TTTT TTTT TTTT TTTT TTHH AAAA AAAA
   * T = thin lock bits
   * H = hash code state bits
   * A = available for use by GCHeader and/or MiscHeader.
   * </pre>
   */
  private static final class AddressBasedHashingStatusWordLayout extends
      StatusWordLayout {
    /** @return offset of lock bits within status word */
    @Override
    int getLockOffset() {
      return 10;
    }

    /** @return number of lock bits within status word */
    @Override
    int getNumLockBits() {
      return 22;
    }

    /** @return offset of hash bits within status word */
    @Override
    int getHashOffset() {
      return 8;
    }

    /** @return number of hash bits within status word */
    @Override
    int getNumHashBits() {
      return 2;
    }

    /** @return offset of available bits within status word */
    @Override
    int getAvailableOffset() {
      return 0;
    }

    /** @return number of available bits within status word */
    @Override
    int getNumAvailableBits() {
      return 8;
    }
  }

  /** Lay out of status word */
  private static final StatusWordLayout statusWordLayout = !MM_Constants.GENERATE_GC_TRACE ? new AddressBasedHashingStatusWordLayout()
      : new DefaultStatusWordLayout();

  static {
    _assert(statusWordLayout.getNumLockBits()
        + statusWordLayout.getNumHashBits()
        + statusWordLayout.getNumAvailableBits() == 32);
  }

  /** Utilities to access the status of a lock within the status word */
  @Uninterruptible
  public static abstract class LockStatus {
    /** Mask to give bits in a Word belonging to lock status */
    protected static final Word lockStatusMask = Word.fromIntZeroExtend(((1 << statusWordLayout.getNumLockBits()) - 1) << statusWordLayout.getLockOffset());

    /** Mask to give bits in a Word not belonging to lock status */
    protected static final Word inverseLockStatusMask = lockStatusMask.not();

    /**
     * Is the lock word encoding a heavy weight lock
     *
     * @param lockWord to read from
     * @return whether the lock word encodes a heavy weight lock
     */
    public abstract boolean isHeavyLock(Word lockWord);

    /**
     * Read heavy lock ID
     *
     * @param lockWord to read from
     * @return the thread ID
     */
    public abstract Word readHeavyLockID(Word lockWord);

    /**
     * Set heavy lock ID
     *
     * @param lockWord to read from
     * @param the ID to set
     * @return the update lock word
     */
    public abstract Word setHeavyLockID(Word lockWord, int id);

    /**
     * @return Maximum value for heavy weight lock ID
     */
    public abstract int maxHeavyLockID();

    /**
     * Read the recursion count from the lock word
     *
     * @param lockWord to read from
     * @return the recursion count
     */
    public abstract Word readRecursionCount(Word lockWord);

    /**
     * Increment the recursion count in the lock word
     *
     * @param lockWord to read from
     * @return the changed lock word
     */
    public abstract Word incrementRecursionCount(Word lockWord);

    /**
     * Decrement the recursion count in the lock word
     *
     * @param lockWord to read from
     * @return the changed lock word
     */
    public abstract Word decrementRecursionCount(Word lockWord);

    /**
     * Shift the given threadId so that it may be tested without shifts
     *
     * @param threadId the threadId to shift
     * @return the shifted thread ID
     */
    public abstract int shiftThreadID(int threadId);

    /**
     * Shift the given shifted thread IDback to a regular thread ID
     *
     * @param threadId the threadId to shift
     * @return the shifted thread ID
     */
    public abstract int unshiftThreadID(int threadId);

    /**
     * Set the thread ID in the lock word
     *
     * @param lockWord to read from
     * @param threadId the threadId shifted to be in the correct position
     * @return the updated lockWord
     */
    public abstract Word setShiftedThreadID(Word lockWord, int threadId);

    /**
     * Read the thread ID in the lock word
     *
     * @param lockWord to read from
     * @return the shifted thread ID
     */
    public abstract Word readShiftedThreadID(Word lockWord);

    /**
     * Is the lock status part of the status word all zero?
     *
     * @param lockWord to read from
     * @return whether the status word is all zero
     */
    public boolean isLockStatusZero(Word lockWord) {
      return lockWord.and(lockStatusMask).isZero();
    }

    /**
     * Make the lock status part of the status word zero
     *
     * @param lockWord to read from
     * @return the changed lock word
     */
    public Word zeroLockStatus(Word lockWord) {
      return lockWord.and(inverseLockStatusMask);
    }

    public abstract boolean isShiftedThreadIdOwnerWithoutRecursion(Word lockWord, int threadId);


    public abstract boolean isShiftedThreadIdOwner(Word lockWord, int threadId);
  }

  /**
   * Accessors used to implement thin locks. A portion of a word, either in the
   * object header or in some other location, is used to provide light weight
   * synchronization operations. This class defines how the bits available for
   * thin locks are accessed. Either a lock is in fat state, in which case it
   * looks like 1Z..Z where Z..Z is the id of a heavy lock, or it is in thin
   * state in which case it looks like 0I..IC..C where I is the thread id of the
   * thread that owns the lock and C is the recursion count of the lock.
   */
  @Uninterruptible
  public static class ThinLockStatus extends LockStatus {

    /** Lock type bit's offset */
    private static final int lockTypeShift = statusWordLayout.getNumLockBits() + statusWordLayout.getLockOffset() - 1;

    /** Mask to reveal lock type */
    private static final Word lockTypeMask = Word.fromIntZeroExtend(1 << lockTypeShift);

    /* Information about heavy locks */

    /** Offset of lock ID within status word */
    private static final int heavyLockIdShift = statusWordLayout.getLockOffset();

    /** Number of bits for a heavy weight lock */
    private static final int heavyLockIdBits = statusWordLayout.getNumLockBits() - 1;

    /** Mask to reveal heavy lock ID */
    private static final Word heavyLockIdMask = Word.fromIntZeroExtend(((1 << heavyLockIdBits) - 1) << heavyLockIdShift);

    /* Information about thin locks */

    /** Number of bits required to hold a thread ID */
    private static final int threadIdBits = VM_Scheduler.LOG_MAX_THREADS;
    static {
      _assert(threadIdBits < statusWordLayout.getNumLockBits());
    }

    /** Number of bits available for the recursion count (how many times the lock has been recursively held) */
    private static final int recursionCountBits = statusWordLayout.getNumLockBits() - threadIdBits - 1;

    /** Shift to get to recursion count of how many times thin lock is held by this thread */
    private static final int recursionCountShift = statusWordLayout.getLockOffset();

    /** Shift to get thread ID of holding thread */
    private static final int threadIdShift = recursionCountShift + recursionCountBits;

    /** Mask to reveal thread ID */
    private static final Word threadIdMask = Word.fromIntZeroExtend(((1 << threadIdBits) - 1) << threadIdShift);

    /** Mask to reveal recursion count */
    private static final Word recursionCountMask = Word.fromIntZeroExtend(((1 << recursionCountBits) - 1) << recursionCountShift);

    /** Value to increment recursion count by */
    private static final Word recursionCountIncrement = Word.fromIntZeroExtend(1 << recursionCountShift);

    /**
     * Is the lock word encoding a heavy weight lock
     *
     * @param lockWord to read from
     * @return whether the lock word encodes a heavy weight lock
     */
    @Override
    public boolean isHeavyLock(Word lockWord) {
      return !lockWord.and(lockTypeMask).isZero();
    }

    /**
     * Read the recursion count from the lock word
     *
     * @param lockWord to read from
     * @return the recursion count
     */
    @Override
    public Word readRecursionCount(Word lockWord) {
      return lockWord.and(recursionCountMask).rshl(recursionCountShift);
    }

    /**
     * Increment the recursion count in the lock word
     *
     * @param lockWord to read from
     * @return the changed lock word
     */
    @Override
    public Word incrementRecursionCount(Word lockWord) {
      return lockWord.plus(recursionCountIncrement); // update count
    }

    /**
     * Decrement the recursion count in the lock word
     *
     * @param lockWord to read from
     * @return the changed lock word
     */
    @Override
    public Word decrementRecursionCount(Word lockWord) {
      return lockWord.minus(recursionCountIncrement); // update count
    }

    /**
     * Read heavy lock ID
     *
     * @param lockWord to read from
     * @return the thread ID
     */
    @Override
    public Word readHeavyLockID(Word lockWord) {
      return lockWord.and(heavyLockIdMask).rshl(heavyLockIdShift);
    }

    /**
     * Set heavy lock ID
     *
     * @param lockWord to read from
     * @return the thread ID
     */
    @Override
    public Word setHeavyLockID(Word lockWord, int id) {
      return lockWord.and(heavyLockIdMask.not()).or(Word.fromIntZeroExtend((1 << lockTypeShift) | (id << heavyLockIdShift)));
    }

    /**
     * @return Maximum value for heavy weight lock ID
     */
    @Override
    public int maxHeavyLockID() {
      return (1 << heavyLockIdBits) - 1;
    }

    /**
     * Shift the given thread ID so that it may be tested without shifts
     *
     * @param threadId the threadId to shift
     * @return the shifted thread ID
     */
    @Override
    public int shiftThreadID(int threadId) {
      return threadId << threadIdShift;
    }

    /**
     * Shift the given shifted thread IDback to a regular thread ID
     *
     * @param threadId the threadId to shift
     * @return the shifted thread ID
     */
    @Override
    public int unshiftThreadID(int threadId) {
      return threadId >>> threadIdShift;
    }

    @Override
    public Word setShiftedThreadID(Word lockWord, int threadId) {
      return lockWord.or(Word.fromIntZeroExtend(threadId));
    }

    /**
     * Read the thread ID in the lock word
     *
     * @param lockWord to read from
     * @return the shifted thread ID
     */
    @Override
    public Word readShiftedThreadID(Word lockWord) {
      return lockWord.and(threadIdMask);
    }

    @Override
    public boolean isShiftedThreadIdOwnerWithoutRecursion(Word lockWord, int threadId) {
      // implies that fatbit == 0 && count == 0 && lockid == me
      // return lockWord.and(lockMask).xor(threadId).isZero();
      return lockWord.and(lockStatusMask).EQ(Word.fromIntZeroExtend(threadId));
    }

    @Override
    public boolean isShiftedThreadIdOwner(Word lockWord, int threadId) {
      // implies that fatbit == 0 && lockid == me
      return lockWord.and(threadIdMask.or(lockTypeMask)).EQ(Word.fromIntZeroExtend(threadId));
    }
  }

  public static final LockStatus lockStatus = new ThinLockStatus();
}
