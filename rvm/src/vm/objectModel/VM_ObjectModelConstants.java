/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

/**
 * Constants exported to the rest of the runtime by the object model.
 *
 * @see VM_ObjectModel
 * 
 * @author David Bacon
 * @author Steve Fink
 * @author Dave Grove
 */
public interface VM_ObjectModelConstants extends VM_TIBLayoutConstants {

  /**
   * Offset of the array length field from an object reference (in bytes)
   */
  public static final int ARRAY_LENGTH_OFFSET = -4; 

  /**
   * Offset to array element 0 from an object reference (in bytes)
   */
  public static final int ARRAY_ELEMENT_OFFSET = 0; 

}
