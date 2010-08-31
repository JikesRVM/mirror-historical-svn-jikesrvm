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
package org.mmtk.plan.semispace.incremental;

import org.mmtk.plan.*;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.Space;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.Log;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.vm.Lock;
import org.mmtk.vm.VM;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

@Uninterruptible
public class SSMutator extends StopTheWorldMutator {

  static final PreGCFromSpaceLinearSanityScan preGCSanity = new PreGCFromSpaceLinearSanityScan();
  static final PostGCFromSpaceLinearSanityScan postGCSanity = new PostGCFromSpaceLinearSanityScan();

  /****************************************************************************
   * Instance fields
   */
  protected final CopyLocal fromSpaceLocal;
  protected final CopyLocal toSpaceLocal;
  private TraceWriteBuffer toBeScannedLocal;
  private TraceWriteBuffer toBeCopiedLocal;

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Constructor
   */
  public SSMutator() {
    fromSpaceLocal = new CopyLocal();
    toSpaceLocal = new CopyLocal();
    toBeScannedLocal = new TraceWriteBuffer(global().toBeScannedRemset);
    toBeCopiedLocal = new TraceWriteBuffer(global().toBeCopiedRemset);
  }

  /** @return The active global plan as a <code>SS</code> instance. */
  @Inline
  private static SS global() {
    return (SS) VM.activePlan.global();
  }

  /**
   * Called before the MutatorContext is used, but after the context has been
   * fully registered and is visible to collection.
   */
  public void initMutator(int id) {
    super.initMutator(id);
    fromSpaceLocal.rebind(SS.fromSpace()); // later for concurrent termination might want to be to-space
    toSpaceLocal.rebind(SS.toSpace());
  }

  /****************************************************************************
   *
   * Mutator-time allocation
   */

  /**
   * Allocate space (for an object)
   *
   * @param bytes The size of the space to be allocated (in bytes)
   * @param align The requested alignment.
   * @param offset The alignment offset.
   * @param allocator The allocator number to be used for this allocation
   * @param site Allocation site
   * @return The address of the first byte of the allocated region
   */
  @Inline
  public Address alloc(int bytes, int align, int offset, int allocator, int site) {
    if (allocator == SS.ALLOC_SS) {
      Address addy = fromSpaceLocal.alloc(bytes, align, offset);
      if (allocationBarrier) {
        Address toSpaceAddy = toSpaceLocal.alloc(bytes, align, offset);
        ObjectReference fromSpaceRef = addy.plus(16).toObjectReference();// LPJH nasty nasty hack hard coding in value
        ObjectReference toSpaceRef = toSpaceAddy.plus(16).toObjectReference(); // LPJH plain nasty
        ForwardingWord.setReplicatingFP(fromSpaceRef, toSpaceRef);
      }
      return addy;
    }
    else
      return super.alloc(bytes, align, offset, allocator, site);
  }

  /**
   * Perform post-allocation actions.  For many allocators none are
   * required.
   *
   * @param object The newly allocated object
   * @param typeRef The type reference for the instance being created
   * @param bytes The size of the space to be allocated (in bytes)
   * @param allocator The allocator number to be used for this allocation
   */
  @Inline
  public void postAlloc(ObjectReference object, ObjectReference typeRef,
      int bytes, int allocator) {
    if (allocator == SS.ALLOC_SS) return;
    super.postAlloc(object, typeRef, bytes, allocator);
  }

  /**
   * Return the allocator instance associated with a space
   * <code>space</code>, for this plan instance.
   *
   * @param space The space for which the allocator instance is desired.
   * @return The allocator instance associated with this plan instance
   * which is allocating into <code>space</code>, or <code>null</code>
   * if no appropriate allocator can be established.
   */
  public Allocator getAllocatorFromSpace(Space space) {
    if (space == SS.repSpace0 || space == SS.repSpace1)
      return fromSpaceLocal;
    return super.getAllocatorFromSpace(space);
  }

  /****************************************************************************
   *
   * Collection
   */

  /**
   * Perform a per-mutator collection phase.
   *
   * @param phaseId The collection phase to perform
   * @param primary Perform any single-threaded activities using this thread.
   */
  @Inline
  public void collectionPhase(short phaseId, boolean primary) {
    if (phaseId == SS.PREPARE) {
      super.collectionPhase(phaseId, primary);
      if (SS.currentTrace == 1) {
        // first trace
        allocationBarrier = true;
        insertionBarrier = true;
        replicationBarrier = true;
        flushRememberedSets();
        fromSpaceLocal.linearScan(preGCSanity);
      }
      return;
    }

    if (phaseId == SS.RELEASE) {
      super.collectionPhase(phaseId, primary);
      if (SS.currentTrace == 1) {
        // first trace complete turn on replication barrier
        replicationBarrier = true;
      }
      else if (SS.currentTrace == 2) {
        // second trace
        // rebind the allocation bump pointer to the appropriate semispace.
        fromSpaceLocal.rebind(SS.toSpace()); // flip hasn't happened yet
        toSpaceLocal.rebind(SS.fromSpace()); // flip hasn't happened yet
        assertRemsetsFlushed();
      }
      return;
    }
    
    if (phaseId == SS.COMPLETE) {
      super.collectionPhase(phaseId, primary);
      if (SS.currentTrace == 2) {
        // second trace
        allocationBarrier = false;
        insertionBarrier = false;
        replicationBarrier = false;
        fromSpaceLocal.linearScan(postGCSanity);
      }
      return;
    }

    super.collectionPhase(phaseId, primary);
  }


  /****************************************************************************
   *
   * Miscellaneous
   */

  /**
   * Show the status of each of the allocators.
   */
  public final void show() {
    fromSpaceLocal.show();
    toSpaceLocal.show();
    los.show();
    immortal.show();
  }

  /**
   * The mutator is about to be cleaned up, make sure all local data is returned.
   */
  public void deinitMutator() {
    SS.tackOnLock.acquire();
    // Log.writeln("Deiniting mutator thread ", VM.activePlan.mutator().getId());
    // Log.flush();
    SS.deadThreadsBumpPointer.tackOn(fromSpaceLocal); // thread is dying, ensure everything it allocated is still scanable
    SS.tackOnLock.release();
    super.deinitMutator();
  }

  /**
   * Write a boolean. Take appropriate write barrier actions.
   * <p>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new boolean
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void booleanWrite(ObjectReference src, Address slot, boolean value, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.booleanWrite(src, value, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
    }
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
        VM.barriers.booleanWrite(forwarded, value, metaDataA, metaDataB, mode);
      }
    }
  }

  /**
   * A number of booleans are about to be copied from object <code>src</code> to object <code>dst</code> (as in an array copy).
   * Thus, <code>dst</code> is the mutated object. Take appropriate write barrier actions.
   * <p>
   * @param src The source array
   * @param srcOffset The starting source offset
   * @param dst The destination array
   * @param dstOffset The starting destination offset
   * @param bytes The number of bytes to be copied
   * @return True if the update was performed by the barrier, false if left to the caller
   */
  public boolean booleanBulkCopy(ObjectReference src, Offset srcOffset, ObjectReference dst, Offset dstOffset, int bytes) {
    // Not actually called yet - something to optimise later
    return false;
  }

  /**
   * Write a byte. Take appropriate write barrier actions.
   * <p>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new byte
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void byteWrite(ObjectReference src, Address slot, byte value, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.byteWrite(src, value, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
    }
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
        VM.barriers.byteWrite(forwarded, value, metaDataA, metaDataB, mode);
      }
    }
  }

  /**
   * A number of bytes are about to be copied from object <code>src</code> to object <code>dst</code> (as in an array copy). Thus,
   * <code>dst</code> is the mutated object. Take appropriate write barrier actions.
   * <p>
   * @param src The source array
   * @param srcOffset The starting source offset
   * @param dst The destination array
   * @param dstOffset The starting destination offset
   * @param bytes The number of bytes to be copied
   * @return True if the update was performed by the barrier, false if left to the caller
   */
  public boolean byteBulkCopy(ObjectReference src, Offset srcOffset, ObjectReference dst, Offset dstOffset, int bytes) {
    // Not actually called yet - something to optimise later
    return false;
  }

  /**
   * Write a char. Take appropriate write barrier actions.
   * <p>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new char
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void charWrite(ObjectReference src, Address slot, char value, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.charWrite(src, value, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
    }
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
        VM.barriers.charWrite(forwarded, value, metaDataA, metaDataB, mode);
      }
    }
  }

  /**
   * A number of chars are about to be copied from object <code>src</code> to object <code>dst</code> (as in an array copy). Thus,
   * <code>dst</code> is the mutated object. Take appropriate write barrier actions.
   * <p>
   * @param src The source array
   * @param srcOffset The starting source offset
   * @param dst The destination array
   * @param dstOffset The starting destination offset
   * @param bytes The number of bytes to be copied
   * @return True if the update was performed by the barrier, false if left to the caller
   */
  public boolean charBulkCopy(ObjectReference src, Offset srcOffset, ObjectReference dst, Offset dstOffset, int bytes) {
    // Not actually called yet - something to optimise later
    return false;
  }

  /**
   * Write a double. Take appropriate write barrier actions.
   * <p>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new double
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void doubleWrite(ObjectReference src, Address slot, double value, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.doubleWrite(src, value, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
    }
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
        VM.barriers.doubleWrite(forwarded, value, metaDataA, metaDataB, mode);
      }
    }
  }

  /**
   * A number of doubles are about to be copied from object <code>src</code> to object <code>dst</code> (as in an array copy). Thus,
   * <code>dst</code> is the mutated object. Take appropriate write barrier actions.
   * <p>
   * @param src The source array
   * @param srcOffset The starting source offset
   * @param dst The destination array
   * @param dstOffset The starting destination offset
   * @param bytes The number of bytes to be copied
   * @return True if the update was performed by the barrier, false if left to the caller
   */
  public boolean doubleBulkCopy(ObjectReference src, Offset srcOffset, ObjectReference dst, Offset dstOffset, int bytes) {
    // Not actually called yet - something to optimise later
    return false;
  }

  /**
   * Write a float. Take appropriate write barrier actions.
   * <p>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new float
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void floatWrite(ObjectReference src, Address slot, float value, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.floatWrite(src, value, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
    }
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
        VM.barriers.floatWrite(forwarded, value, metaDataA, metaDataB, mode);
      }
    }
  }

  /**
   * A number of floats are about to be copied from object <code>src</code> to object <code>dst</code> (as in an array copy). Thus,
   * <code>dst</code> is the mutated object. Take appropriate write barrier actions.
   * <p>
   * @param src The source array
   * @param srcOffset The starting source offset
   * @param dst The destination array
   * @param dstOffset The starting destination offset
   * @param bytes The number of bytes to be copied
   * @return True if the update was performed by the barrier, false if left to the caller
   */
  public boolean floatBulkCopy(ObjectReference src, Offset srcOffset, ObjectReference dst, Offset dstOffset, int bytes) {
    // Not actually called yet - something to optimise later
    return false;
  }

  /**
   * Write a int. Take appropriate write barrier actions.
   * <p>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new int
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void intWrite(ObjectReference src, Address slot, int value, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.intWrite(src, value, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
    }
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
        VM.barriers.intWrite(forwarded, value, metaDataA, metaDataB, mode);
      }
    }
  }

  /**
   * A number of ints are about to be copied from object <code>src</code> to object <code>dst</code> (as in an array copy). Thus,
   * <code>dst</code> is the mutated object. Take appropriate write barrier actions.
   * <p>
   * @param src The source array
   * @param srcOffset The starting source offset
   * @param dst The destination array
   * @param dstOffset The starting destination offset
   * @param bytes The number of bytes to be copied
   * @return True if the update was performed by the barrier, false if left to the caller
   */
  public boolean intBulkCopy(ObjectReference src, Offset srcOffset, ObjectReference dst, Offset dstOffset, int bytes) {
    // Not actually called yet - something to optimise later
    return false;
  }

  /**
   * Attempt to atomically exchange the value in the given slot with the passed replacement value.
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param old The old int to be swapped out
   * @param value The new int
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   * @return True if the swap was successful.
   */
  public boolean intTryCompareAndSwap(ObjectReference src, Address slot, int old, int value, Word metaDataA, Word metaDataB,
                                      int mode) {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
      if (SS.inFromSpace(slot))
        VM.assertions.fail("Warning attempting intTryCompareAndSwap on object in Sapphire fromSpace");
    }
    return VM.barriers.intTryCompareAndSwap(src, old, value, metaDataA, metaDataB, mode);
  }

  /**
   * Write a long. Take appropriate write barrier actions.
   * <p>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new long
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void longWrite(ObjectReference src, Address slot, long value, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.longWrite(src, value, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
    }
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
        VM.barriers.longWrite(forwarded, value, metaDataA, metaDataB, mode);
      }
    }
  }

  /**
   * A number of longs are about to be copied from object <code>src</code> to object <code>dst</code> (as in an array copy). Thus,
   * <code>dst</code> is the mutated object. Take appropriate write barrier actions.
   * <p>
   * @param src The source array
   * @param srcOffset The starting source offset
   * @param dst The destination array
   * @param dstOffset The starting destination offset
   * @param bytes The number of bytes to be copied
   * @return True if the update was performed by the barrier, false if left to the caller
   */
  public boolean longBulkCopy(ObjectReference src, Offset srcOffset, ObjectReference dst, Offset dstOffset, int bytes) {
    // Not actually called yet - something to optimise later
    return false;
  }

  /**
   * Attempt to atomically exchange the value in the given slot with the passed replacement value.
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param old The old long to be swapped out
   * @param value The new long
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   * @return True if the swap was successful.
   */
  public boolean longTryCompareAndSwap(ObjectReference src, Address slot, long old, long value, Word metaDataA, Word metaDataB,
                                       int mode) {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
      if (SS.inFromSpace(slot))
        VM.assertions.fail("Warning attempting longTryCompareAndSwap on object in Sapphire fromSpace");
    }
    return VM.barriers.longTryCompareAndSwap(src, old, value, metaDataA, metaDataB, mode);
  }

  /**
   * Write a short. Take appropriate write barrier actions.
   * <p>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new short
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void shortWrite(ObjectReference src, Address slot, short value, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.shortWrite(src, value, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
    }
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
        VM.barriers.shortWrite(forwarded, value, metaDataA, metaDataB, mode);
      }
    }
  }

  /**
   * A number of shorts are about to be copied from object <code>src</code> to object <code>dst</code> (as in an array copy). Thus,
   * <code>dst</code> is the mutated object. Take appropriate write barrier actions.
   * <p>
   * @param src The source array
   * @param srcOffset The starting source offset
   * @param dst The destination array
   * @param dstOffset The starting destination offset
   * @param bytes The number of bytes to be copied
   * @return True if the update was performed by the barrier, false if left to the caller
   */
  public boolean shortBulkCopy(ObjectReference src, Offset srcOffset, ObjectReference dst, Offset dstOffset, int bytes) {
    // Not actually called yet - something to optimise later
    return false;
  }

  /**
   * Write a Word. Take appropriate write barrier actions.
   * <p>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new Word
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void wordWrite(ObjectReference src, Address slot, Word value, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.wordWrite(src, value, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
    }
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
        VM.barriers.wordWrite(forwarded, value, metaDataA, metaDataB, mode);
      }
    }
  }

  /**
   * Write a Word during GC into toSpace. Take appropriate write barrier actions.
   * <p>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new Word
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void wordWriteDuringGC(ObjectReference src, Address slot, Word value, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.wordWrite(src, value, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(replicationBarrier);
      VM.assertions._assert(insertionBarrier);
      VM.assertions._assert(allocationBarrier);
    }
    // during GC might have a reference to toSpace, avoid certain assertions
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
          // object is already forwarded, update both copies and return
        VM.barriers.wordWrite(forwarded, value, metaDataA, metaDataB, mode);
        }
    }
  }

  /**
   * Attempt to atomically exchange the value in the given slot with the passed replacement value.
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param old The old long to be swapped out
   * @param value The new long
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   * @return True if the swap was successful.
   */
  public boolean wordTryCompareAndSwap(ObjectReference src, Address slot, Word old, Word value, Word metaDataA, Word metaDataB,
                                       int mode) {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!SS.inToSpace(slot));
      VM.assertions._assert(!SS.inFromSpace(slot), "Warning attempting wordTryCompareAndSwap on object in Sapphire fromSpace");
    }
    return VM.barriers.wordTryCompareAndSwap(src, old, value, metaDataA, metaDataB, mode);
  }

  /**
   * Attempt to atomically exchange the value in the given slot with the passed replacement value.
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param old The old long to be swapped out
   * @param value The new long
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   * @return True if the swap was successful.
   */
  /*
   * Stuff for address based hashing LPJH: nasty quick hack
   */Word HASH_STATE_UNHASHED = Word.zero();
  Word HASH_STATE_HASHED = Word.one().lsh(8); // 0x00000100
  Word HASH_STATE_HASHED_AND_MOVED = Word.fromIntZeroExtend(3).lsh(8); // 0x0000300
  Word HASH_STATE_MASK = HASH_STATE_UNHASHED.or(HASH_STATE_HASHED).or(HASH_STATE_HASHED_AND_MOVED);

  public boolean wordTryCompareAndSwapInLock(ObjectReference src, Address slot, Word old, Word value, Word metaDataA,
                                             Word metaDataB, int mode) {
    // LPJH: rename this and other methods *statusWord
    // does not need to be replicated (used for locking)
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!SS.inToSpace(slot));
    }
    if (SS.inFromSpace(slot)) { // LPJH: optimise later to depend on replicationBarrier (will also have to change lock+hash code)
      // in possibly replicated fromSpace
      // mark object as being forwarded, attempt fromSpace write, if successful and has FP then do forwarded write
      // (preserving hash bits)
      Word debugPrevValue = ForwardingWord.atomicMarkBusy(src);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(SS.inFromSpace(slot));
//        VM.assertions._assert(ForwardingWord.isBusy(src));
      }
      old = old.or(Word.fromIntZeroExtend(ForwardingWord.BUSY));
      value = value.or(Word.fromIntZeroExtend(ForwardingWord.BUSY));
      if (VM.barriers.wordTryCompareAndSwap(src, old, value, metaDataA, metaDataB, mode)) {
        if (VM.VERIFY_ASSERTIONS) {
          VM.assertions._assert(ForwardingWord.isBusy(src));
        }
        // cas succeeded update any replica
        ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
        if (forwarded != null) {
          if (VM.VERIFY_ASSERTIONS) {
            VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
            VM.assertions._assert(!ForwardingWord.isBusy(forwarded)); // toSpace should not be marked busy
            // check that the hash status is correct in replica before we consider rewriting it
            // (ensure hashcode status updates go via this barrier)
            Word fromStatusHashState = VM.objectModel.readAvailableBitsWord(src).and(HASH_STATE_MASK);
            Word toStatusHashState = VM.objectModel.readAvailableBitsWord(forwarded).and(HASH_STATE_MASK);
            if (fromStatusHashState.EQ(HASH_STATE_HASHED)) {
              VM.assertions._assert(toStatusHashState.EQ(HASH_STATE_HASHED_AND_MOVED));
            } else {
              // toSpace might be marked hashed and fromSpace might not be marked at all
            }
          }
          // object is already forwarded, update copy with difference between old and value
          Word diff = old.xor(value);
          Word toSpaceStatusWord = VM.objectModel.readAvailableBitsWord(forwarded);
          VM.barriers.wordWrite(forwarded, toSpaceStatusWord.xor(diff), metaDataA, metaDataB, mode);
          // LPJH: do we need a StoreLoad fence here?
          if (VM.VERIFY_ASSERTIONS) {
            VM.assertions._assert(!ForwardingWord.isBusy(forwarded));
            Word fromStatusHashState = VM.objectModel.readAvailableBitsWord(src).and(HASH_STATE_MASK);
            Word toStatusHashState = VM.objectModel.readAvailableBitsWord(forwarded).and(HASH_STATE_MASK);
            if (fromStatusHashState.EQ(HASH_STATE_HASHED)) {
              VM.assertions._assert(toStatusHashState.EQ(HASH_STATE_HASHED_AND_MOVED));
            } else {
              // toSpace might be marked hashed and fromSpace might not be marked at all
            }
          }
        }
//        if (VM.VERIFY_ASSERTIONS)
//          VM.assertions._assert(ForwardingWord.isBusy(src));
        ForwardingWord.markNotBusy(src, debugPrevValue);
        return true;
      } else {
        // failed to update statusWord, unmark busy state
//        if (VM.VERIFY_ASSERTIONS) {
//          VM.assertions._assert(ForwardingWord.isBusy(src));
//        }
        ForwardingWord.markNotBusy(src, debugPrevValue);
        return false;
      }
    } else {
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(!SS.inToSpace(slot));
        VM.assertions._assert(!SS.inFromSpace(slot));
      }
      // not in replicated space just CAS as normal
      return VM.barriers.wordTryCompareAndSwap(src, old, value, metaDataA, metaDataB, mode);
    }
  }

  /**
   * Write a Address. Take appropriate write barrier actions.
   * <p>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new Address
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void addressWrite(ObjectReference src, Address slot, Address value, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.addressWrite(src, value, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
    }
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
        VM.barriers.addressWrite(forwarded, value, metaDataA, metaDataB, mode);
      }
    }
  }

  /**
   * Write a Extent. Take appropriate write barrier actions.
   * <p>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new Extent
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void extentWrite(ObjectReference src, Address slot, Extent value, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.extentWrite(src, value, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
    }
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
        VM.barriers.extentWrite(forwarded, value, metaDataA, metaDataB, mode);
      }
    }
  }

  /**
   * Write a Offset. Take appropriate write barrier actions.
   * <p>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new Offset
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void offsetWrite(ObjectReference src, Address slot, Offset value, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.offsetWrite(src, value, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
    }
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
        VM.barriers.offsetWrite(forwarded, value, metaDataA, metaDataB, mode);
      }
    }
  }

  /**
   * Read a reference. Take appropriate read barrier action, and return the value that was read.
   * <p>
   * This is a <b>substituting<b> barrier. The call to this barrier takes the place of a load.
   * <p>
   * @param src The object reference holding the field being read.
   * @param slot The address of the slot being read.
   * @param metaDataA A value that assists the host VM in creating a load
   * @param metaDataB A value that assists the host VM in creating a load
   * @param mode The context in which the load occurred
   * @return The reference that was read.
   */
  @Inline
  @Override
  public ObjectReference objectReferenceRead(ObjectReference src, Address slot, Word metaDataA, Word metaDataB, int mode) {
    ObjectReference obj = VM.barriers.objectReferenceRead(src, metaDataA, metaDataB, mode);
    if (VM.VERIFY_ASSERTIONS) {
      if (!obj.isNull())
        VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), obj));
    }
    return obj;
  }

  /**
   * Write an object reference. Take appropriate write barrier actions.
   * <p>
   * <b>By default do nothing, override if appropriate.</b>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param value The value of the new reference
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   */
  public void objectReferenceWrite(ObjectReference src, Address slot, ObjectReference value, Word metaDataA, Word metaDataB,
                                   int mode) {
    VM.barriers.objectReferenceWrite(src, value, metaDataA, metaDataB, mode);
    checkAndEnqueueReference(value);
    if (VM.VERIFY_ASSERTIONS && !SS.gcInProgress()) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
      if (!value.isNull())
        VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), value));
    }
    if (replicationBarrier && SS.inFromSpace(slot)) {
      // writing to an object in Sapphire fromSpace - it might be replicated
      ObjectReference forwarded = ForwardingWord.getReplicatingFP(src);
      if (forwarded != null) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(SS.inToSpace(forwarded.toAddress()));
        VM.barriers.objectReferenceWrite(forwarded, value, metaDataA, metaDataB, mode);
      }
    }
  }

  /**
   * A number of references are about to be copied from object <code>src</code> to object <code>dst</code> (as in an array copy).
   * Thus, <code>dst</code> is the mutated object. Take appropriate write barrier actions.
   * <p>
   * @param src The source array
   * @param srcOffset The starting source offset
   * @param dst The destination array
   * @param dstOffset The starting destination offset
   * @param bytes The number of bytes to be copied
   * @return True if the update was performed by the barrier, false if left to the caller (always false in this case).
   */
  public boolean objectReferenceBulkCopy(ObjectReference src, Offset srcOffset, ObjectReference dst, Offset dstOffset, int bytes) {
    // Not actually called yet - something to optimise later
    return false;
  }

  /**
   * Attempt to atomically exchange the value in the given slot with the passed replacement value. If a new reference is created, we
   * must then take appropriate write barrier actions.
   * <p>
   * <b>By default do nothing, override if appropriate.</b>
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param old The old reference to be swapped out
   * @param tgt The target of the new reference
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The context in which the store occurred
   * @return True if the swap was successful.
   */
  public boolean objectReferenceTryCompareAndSwap(ObjectReference src, Address slot, ObjectReference old, ObjectReference tgt,
                                                  Word metaDataA, Word metaDataB, int mode) {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), slot));
      if (SS.inFromSpace(slot))
        VM.assertions.fail("Warning attempting objectTryCompareAndSwap on object in Sapphire fromSpace");
      if (!tgt.isNull())
        VM.assertions._assert(!Space.isInSpace(SS.toSpace().getDescriptor(), tgt));
    }
    checkAndEnqueueReference(tgt);
    return VM.barriers.objectReferenceTryCompareAndSwap(src, old, tgt, metaDataA, metaDataB, mode);
  }

  /**
   * Flush per-mutator remembered sets into the global remset pool.
   */
  public final void flushRememberedSets() {
    toBeScannedLocal.flush();
    toBeCopiedLocal.flush();
    assertRemsetsFlushed();
  }

  /**
   * Assert that the remsets have been flushed. This is critical to correctness. We need to maintain the invariant that remset
   * entries do not accrue during GC. If the host JVM generates barrier entires it is its own responsibility to ensure that they are
   * flushed before returning to MMTk.
   */
  public final void assertRemsetsFlushed() {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(toBeScannedLocal.isFlushed());
      VM.assertions._assert(toBeCopiedLocal.isFlushed());
    }
  }
  
  /**
   * Process a reference that may require being enqueued as part of a concurrent
   * collection.
   *
   * @param ref The reference to check.
   */
  private void checkAndEnqueueReference(ObjectReference ref) {
//    if (insertionBarrier) {
//      if (!ref.isNull()) {
//        if (Space.isInSpace(SS.fromSpace().getDescriptor(), ref)) SS.repSpace0.traceObject(toBeScannedLocal, ref, SS.ALLOC_SS, true);
//        else if (Space.isInSpace(SS.toSpace().getDescriptor(), ref)) SS.repSpace1.traceObject(toBeScannedLocal, ref, SS.ALLOC_SS, false);
//        else if (Space.isInSpace(SS.IMMORTAL,   ref)) SS.immortalSpace.traceObject(toBeScannedLocal, ref);
//        else if (Space.isInSpace(SS.LOS,        ref)) SS.loSpace.traceObject(toBeScannedLocal, ref);
//        else if (Space.isInSpace(SS.NON_MOVING, ref)) SS.nonMovingSpace.traceObject(toBeScannedLocal, ref);
//        else if (Space.isInSpace(SS.SMALL_CODE, ref)) SS.smallCodeSpace.traceObject(toBeScannedLocal, ref);
//        else if (Space.isInSpace(SS.LARGE_CODE, ref)) SS.largeCodeSpace.traceObject(toBeScannedLocal, ref);
//
//        if (VM.VERIFY_ASSERTIONS) {
//          if (!ref.isNull() && !Plan.gcInProgress()) {
//            if (Space.isInSpace(SS.SS0, ref))             VM.assertions._assert(SS.repSpace0.isLive(ref));
//            else if (Space.isInSpace(SS.SS1, ref))        VM.assertions._assert(SS.repSpace1.isLive(ref));
//            else if (Space.isInSpace(SS.IMMORTAL,   ref)) VM.assertions._assert(SS.immortalSpace.isLive(ref));
//            else if (Space.isInSpace(SS.LOS,        ref)) VM.assertions._assert(SS.loSpace.isLive(ref));
//            else if (Space.isInSpace(SS.NON_MOVING, ref)) VM.assertions._assert(SS.nonMovingSpace.isLive(ref));
//            else if (Space.isInSpace(SS.SMALL_CODE, ref)) VM.assertions._assert(SS.smallCodeSpace.isLive(ref));
//            else if (Space.isInSpace(SS.LARGE_CODE, ref)) VM.assertions._assert(SS.largeCodeSpace.isLive(ref));
//          }
//        }
//      }
//    }
  }
}
