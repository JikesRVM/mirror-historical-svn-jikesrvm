/*
 * Copyright 1996-2006 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

package java.lang.reflect;

import java.lang.annotation.Annotation;

import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMField;

/**
 * A {@code Field} provides information about, and dynamic access to, a
 * single field of a class or an interface.  The reflected field may
 * be a class (static) field or an instance field.
 *
 * <p>A {@code Field} permits widening conversions to occur during a get or
 * set access operation, but throws an {@code IllegalArgumentException} if a
 * narrowing conversion would occur.
 *
 * @see Member
 * @see java.lang.Class
 * @see java.lang.Class#getFields()
 * @see java.lang.Class#getField(String)
 * @see java.lang.Class#getDeclaredFields()
 * @see java.lang.Class#getDeclaredField(String)
 *
 * @author Kenneth Russell
 * @author Nakul Saraiya
 */
public final class Field extends AccessibleObject implements Member {

  final RVMField vmField;

  /**
   * Package-private constructor used by ReflectAccess to enable
   * instantiation of these objects in Java code from the java.lang
   * package via sun.reflect.LangReflectAccess.
   */
  Field(Class declaringClass, String name, Class type, int modifiers, int slot,
      String signature, byte[] annotations) {
    throw new Error("Not implemented constructor Field " + type.getName());
  }

  /**
   * Package-private routine (exposed to java.lang.Class via
   * ReflectAccess) which returns a copy of this Field. The copy's
   * "root" field points to this Field.
   */
  Field copy() {
    // This routine enables sharing of FieldAccessor objects
    // among Field objects which refer to the same underlying
    // method in the VM. (All of this contortion is only necessary
    // because of the "accessibility" bit in AccessibleObject,
    // which implicitly requires that new java.lang.reflect
    // objects be fabricated for each reflective call on Class
    // objects.)
    throw new Error("Field.copy: TODO");
  }

  /**
   * JikesRVM pacakge constructor
   * @param vmField
   */
  Field(RVMField vmField) {

    this.vmField = vmField;
  }

  /**
   * Returns the {@code Class} object representing the class or interface
   * that declares the field represented by this {@code Field} object.
   */
  public Class<?> getDeclaringClass() {
    return vmField.getClass();
  }

  /**
   * Returns the name of the field represented by this {@code Field} object.
   */
  public String getName() {
    return vmField.getName().toString();
  }

  /**
   * Returns the Java language modifiers for the field represented
   * by this {@code Field} object, as an integer. The {@code Modifier} class should
   * be used to decode the modifiers.
   *
   * @see Modifier
   */
  public int getModifiers() {
    return vmField.getModifiers();
  }

  /**
   * Returns {@code true} if this field represents an element of
   * an enumerated type; returns {@code false} otherwise.
   *
   * @return {@code true} if and only if this field represents an element of
   * an enumerated type.
   * @since 1.5
   */
  public boolean isEnumConstant() {
    return (getModifiers() & Modifier.ENUM) != 0;
  }

  /**
   * Returns {@code true} if this field is a synthetic
   * field; returns {@code false} otherwise.
   *
   * @return true if and only if this field is a synthetic
   * field as defined by the Java Language Specification.
   * @since 1.5
   */
  public boolean isSynthetic() {
    throw new Error("Field.isSynthetic: TODO");
    //return Modifier.isSynthetic(getModifiers());
  }

  /**
   * Returns a {@code Class} object that identifies the
   * declared type for the field represented by this
   * {@code Field} object.
   *
   * @return a {@code Class} object identifying the declared
   * type of the field represented by this object
   */
  public Class<?> getType() {
    return vmField.getType().resolve().getClassForType();
  }

  /**
   * Returns a {@code Type} object that represents the declared type for
   * the field represented by this {@code Field} object.
   *
   * <p>If the {@code Type} is a parameterized type, the
   * {@code Type} object returned must accurately reflect the
   * actual type parameters used in the source code.
   *
   * <p>If the type of the underlying field is a type variable or a
   * parameterized type, it is created. Otherwise, it is resolved.
   *
   * @return a {@code Type} object that represents the declared type for
   *     the field represented by this {@code Field} object
   * @throws GenericSignatureFormatError if the generic field
   *     signature does not conform to the format specified in the Java
   *     Virtual Machine Specification, 3rd edition
   * @throws TypeNotPresentException if the generic type
   *     signature of the underlying field refers to a non-existent
   *     type declaration
   * @throws MalformedParameterizedTypeException if the generic
   *     signature of the underlying field refers to a parameterized type
   *     that cannot be instantiated for any reason
   * @since 1.5
   */
  public Type getGenericType() {
    throw new Error("TODO");
  }

  /**
   * Compares this {@code Field} against the specified object.  Returns
   * true if the objects are the same.  Two {@code Field} objects are the same if
   * they were declared by the same class and have the same name
   * and type.
   */
  public boolean equals(Object obj) {
    if (obj != null && obj instanceof Field) {
      Field other = (Field) obj;
      return (getDeclaringClass() == other.getDeclaringClass())
          && (getName() == other.getName()) 
          && (getType() == other.getType());
    }
    return false;
  }

  /**
   * Returns a hashcode for this {@code Field}.  This is computed as the
   * exclusive-or of the hashcodes for the underlying field's
   * declaring class name and its name.
   */
  public int hashCode() {
    return getDeclaringClass().getName().hashCode() ^ getName().hashCode();
  }

  /**
   * Returns a string describing this {@code Field}.  The format is
   * the access modifiers for the field, if any, followed
   * by the field type, followed by a space, followed by
   * the fully-qualified name of the class declaring the field,
   * followed by a period, followed by the name of the field.
   * For example:
   * <pre>
   *    public static final int java.lang.Thread.MIN_PRIORITY
   *    private int java.io.FileDescriptor.fd
   * </pre>
   *
   * <p>The modifiers are placed in canonical order as specified by
   * "The Java Language Specification".  This is {@code public},
   * {@code protected} or {@code private} first, and then other
   * modifiers in the following order: {@code static}, {@code final},
   * {@code transient}, {@code volatile}.
   */
  public String toString() {
    int mod = getModifiers();
    return (((mod == 0) ? "" : (Modifier.toString(mod) + " ")) 
        + getTypeName(getType()) + " " 
        + getTypeName(getDeclaringClass()) + "." + getName());
  }

  /**
   * Returns a string describing this {@code Field}, including
   * its generic type.  The format is the access modifiers for the
   * field, if any, followed by the generic field type, followed by
   * a space, followed by the fully-qualified name of the class
   * declaring the field, followed by a period, followed by the name
   * of the field.
   *
   * <p>The modifiers are placed in canonical order as specified by
   * "The Java Language Specification".  This is {@code public},
   * {@code protected} or {@code private} first, and then other
   * modifiers in the following order: {@code static}, {@code final},
   * {@code transient}, {@code volatile}.
   *
   * @return a string describing this {@code Field}, including
   * its generic type
   *
   * @since 1.5
   */
  public String toGenericString() {
    int mod = getModifiers();
    Type fieldType = getGenericType();
    return (((mod == 0) ? "" : (Modifier.toString(mod) + " "))
        + ((fieldType instanceof Class) ? getTypeName((Class) fieldType)
            : fieldType.toString()) + " " + getTypeName(getDeclaringClass())
        + "." + getName());
  }

  /**
   * Returns the value of the field represented by this {@code Field}, on
   * the specified object. The value is automatically wrapped in an
   * object if it has a primitive type.
   *
   * <p>The underlying field's value is obtained as follows:
   *
   * <p>If the underlying field is a static field, the {@code obj} argument
   * is ignored; it may be null.
   *
   * <p>Otherwise, the underlying field is an instance field.  If the
   * specified {@code obj} argument is null, the method throws a
   * {@code NullPointerException}. If the specified object is not an
   * instance of the class or interface declaring the underlying
   * field, the method throws an {@code IllegalArgumentException}.
   *
   * <p>If this {@code Field} object enforces Java language access control, and
   * the underlying field is inaccessible, the method throws an
   * {@code IllegalAccessException}.
   * If the underlying field is static, the class that declared the
   * field is initialized if it has not already been initialized.
   *
   * <p>Otherwise, the value is retrieved from the underlying instance
   * or static field.  If the field has a primitive type, the value
   * is wrapped in an object before being returned, otherwise it is
   * returned as is.
   *
   * <p>If the field is hidden in the type of {@code obj},
   * the field's value is obtained according to the preceding rules.
   *
   * @param obj object from which the represented field's value is
   * to be extracted
   * @return the value of the represented field in object
   * {@code obj}; primitive values are wrapped in an appropriate
   * object before being returned
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not an
   *              instance of the class or interface declaring the underlying
   *              field (or a subclass or implementor thereof).
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   */
  public Object get(Object obj) throws IllegalArgumentException,
      IllegalAccessException {
    return VMCommonLibrarySupport.get(obj, vmField, this, 
        RVMClass.getClassFromStackFrame(1));
  }

  /**
   * Gets the value of a static or instance {@code boolean} field.
   *
   * @param obj the object to extract the {@code boolean} value
   * from
   * @return the value of the {@code boolean} field
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not
   *              an instance of the class or interface declaring the
   *              underlying field (or a subclass or implementor
   *              thereof), or if the field value cannot be
   *              converted to the type {@code boolean} by a
   *              widening conversion.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#get
   */
  public boolean getBoolean(Object obj) throws IllegalArgumentException,
      IllegalAccessException {
    return VMCommonLibrarySupport.getBoolean(obj, vmField, this, 
        RVMClass.getClassFromStackFrame(1));
  }

  /**
   * Gets the value of a static or instance {@code byte} field.
   *
   * @param obj the object to extract the {@code byte} value
   * from
   * @return the value of the {@code byte} field
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not
   *              an instance of the class or interface declaring the
   *              underlying field (or a subclass or implementor
   *              thereof), or if the field value cannot be
   *              converted to the type {@code byte} by a
   *              widening conversion.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#get
   */
  public byte getByte(Object obj) throws IllegalArgumentException,
      IllegalAccessException {
    return VMCommonLibrarySupport.getByte(obj, vmField, this, 
        RVMClass.getClassFromStackFrame(1));
  }

  /**
   * Gets the value of a static or instance field of type
   * {@code char} or of another primitive type convertible to
   * type {@code char} via a widening conversion.
   *
   * @param obj the object to extract the {@code char} value
   * from
   * @return the value of the field converted to type {@code char}
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not
   *              an instance of the class or interface declaring the
   *              underlying field (or a subclass or implementor
   *              thereof), or if the field value cannot be
   *              converted to the type {@code char} by a
   *              widening conversion.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see Field#get
   */
  public char getChar(Object obj) throws IllegalArgumentException,
      IllegalAccessException {
    return VMCommonLibrarySupport.getChar(obj, vmField, this, 
        RVMClass.getClassFromStackFrame(1));
  }

  /**
   * Gets the value of a static or instance field of type
   * {@code short} or of another primitive type convertible to
   * type {@code short} via a widening conversion.
   *
   * @param obj the object to extract the {@code short} value
   * from
   * @return the value of the field converted to type {@code short}
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not
   *              an instance of the class or interface declaring the
   *              underlying field (or a subclass or implementor
   *              thereof), or if the field value cannot be
   *              converted to the type {@code short} by a
   *              widening conversion.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#get
   */
  public short getShort(Object obj) throws IllegalArgumentException,
      IllegalAccessException {
    return VMCommonLibrarySupport.getShort(obj, vmField, this, 
        RVMClass.getClassFromStackFrame(1));
  }

  /**
   * Gets the value of a static or instance field of type
   * {@code int} or of another primitive type convertible to
   * type {@code int} via a widening conversion.
   *
   * @param obj the object to extract the {@code int} value
   * from
   * @return the value of the field converted to type {@code int}
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not
   *              an instance of the class or interface declaring the
   *              underlying field (or a subclass or implementor
   *              thereof), or if the field value cannot be
   *              converted to the type {@code int} by a
   *              widening conversion.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#get
   */
  public int getInt(Object obj) throws IllegalArgumentException,
      IllegalAccessException {
    return VMCommonLibrarySupport.getInt(obj, vmField, this, 
        RVMClass.getClassFromStackFrame(1));
  }

  /**
   * Gets the value of a static or instance field of type
   * {@code long} or of another primitive type convertible to
   * type {@code long} via a widening conversion.
   *
   * @param obj the object to extract the {@code long} value
   * from
   * @return the value of the field converted to type {@code long}
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not
   *              an instance of the class or interface declaring the
   *              underlying field (or a subclass or implementor
   *              thereof), or if the field value cannot be
   *              converted to the type {@code long} by a
   *              widening conversion.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#get
   */
  public long getLong(Object obj) throws IllegalArgumentException,
      IllegalAccessException {
    return VMCommonLibrarySupport.getLong(obj, vmField, this, 
        RVMClass.getClassFromStackFrame(1));
  }

  /**
   * Gets the value of a static or instance field of type
   * {@code float} or of another primitive type convertible to
   * type {@code float} via a widening conversion.
   *
   * @param obj the object to extract the {@code float} value
   * from
   * @return the value of the field converted to type {@code float}
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not
   *              an instance of the class or interface declaring the
   *              underlying field (or a subclass or implementor
   *              thereof), or if the field value cannot be
   *              converted to the type {@code float} by a
   *              widening conversion.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see Field#get
   */
  public float getFloat(Object obj) throws IllegalArgumentException,
      IllegalAccessException {
    return VMCommonLibrarySupport.getFloat(obj, vmField, this, 
        RVMClass.getClassFromStackFrame(1));
  }

  /**
   * Gets the value of a static or instance field of type
   * {@code double} or of another primitive type convertible to
   * type {@code double} via a widening conversion.
   *
   * @param obj the object to extract the {@code double} value
   * from
   * @return the value of the field converted to type {@code double}
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not
   *              an instance of the class or interface declaring the
   *              underlying field (or a subclass or implementor
   *              thereof), or if the field value cannot be
   *              converted to the type {@code double} by a
   *              widening conversion.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#get
   */
  public double getDouble(Object obj) throws IllegalArgumentException,
      IllegalAccessException {
    return VMCommonLibrarySupport.getDouble(obj, vmField, this, 
        RVMClass.getClassFromStackFrame(1));
  }

  /**
   * Sets the field represented by this {@code Field} object on the
   * specified object argument to the specified new value. The new
   * value is automatically unwrapped if the underlying field has a
   * primitive type.
   *
   * <p>The operation proceeds as follows:
   *
   * <p>If the underlying field is static, the {@code obj} argument is
   * ignored; it may be null.
   *
   * <p>Otherwise the underlying field is an instance field.  If the
   * specified object argument is null, the method throws a
   * {@code NullPointerException}.  If the specified object argument is not
   * an instance of the class or interface declaring the underlying
   * field, the method throws an {@code IllegalArgumentException}.
   *
   * <p>If this {@code Field} object enforces Java language access control, and
   * the underlying field is inaccessible, the method throws an
   * {@code IllegalAccessException}.
   *
   * <p>If the underlying field is final, the method throws an
   * {@code IllegalAccessException} unless
   * {@code setAccessible(true)} has succeeded for this field
   * and this field is non-static. Setting a final field in this way
   * is meaningful only during deserialization or reconstruction of
   * instances of classes with blank final fields, before they are
   * made available for access by other parts of a program. Use in
   * any other context may have unpredictable effects, including cases
   * in which other parts of a program continue to use the original
   * value of this field.
   *
   * <p>If the underlying field is of a primitive type, an unwrapping
   * conversion is attempted to convert the new value to a value of
   * a primitive type.  If this attempt fails, the method throws an
   * {@code IllegalArgumentException}.
   *
   * <p>If, after possible unwrapping, the new value cannot be
   * converted to the type of the underlying field by an identity or
   * widening conversion, the method throws an
   * {@code IllegalArgumentException}.
   *
   * <p>If the underlying field is static, the class that declared the
   * field is initialized if it has not already been initialized.
   *
   * <p>The field is set to the possibly unwrapped and widened new value.
   *
   * <p>If the field is hidden in the type of {@code obj},
   * the field's value is set according to the preceding rules.
   *
   * @param obj the object whose field should be modified
   * @param value the new value for the field of {@code obj}
   * being modified
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not an
   *              instance of the class or interface declaring the underlying
   *              field (or a subclass or implementor thereof),
   *              or if an unwrapping conversion fails.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   */
  public void set(Object obj, Object value) throws IllegalArgumentException,
      IllegalAccessException {
    VMCommonLibrarySupport.set(obj, value, vmField, this, 
        RVMClass.getClassFromStackFrame(1));
  }

  /**
   * Sets the value of a field as a {@code boolean} on the specified object.
   * This method is equivalent to
   * {@code set(obj, zObj)},
   * where {@code zObj} is a {@code Boolean} object and
   * {@code zObj.booleanValue() == z}.
   *
   * @param obj the object whose field should be modified
   * @param z   the new value for the field of {@code obj}
   * being modified
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not an
   *              instance of the class or interface declaring the underlying
   *              field (or a subclass or implementor thereof),
   *              or if an unwrapping conversion fails.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#set
   */
  public void setBoolean(Object obj, boolean z)
      throws IllegalArgumentException, IllegalAccessException {
    VMCommonLibrarySupport.setBoolean(obj, z, vmField, this, 
        RVMClass.getClassFromStackFrame(1));
  }

  /**
   * Sets the value of a field as a {@code byte} on the specified object.
   * This method is equivalent to
   * {@code set(obj, bObj)},
   * where {@code bObj} is a {@code Byte} object and
   * {@code bObj.byteValue() == b}.
   *
   * @param obj the object whose field should be modified
   * @param b   the new value for the field of {@code obj}
   * being modified
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not an
   *              instance of the class or interface declaring the underlying
   *              field (or a subclass or implementor thereof),
   *              or if an unwrapping conversion fails.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#set
   */
  public void setByte(Object obj, byte b) throws IllegalArgumentException,
      IllegalAccessException {
    VMCommonLibrarySupport.setByte(obj, b, vmField, this, 
        RVMClass.getClassFromStackFrame(1));
  }

  /**
   * Sets the value of a field as a {@code char} on the specified object.
   * This method is equivalent to
   * {@code set(obj, cObj)},
   * where {@code cObj} is a {@code Character} object and
   * {@code cObj.charValue() == c}.
   *
   * @param obj the object whose field should be modified
   * @param c   the new value for the field of {@code obj}
   * being modified
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not an
   *              instance of the class or interface declaring the underlying
   *              field (or a subclass or implementor thereof),
   *              or if an unwrapping conversion fails.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#set
   */
  public void setChar(Object obj, char c) throws IllegalArgumentException,
      IllegalAccessException {
    VMCommonLibrarySupport.setChar(obj, c, vmField, this, RVMClass
        .getClassFromStackFrame(1));
  }

  /**
   * Sets the value of a field as a {@code short} on the specified object.
   * This method is equivalent to
   * {@code set(obj, sObj)},
   * where {@code sObj} is a {@code Short} object and
   * {@code sObj.shortValue() == s}.
   *
   * @param obj the object whose field should be modified
   * @param s   the new value for the field of {@code obj}
   * being modified
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not an
   *              instance of the class or interface declaring the underlying
   *              field (or a subclass or implementor thereof),
   *              or if an unwrapping conversion fails.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#set
   */
  public void setShort(Object obj, short s) throws IllegalArgumentException,
      IllegalAccessException {
    VMCommonLibrarySupport.setShort(obj, s, vmField, this, RVMClass
        .getClassFromStackFrame(1));
  }

  /**
   * Sets the value of a field as an {@code int} on the specified object.
   * This method is equivalent to
   * {@code set(obj, iObj)},
   * where {@code iObj} is a {@code Integer} object and
   * {@code iObj.intValue() == i}.
   *
   * @param obj the object whose field should be modified
   * @param i   the new value for the field of {@code obj}
   * being modified
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not an
   *              instance of the class or interface declaring the underlying
   *              field (or a subclass or implementor thereof),
   *              or if an unwrapping conversion fails.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#set
   */
  public void setInt(Object obj, int i) throws IllegalArgumentException,
      IllegalAccessException {
    VMCommonLibrarySupport.setInt(obj, i, vmField, this, RVMClass
        .getClassFromStackFrame(1));
  }

  /**
   * Sets the value of a field as a {@code long} on the specified object.
   * This method is equivalent to
   * {@code set(obj, lObj)},
   * where {@code lObj} is a {@code Long} object and
   * {@code lObj.longValue() == l}.
   *
   * @param obj the object whose field should be modified
   * @param l   the new value for the field of {@code obj}
   * being modified
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not an
   *              instance of the class or interface declaring the underlying
   *              field (or a subclass or implementor thereof),
   *              or if an unwrapping conversion fails.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#set
   */
  public void setLong(Object obj, long l) throws IllegalArgumentException,
      IllegalAccessException {
    VMCommonLibrarySupport.setLong(obj, l, vmField, this, RVMClass
        .getClassFromStackFrame(1));
  }

  /**
   * Sets the value of a field as a {@code float} on the specified object.
   * This method is equivalent to
   * {@code set(obj, fObj)},
   * where {@code fObj} is a {@code Float} object and
   * {@code fObj.floatValue() == f}.
   *
   * @param obj the object whose field should be modified
   * @param f   the new value for the field of {@code obj}
   * being modified
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not an
   *              instance of the class or interface declaring the underlying
   *              field (or a subclass or implementor thereof),
   *              or if an unwrapping conversion fails.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#set
   */
  public void setFloat(Object obj, float f) throws IllegalArgumentException,
      IllegalAccessException {
    VMCommonLibrarySupport.setFloat(obj, f, vmField, this, RVMClass
        .getClassFromStackFrame(1));
  }

  /**
   * Sets the value of a field as a {@code double} on the specified object.
   * This method is equivalent to
   * {@code set(obj, dObj)},
   * where {@code dObj} is a {@code Double} object and
   * {@code dObj.doubleValue() == d}.
   *
   * @param obj the object whose field should be modified
   * @param d   the new value for the field of {@code obj}
   * being modified
   *
   * @exception IllegalAccessException    if the underlying field
   *              is inaccessible.
   * @exception IllegalArgumentException  if the specified object is not an
   *              instance of the class or interface declaring the underlying
   *              field (or a subclass or implementor thereof),
   *              or if an unwrapping conversion fails.
   * @exception NullPointerException      if the specified object is null
   *              and the field is an instance field.
   * @exception ExceptionInInitializerError if the initialization provoked
   *              by this method fails.
   * @see       Field#set
   */
  public void setDouble(Object obj, double d) throws IllegalArgumentException,
      IllegalAccessException {
    VMCommonLibrarySupport.setDouble(obj, d, vmField, this, RVMClass
        .getClassFromStackFrame(1));
  }

  /*
   * Utility routine to paper over array type names
   */
  static <T extends Object> String getTypeName(Class<T> type) {
    if (type.isArray()) {
      try {
        Class cl = type;
        int dimensions = 0;
        while (cl.isArray()) {
          dimensions++;
          cl = cl.getComponentType();
        }
        StringBuffer sb = new StringBuffer();
        sb.append(cl.getName());
        for (int i = 0; i < dimensions; i++) {
          sb.append("[]");
        }
        return sb.toString();
      } catch (Throwable e) { /*FALLTHRU*/
      }
    }
    return type.getName();
  }

  /**
   * @throws NullPointerException {@inheritDoc}
   * @since 1.5
   */
  public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
    if (annotationClass == null)
      throw new NullPointerException();

    return vmField.getAnnotation(annotationClass);
  }

  private static final Annotation[] EMPTY_ANNOTATION_ARRAY = new Annotation[0];

  /**
   * @since 1.5
   */
  public Annotation[] getDeclaredAnnotations() {
    return vmField.getDeclaredAnnotations();
  }
}
