/*
 * (C) Copyright Department of Computer Science,
 *     Australian National University. 2002
 * (C) IBM Corp. 2002
 */
package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;

/**
 * This class implements a memory resource.  The unit of managment for
 * memory resources is the <code>BLOCK</code><p>
 *
 * Instances of this class each manage some number of blocks of
 * memory.
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
final class MemoryResource implements Constants {
  public final static String Id = "$Id$"; 


  ////////////////////////////////////////////////////////////////////////////
  //
  // Public instance methods
  //
  /**
   * Constructor
   */
  MemoryResource() {
    lock = new Lock();
  }

  // XXX Steve, fix this
  //
  public void reset() {
    reserved = 0;
    committed = 0;
    budget = 0;
  }

  /**
   * Acquire a number of blocks from the memory resource.  Poll the
   * memory manager if the number of blocks used exceeds the budget.
   * By default the budget is zero, in which case the memory manager
   * is polled every time a block is requested.
   *
   * @param blocks The number of blocks requested
   */
  public void acquire(int blocks) {
    lock.acquire();
    reserved += blocks;
    if ((committed + blocks) > budget) {
      lock.release();
      VM_Interface.getPlan().poll();
      lock.acquire();
      committed += blocks;
      lock.release();
    } 
    else {
      committed += blocks;
      lock.release();
    }
  }

  /**
   * Release all blocks from the memory resource.
   */
  public void release() {
    release(reserved);
  }

  /**
   * Release a given number of blocks from the memory resource.
   *
   * @param blocks The number of blocks to be released.
   */
  public void release(int blocks) {
    lock.acquire();
    reserved -= blocks;
    committed -= blocks;
    lock.release();
  }

  /**
   * Return the number of reserved blocks
   *
   * @return The number of reserved blocks.
   */
  public int reservedBlocks() {
    return reserved;
  }

  /**
   * Return the number of committed blocks
   *
   * @return The number of committed blocks.
   */
  public int committedBlocks() {
    return committed;
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Instance variables
  //
  private int reserved;
  private int committed;
  private int budget;
  private Lock lock;
}
