/*
 * This file is part of Jikes RVM (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright Peter Donald. 2007
 */
package test.org.jikesrvm.basic.stats;

import java.io.File;

/**
 * This "test" just aids in extracting and displaying image sizes so that they can be tracked through the testing framework.
 *
 * @author Peter Donald
 */
public class JikesImageSizes {

  public static void main(String[] args) {
    if( args.length != 3 ) {
      System.err.println("Expect 3 arguments. <RVM.file.image> <RVM.data.image> <RVM.rmap.image>");
      System.exit(1);
    }
    final long code = getFileLength("code", args[0]);
    final long data = getFileLength("data", args[1]);
    final long rmap = getFileLength("rmap", args[2]);
    final long total = code + data + rmap;
    System.out.println("Code Size: " + code);
    System.out.println("Data Size: " + data);
    System.out.println("Rmap Size: " + rmap);
    System.out.println("Total Size: " + total);
  }

  private static long getFileLength(final String name, final String location) {
    final File file = new File(location);
    if( !file.exists() ) {
      System.err.println("Location for " + name + " given as " + location + " does not exist.");
      System.exit(2);
    }
    return file.length();
  }
}
