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

import org.jikesrvm.VM;
import org.vmmagic.pragma.Uninterruptible;

/**
 * An unsynchronized queue data structure for Threads.  The current
 * uses are all where there is some other lock that already protects the
 * queue.
 */
@Uninterruptible
public class ThreadQueue {
  RVMThread head;
  RVMThread tail;
  
  public ThreadQueue() {
  }
  
  public boolean isEmpty() {
    return head==null;
  }
  
  public void enqueue(RVMThread t) {
    if (VM.VerifyAssertions) VM._assert(t.queuedOn==null);
    t.next=null;
    if (tail==null) {
      head=t;
    } else {
      tail.next=t;
    }
    tail=t;
    t.queuedOn=this;
  }
  
  public RVMThread peek() {
    return head;
  }
  
  public RVMThread dequeue() {
    RVMThread result=head;
    if (result!=null) {
      head=result.next;
      if (head==null) {
	tail=null;
      }
      if (VM.VerifyAssertions) VM._assert(result.queuedOn==this);
      result.next=null;
      result.queuedOn=null;
    }
    return result;
  }
  
  /** Private helper.  Gets the next pointer of cur unless cur is null,
      in which case it returns head. */
  private RVMThread getNext(RVMThread cur) {
    if (cur==null) {
      return head;
    } else {
      return cur.next;
    }
  }
  
  /** Private helper.  Sets the next pointer of cur to value unless cur
      is null, in which case it sets head.  Also sets tail as appropraite. */
  private void setNext(RVMThread cur,RVMThread value) {
    if (cur==null) {
      if (tail==head) {
	tail=value;
      }
      head=value;
    } else {
      if (cur==tail) {
	tail=value;
      }
      cur.next=value;
    }
  }
  
  /** Remove the given thread from the queue if the thread is still
      on the queue.  Does nothing (and returns in O(1)) if the thread
      is not on the queue.  Also does nothing (and returns in O(1)) if
      the thread is on a different queue. */
  public boolean remove(RVMThread t) {
    if (t.queuedOn!=this) return false;
    for (RVMThread cur=null;cur!=tail;cur=cur.next) {
      if (getNext(cur)==t) {
	setNext(cur,t.next);
	t.queuedOn=null;
	return true;
      }
    }
    VM._assert(VM.NOT_REACHED);
    return false; // make javac happy
  }
  
  public boolean isQueued(RVMThread t) {
    return t.queuedOn==this;
  }
  
  public void dump() {
    boolean pastFirst = false;
    for (RVMThread t = head; t != null; t = t.next) {
      if (pastFirst) {
        VM.sysWrite(" ");
      }
      t.dump();
      pastFirst = true;
    }
    VM.sysWrite("\n");
  }
}

/* For the emacs weenies in the crowd.
Local Variables:
   c-basic-offset: 2
End:
*/

