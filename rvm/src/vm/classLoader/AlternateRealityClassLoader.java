/*
 * (C) Copyright IBM Corp 2002, 2004
 */
//$Id$
package com.ibm.JikesRVM.classloader;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_FileSystem;
import java.io.File;
import java.util.StringTokenizer;
import java.net.URLClassLoader;
import java.net.URL;
import java.net.MalformedURLException;

/**
 * The class loader used by Jikes RVM to load instances of the Jikes RVM
 * and java.* classes, when we want to be able to write out a new boot image
 * from an already running VM.
 *
 * There is only a singleton instance of this class.
 *
 * @author Steven Augart, based on code by Julian Dolby
 */
public class AlternateRealityClassLoader extends URLClassLoader {

  private static String nativeLibDir = null;

  private AlternateRealityClassLoader(String specifiedClassPath,
                                      String specifiedNativeLibDir) {
    super(new URL[0]);
    nativeLibDir = specifiedNativeLibDir;

    final String cp = specifiedClassPath;
    if (cp == null)
      return;                   // all done.  Not very useful, is it?
    try {
      StringTokenizer tok = new StringTokenizer(cp, File.pathSeparator);
      while (tok.hasMoreElements()) {
        String elt = tok.nextToken();
          
        if (!(elt.endsWith(".jar") || elt.endsWith(".zip"))) {
          if (! elt.endsWith( File.separator )) {
            elt += File.separator;
          }
        }
        if (elt.indexOf(":") != -1) {
          addURL(new URL(elt));
        } else if (elt.startsWith(File.separator)) {
          addURL(new URL("file", null, -1, elt));
        } else {
          addURL(new URL("file", null, -1, System.getProperty("user.dir") + File.separator + elt));
        }
      }
    } catch (MalformedURLException e) {
      VM.sysWrite("Error setting the classpath for the AlternateRealityClassLoader " + e);
      VM.sysExit(-1);
    }
  }

  public String toString() { return "AlternateRealityCL"; }

  /* The following code is taken verbatim from ClassLoader.java */
  /**
   * Load a class using this ClassLoader or its parent, possibly resolving
   * it as well using <code>resolveClass()</code>. It first tries to find
   * out if the class has already been loaded through this classloader by
   * calling <code>findLoadedClass()</code>. Then it calls
   * <code>loadClass()</code> on the parent classloader (or when there is
   * no parent it uses the VM bootclassloader)</code>). If the class is still
   * not loaded it tries to create a new class by calling
   * <code>findClass()</code>. Finally when <code>resolve</code> is
   * <code>true</code> it also calls <code>resolveClass()</code> on the
   * newly loaded class.
   *
   * <p>Subclasses are not supposed to  override this method; they should
   * override <code>findClass()</code> which is called by this method.
   *
   * @param name the fully qualified name of the class to load
   * @param resolve whether or not to resolve the class
   * @return the loaded class
   * @throws ClassNotFoundException if the class cannot be found
   */
  protected synchronized Class loadClass(String name, boolean resolve)
    throws ClassNotFoundException
  {
    // Have we already loaded this class?
    Class c = findLoadedClass(name);
    if (c != null)
      return c;

    // Do not delegate up!

    // Still not found, we have to do it ourself.
    c = findClass(name);
    if (resolve)
      resolveClass(c);
    return c;
  }


  private static AlternateRealityClassLoader alternateRealityCL = null;

  /** There is always an alternate reality class loader.  It may be
   * instantiated in such a way that it will do nothing useful, but it is
   * always present. If it weren't, then that would be sad. */
  public static AlternateRealityClassLoader getAlternateRealityClassLoader() {
    if (alternateRealityCL == null)
      init(null, null);
    return alternateRealityCL;
  }

  public static AlternateRealityClassLoader init(
        String specifiedClassPath, String specifiedNativeLibDir) 
    throws IllegalStateException
  {
    if (alternateRealityCL != null)
      throw new IllegalStateException("We already initialized the Alternate Reality Class Loader");
    return alternateRealityCL 
      = new AlternateRealityClassLoader(specifiedClassPath, 
                                        specifiedNativeLibDir);
  }



  protected String findLibrary(String libName) {
    if (nativeLibDir == null)
      throw new Error("This function should never be called; the alternate reality class loader classes shouldn't have any native methods that get invoked.  Should they?");
     String platformLibName = System.mapLibraryName(libName);
     String lib = nativeLibDir + File.separator + platformLibName;
     return VM_FileSystem.access(lib, VM_FileSystem.ACCESS_R_OK) == 0 ? lib : null;
  }
}

                    
