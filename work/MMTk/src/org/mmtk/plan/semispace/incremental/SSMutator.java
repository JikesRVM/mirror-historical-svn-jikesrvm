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
  protected final CopyLocal ss;

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Constructor
   */
  public SSMutator() {
    ss = new CopyLocal();
  }

  /**
   * Called before the MutatorContext is used, but after the context has been
   * fully registered and is visible to collection.
   */
  public void initMutator(int id) {
    super.initMutator(id);
    ss.rebind(SS.fromSpace()); // later for concurrent termination might want to be to-space
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
      Address addy = ss.alloc(bytes, align, offset);
      // Log.write("Allocating... ");
      // Log.write(addy);
      // Log.writeln(" as thread", VM.activePlan.mutator().getId());
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
    if (space == SS.copySpace0 || space == SS.copySpace1) return ss;
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
      ss.linearScan(preGCSanity);
      return;
    }

    if (phaseId == SS.CLOSURE) {
      // Log.writeln("Closure for a mutator context");
      ss.linearScan(SSCollector.linearTrace);
      // Log.writeln("Linear scanned so far: ", SS.linearScannedSoFar);
      return;
    }

    if (phaseId == SS.RELEASE) {
      super.collectionPhase(phaseId, primary);
      // rebind the allocation bump pointer to the appropriate semispace.
      if (SS.copyingAllComplete)
        ss.rebind(SS.toSpace()); // flip hasn't happened yet
      return;
    }
    
    if (phaseId == SS.COMPLETE) {
      super.collectionPhase(phaseId, primary);
      ss.linearScan(postGCSanity);
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
    ss.show();
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
    SS.deadThreadsBumpPointer.tackOn(ss); // thread is dying, ensure everything it allocated is still scanable
    SS.tackOnLock.release();
    super.deinitMutator();
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
    if (!obj.isNull() && (Space.isInSpace(SS.SS0, obj) || Space.isInSpace(SS.SS1, obj)) && ForwardingWord.isForwarded(obj)) {
      Log.writeln("Caught loading a reference to SS0 or SS1 where the object has a forwading pointer");
      Log.write("The caught reference was ");
      Log.write(obj);
      Log.write(" and was loaded from object ");
      Log.write(src);
      if (VM.scanning.pointsToForwardedObjects(src)) {
        Log.write(" which was correctly detected as containing reference to a forwarded object");
      }
      Log.writeln("");
      // VM.assertions.fail("Loaded a stale ref");
    }
    return obj;
  }

  /**
   * A new reference is about to be created. Take appropriate write barrier actions.
   * <p>
   * In this case, we remember the address of the source of the pointer if the new reference points into the nursery from nonnursery
   * space.
   * @param src The object into which the new reference will be stored
   * @param slot The address into which the new reference will be stored.
   * @param tgt The target of the new reference
   * @param metaDataA A value that assists the host VM in creating a store
   * @param metaDataB A value that assists the host VM in creating a store
   * @param mode The mode of the store (eg putfield, putstatic etc)
   */
  @Inline
  public final void objectReferenceWrite(ObjectReference src, Address slot, ObjectReference tgt, Word metaDataA, Word metaDataB,
                                         int mode) {
    if (!tgt.isNull() && (Space.isInSpace(SS.SS0, tgt) || Space.isInSpace(SS.SS1, tgt)) && ForwardingWord.isForwarded(tgt)) {
      Log.writeln("Caught writing a reference to SS0 or SS1 where the tgt is already forwarded");
      Log.write("The caught reference was ");
      Log.write(tgt);
      Log.write(" and it being written into object ");
      Log.writeln(src);
    }
    VM.barriers.objectReferenceWrite(src, tgt, metaDataA, metaDataB, mode);
  }
}
