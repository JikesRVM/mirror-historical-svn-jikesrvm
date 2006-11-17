/*
 * This file is part of Jikes RVM (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright IBM Corp. 2001
 */
//$Id$
package com.ibm.jikesrvm.opt;

/**
 * An <code>IteratorEnumerator</code> converts an <code>Iterator</code>
 * into an <code>Enumeration</code>.
 *
 * @author Stephen Fink
 */
public class OPT_IteratorEnumerator
    implements java.util.Enumeration {
  private final java.util.Iterator i;

  public OPT_IteratorEnumerator(java.util.Iterator i) {
    this.i = i;
  }

  public boolean hasMoreElements() {
    return  i.hasNext();
  }

  public Object nextElement() {
    return  i.next();
  }
}