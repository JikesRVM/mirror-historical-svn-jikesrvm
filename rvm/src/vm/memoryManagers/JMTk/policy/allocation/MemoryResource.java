/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 * All rights reserved.
 */
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
final class MemoryResource implements Constants, Uninterruptible {
  public final static String Id = "$Id$"; 


  ////////////////////////////////////////////////////////////////////////////
  //
  // Public instance methods
  //
  /**
   * Constructor
   */
  MemoryResource() {
  }

  /**
   * Acquire a number of blocks from the memory resource.  Poll the
   * memory manager if the number of blocks used exceeds the budget.
   * By default the budget is zero, in which case the memory manager
   * is polled every time a block is requested.
   *
   * @param blocks The number of blocks requested
   */
  public synchronized void acquire(int blocks) {
    reserved += blocks;
    if ((used + blocks) > budget)
      MM.poll();

    committed += blocks;
  }

  /**
   * Release all blocks from the memory resource.
   */
  public synchronized void release() {
    release(reserved);
  }

  /**
   * Release a given number of blocks from the memory resource.
   *
   * @param blocks The number of blocks to be released.
   */
  public synchronized void release(int blocks) {
    reserved -= blocks;
    committed -= blocks;
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
}
