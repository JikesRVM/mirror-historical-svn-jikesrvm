/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 * All rights reserved.
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

/**
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
public abstract class BasePlan implements {  // Constants {

  public final static String Id = "$Id$"; 

  BasePlan() {
    workqueue = new WorkQueue();
  }

  /**
   * The boot method is called by the runtime immediately command-line
   * arguments are available.  Note that allocation must be supported
   * prior to this point because the runtime infrastructure may
   * require allocation in order to parse the command line arguments.
   * For this reason all plans should operate gracefully on the
   * default minimum heap size until the point that boot is called.
   */
  public boot() {
    heapBlocks = Conversion.MBtoBlocks(Options.heapSize);
  }

  private boolean gcInProgress = false;

  static public boolean gcInProgress() {
    return gcInProgress;
  }

  public void collect(PointerIterator remset) {
    gcInProgress = true;
    roots.trace();
    if (remset != null)
      remset.trace();
    workqueue.trace();
    gcInProgress = false;
  }

  public static int getHeapBlocks() { return heapBlocks; }

  private static int heapBlocks_;

  public static final long freeMemory() throws VM_PragmaInterruptible {
    VM._assert(false);
  }

  public static final long totalMemory() throws VM_PragmaInterruptible {
    VM._assert(false);
  }

  
  /**
   * Perform a write barrier operation for the putField bytecode.<p> <b>By default do nothing,
   * override if appropriate.</b>
   *
   * @param srcObj The address of the object containing the pointer to
   * be written to
   * @param offset The offset from srcObj of the slot the pointer is to be written into
   * @param tgt The address to which the source will point
   */
  public void putFieldWriteBarrier(VM_Address srcObj, int offset, VM_Address tgt){
  }

  /**
   * Perform a write barrier operation for the putStatic bytecode.<p> <b>By default do nothing,
   * override if appropriate.</b>
   *
   * @param srcObj The address of the object containing the pointer to
   * be written to
   * @param offset The offset from static table (JTOC) of the slot the pointer is to be written into
   * @param tgt The address to which the source will point
   */
  public void putStaticWriteBarrier(int staticOffset, VM_Address tgt){
  }

  /**
   * Perform a read barrier operation of the getField bytecode.<p> <b>By default do nothing,
   * override if appropriate.</b>
   *
   * @param tgtObj The address of the object containing the pointer
   * about to be read
   * @param offset The offset from tgtObj of the field to be read from
   */
  public void getFieldReadBarrier(VM_Address tgtObj, int offset) {}

  /**
   * Perform a read barrier operation for the getStatic bytecode.<p> <b>By default do nothing,
   * override if appropriate.</b>
   *
   * @param tgtObj The address of the object containing the pointer
   * about to be read
   * @param offset The offset from tgtObj of the field to be read from
   */
  public void getStaticReadBarrier(int staticOffset) {}

  /**
   * Trace a reference during GC.  This involves determining which
   * collection policy applies and calling the appropriate
   * <code>trace</code> method.
   *
   * @param obj The object reference to be traced.  This is <i>NOT</i> an
   * interior pointer.
   * @return The possibly moved reference.
   */
  abstract public VM_Address traceObject(VM_Address obj);

  /**
   * Trace a reference during GC.  This involves determining which
   * collection policy applies and calling the appropriate
   * <code>trace</code> method.
   *
   * @param objLoc The location containing the object reference to be traced.  
   *    The object reference is <i>NOT</i> an interior pointer.
   * @return void
   */
  final public void traceObjectLocation(VM_Address objLoc) {
    VM_Addres obj = VM_Magic.getMemoryAddress(objLoc);
    VM_Address newObj = traceObject(obj);
    VM_Magic.setMemoryAddress(objLoc, newObj);
  }


  /**
   * Trace a reference during GC.  This involves determining which
   * collection policy applies and calling the appropriate
   * <code>trace</code> method.
   *
   * @param obj The object reference to be traced.  This is <i>NOT</i> an
   * interior pointer.
   * @param interiorRef The interior reference inside object ref that must be trace.
   * @return The possibly moved reference.
   */
  final public VM_Address traceInteriorReference(VM_Address obj, VM_Address interiorRef) {
    int offset = obj.diff(reference);
    VM_Address newObj = traceReference(obj);
    return newObj.add(offset);
  }

}
