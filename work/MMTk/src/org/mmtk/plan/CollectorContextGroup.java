/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.plan;

import org.mmtk.utility.Constants;

import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;

/**
 * This class represents a pool of collector contexts that can be triggered
 * to perform collection activity.
 */
@Uninterruptible
public class CollectorContextGroup implements Constants {

  /****************************************************************************
   * Instance fields
   */

  /** The name of this collector context group. */
  private final String name;

  /** The collector context instances operating within this group */
  private CollectorContext[] contexts;
  
  /** The current number of active threads in the group */

  /****************************************************************************
   *
   * Initialization
   */
  protected CollectorContextGroup(String name) {
    this.name = name;
  }
  
  /**
   * Initialize the collector context group.
   *
   * @param size The number of collector contexts within the group.
   */
  public void initGroup(int size) {
    this.contexts = new CollectorContext[size];
    for(int i = 0; i < size; i++) {
     // this.contexts[i] = VM.collection.createCollectorContext();
    }
  }
  
  // need to be able to park / 'wake up' the group (possibly only a subset)
  
  // need to be able to 'rendezvous' within the group  
}
