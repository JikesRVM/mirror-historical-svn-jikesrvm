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
package org.mmtk.utility.options;

import static org.mmtk.policy.immix.ImmixConstants.DEFAULT_DEFRAG_HEADROOM;

/**
 */
public class DefragHeadroom extends org.vmutil.options.PagesOption {
  /**
   * Create the option.
   */
  public DefragHeadroom() {
    super(Options.set, "Defrag Headroom",
          "Allow the defragmenter this amount of headroom during defrag.",
          DEFAULT_DEFRAG_HEADROOM);
  }
}