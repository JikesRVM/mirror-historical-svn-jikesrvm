// $Id$

/**
 * Shared support fuctions for object model implementations that use a
 * lock nursery.
 *
 * @author David Bacon
 * @author Stephen Fink
 * @author Dave Grove
 */
public class VM_NurseryObjectModel implements VM_Uninterruptible, VM_Constants
{

  /**
   * Does an object have a thin lock?
   */
  public static boolean hasThinLock(Object o) { 
    VM_Magic.pragmaInline();  
    return VM_Magic.getObjectType(o).isSynchronized; 
  }

  /**
   * fastPathLocking
   */
  public static void fastPathLock(Object o) { 
    VM_Magic.pragmaInline();
    if (VM.VerifyAssertions) VM.assert(hasThinLock(o));
    VM_ThinLock.inlineLock(o);
  }

  /**
   * fastPathUnlocking
   */
  public static void fastPathUnlock(Object o) { 
    VM_Magic.pragmaInline();
    if (VM.VerifyAssertions) VM.assert(hasThinLock(o));
    VM_ThinLock.inlineUnlock(o);
  }

  /**
   * Generic lock
   */
  public static void genericLock(Object o) { 
    VM_Magic.pragmaInline();
    if (hasThinLock(o)) {
      VM_ThinLock.lock(o);
    } else {
      VM_LockNursery.lock(o);
    }
  }

  /**
   * Generic unlock
   */
  public static void genericUnlock(Object o) {
    VM_Magic.pragmaInline();
    if (hasThinLock(o)) {
      VM_ThinLock.unlock(o);
    } else {
      VM_LockNursery.unlock(o);
    }
  }

  /**
   * Obtains the heavy-weight lock, if there is one, associated with the
   * indicated object.  Returns <code>null</code>, if there is no
   * heavy-weight lock associated with the object.
   *
   * @param o the object from which a lock is desired
   * @param create if true, create heavy lock if none found
   * @return the heavy-weight lock on the object (if any)
   */
  public static VM_Lock getHeavyLock(Object o, boolean create) {
    if (hasThinLock(o)) {
      return VM_ThinLock.getHeavyLock(o, create);
    } else {
      return VM_LockNursery.findOrCreate(o, create);
    }
  }
}
