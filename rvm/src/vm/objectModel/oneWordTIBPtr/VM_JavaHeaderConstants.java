/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$ 

/**
 * Defines other header words not used for 
 * core Java language support of memory allocation.
 * Typically these are extra header words used for various
 * kinds of instrumentation or profiling.
 *
 * @see VM_ObjectModel
 * 
 * @author David Bacon
 * @author Steve Fink
 * @author Dave Grove
 */
public interface VM_JavaHeaderConstants {

  static final int JAVA_HEADER_END = -8;

  static final int ARRAY_LENGTH_OFFSET = -4;

  /** How many bits in the header are available for the GC and MISC headers? */
  static final int NUM_AVAILABLE_BITS = 0;

  static final int HASH_STATE_UNHASHED         = 0;
  static final int HASH_STATE_HASHED           = 0;
  static final int HASH_STATE_HASHED_AND_MOVED = 0;
}
