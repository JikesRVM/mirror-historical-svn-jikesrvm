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
   * Number of bytes between the object reference and the byte immediately
   * after the last byte of the MISC header (which is that highest byte of the 
   * header).
   */
  static final int OBJECT_HEADER_END = -4;
  
  /**
   * Magic number to add to an object reference to get
   * an address which is guarenteed to be inside
   * the memory region allocated to the object.
   */
  static final int OBJECT_PTR_ADJUSTMENT = -8;
  
  /**
   * Offset of the array length field from an object reference (in bytes)
   */
  public static final int ARRAY_LENGTH_OFFSET = -4; 

  /**
   * Offset to array element 0 from an object reference (in bytes)
   */
  public static final int ARRAY_ELEMENT_OFFSET = 0; 

}
