/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

/**
 * @author Mauricio J. Serrano
 * @author John Whaley
 */
class OPT_LinkedList {

  /**
   * put your documentation comment here
   */
  OPT_LinkedList () {
  }

  /**
   * put your documentation comment here
   * @param   OPT_LinkedListElement e
   */
  OPT_LinkedList (OPT_LinkedListElement e) {
    start = end = e;
  }

  /**
   * put your documentation comment here
   * @return 
   */
  final public OPT_LinkedListElement first () {
    return  start;
  }

  /**
   * put your documentation comment here
   * @return 
   */
  final public OPT_LinkedListElement last () {
    return  end;
  }

  // append at the end of the list
  final public void append (OPT_LinkedListElement e) {
    if (e == null)
      return;
    OPT_LinkedListElement End = end;
    if (End != null) {
      End.insertAfter(e);
    } 
    else {      // empty list
      start = e;
    }
    end = e;
  }

  // insert at the start of the list
  final public void prepend (OPT_LinkedListElement e) {
    OPT_LinkedListElement Start = start;
    if (Start != null) {
      e.next = Start;
    } 
    else {      // empty list
      end = e;
    }
    // in either case, e is the first node on the list
    start = e;
  }

  // removes the next element from the list
  final public void removeNext (OPT_LinkedListElement e) {
    // update end if needed
    if (end == e.getNext())
      end = null;
    // remove the element
    e.next = e.getNext().getNext();
  }

  // removes the head element from the list
  final public OPT_LinkedListElement removeHead () {
    if (start == null)
      return  null;
    OPT_LinkedListElement result = start;
    start = result.next;
    result.next = null;
    return  result;
  }
  private OPT_LinkedListElement start;
  private OPT_LinkedListElement end;
}



