/*
 * This file is part of the Jikes RVM project (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright IBM Corp. 2002
 */
// $Id$

package com.ibm.JikesRVM;

import org.vmmagic.pragma.*;

/**
 * Visitor class for <code>VM_ThreadEventWaitData</code> objects.
 * Subclasses can recover the actual type of an object from a
 * <code>VM_ThreadEventWaitData</code> reference.
 *
 * @author David Hovemeyer
 */
public abstract class VM_ThreadEventWaitDataVisitor implements Uninterruptible {

  /**
   * Visit a VM_ThreadIOWaitData object.
   */
  public abstract void visitThreadIOWaitData(VM_ThreadIOWaitData waitData);

  /**
   * Visit a VM_ThreadProcessWaitData object.
   */
  public abstract void visitThreadProcessWaitData(VM_ThreadProcessWaitData waitData);

}
