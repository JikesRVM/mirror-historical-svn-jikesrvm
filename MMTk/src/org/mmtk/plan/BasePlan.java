/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 * All rights reserved.
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.memoryManagers.vmInterface.WorkQueue;
import com.ibm.JikesRVM.memoryManagers.vmInterface.AddressSet;
import com.ibm.JikesRVM.memoryManagers.vmInterface.AddressPairSet;
import com.ibm.JikesRVM.memoryManagers.vmInterface.AddressTripleSet;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_PragmaInterruptible;

/**
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
public abstract class BasePlan implements Constants {

  public final static String Id = "$Id$"; 

  private WorkQueue workQueue;
  private AddressSet values;                 // gray objects
  //  private AddressQueue values;                 // gray objects
  private AddressSet locations;              // locations containing gray objects
  private AddressPairSet interiorLocations;  // interior locations
  // private AddressPairQueue interiorLocations;  // interior locations

  BasePlan() {
    workQueue = new WorkQueue();
    values = new AddressSet(64 * 1024);
    locations = new AddressSet(1024);
    interiorLocations = new AddressPairSet(1024);
  }

  /**
   * The boot method is called by the runtime immediately command-line
   * arguments are available.  Note that allocation must be supported
   * prior to this point because the runtime infrastructure may
   * require allocation in order to parse the command line arguments.
   * For this reason all plans should operate gracefully on the
   * default minimum heap size until the point that boot is called.
   */
  public void boot() {
    heapBlocks = Conversions.MBToBlocks(Options.heapSize);
  }

  protected static boolean gcInProgress = false;    // This flag should be turned on/off by subclasses.

  static public boolean gcInProgress() {
    return gcInProgress;
  }

  private void computeRoots() {
    VM.sysWriteln("computeRoots not implemented");
    VM._assert(false);
  }

  // Add a gray object
  //
  public void enqueue(VM_Address obj) {
    values.push(obj);
  }

  private void processAllWork() {

    while (true) {
      
      while (!values.isEmpty()) 
	traceObject(values.pop());
      
      while (!locations.isEmpty()) 
	traceObjectLocation(locations.pop());
      
      while (!interiorLocations.isEmpty()) {
	VM_Address obj = interiorLocations.pop1();
	VM_Address interiorLoc = interiorLocations.pop2();
	VM_Address interior = VM_Magic.getMemoryAddress(interiorLoc);
	VM_Address newInterior = traceInteriorReference(obj, interior);
	VM_Magic.setMemoryAddress(interiorLoc, newInterior);
      }

      if (values.isEmpty() && locations.isEmpty() && interiorLocations.isEmpty())
	break;
    }

  }

  protected void collect() {
    computeRoots();
    processAllWork();
  }

  public static int getHeapBlocks() { return heapBlocks; }

  private static int heapBlocks;

  public static final long freeMemory() throws VM_PragmaInterruptible {
    VM._assert(false);
    return 0;
  }

  public static final long totalMemory() throws VM_PragmaInterruptible {
    VM._assert(false);
    return 0;
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
    VM_Address obj = VM_Magic.getMemoryAddress(objLoc);
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
    int offset = obj.diff(interiorRef);
    VM_Address newObj = traceObject(obj);
    return newObj.add(offset);
  }

}
