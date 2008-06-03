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
package java.nio;

import org.vmmagic.unboxed.Address;

/**
 * Library support interface of Jikes RVM
 */
public class JikesRVMSupport {
  public static Address getDirectBufferAddress(Buffer buffer) {
    throw new Error("TODO");
  }

  public static ByteBuffer newDirectByteBuffer(Address address, long capacity) {
    throw new Error("TODO");
  }
}
