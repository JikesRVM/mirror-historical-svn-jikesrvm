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
package org.jikesrvm.scheduler;

public final class LockConfigs {
  public static final int EagerDeflate = 1;
  public static final int SloppyDeflate = 2;
  public static final int EagerDeflateBiased = 3;
  public static final int EagerDeflateHybrid = 4;
  public static final int SloppyDeflateHybrid = 5;
  public static final int JUC = 6;
  public static final int JUCHybrid = 6;
}



