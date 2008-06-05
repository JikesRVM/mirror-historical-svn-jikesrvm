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

/**
 * Implementation of Harmony VMI Invocation API for Jikes RVM.
 */

#define LINUX
#include "vmi.h"
#include "bootImageRunner.h"

struct VMInterfaceFunctions_ vmi_impl = {
   &CheckVersion,
   &GetJavaVM,
   &GetPortLibrary,
   &GetVMLSFunctions,
   #ifndef HY_ZIP_API
   &GetZipCachePool,
   #else /* HY_ZIP_API */
   &GetZipFunctions,
   #endif /* HY_ZIP_API */
   &GetInitArgs,
   &GetSystemProperty,
   &SetSystemProperty,
   &CountSystemProperties,
   &IterateSystemProperties
};

VMInterface vmi = &vmi_impl;

vmiError JNICALL CheckVersion (VMInterface * vmi, vmiVersion * version)
{
    return VMI_ERROR_UNIMPLEMENTED;
}

JavaVM * JNICALL GetJavaVM (VMInterface * vmi)
{
    return &sysJavaVM;
}

HyPortLibrary * JNICALL GetPortLibrary (VMInterface * vmi)
{
    fprintf(stderr, "UNIMPLEMENTED VMI call GetPortLibrary\n");
    return NULL;
}

HyVMLSFunctionTable * JNICALL GetVMLSFunctions (VMInterface * vmi)
{
    fprintf(stderr, "UNIMPLEMENTED VMI call GetVMLSFunctions\n");
    return NULL;
}

#ifndef HY_ZIP_API
HyZipCachePool * JNICALL GetZipCachePool (VMInterface * vmi)
{
    fprintf(stderr, "UNIMPLEMENTED VMI call GetZipCachePool\n");
    return NULL;
}
#else /* HY_ZIP_API */
struct VMIZipFunctionTable * JNICALL GetZipFunctions (VMInterface * vmi)
{
    fprintf(stderr, "UNIMPLEMENTED VMI call GetZipFunctions\n");
    return NULL;
}
#endif /* HY_ZIP_API */

JavaVMInitArgs * JNICALL GetInitArgs (VMInterface * vmi)
{
    fprintf(stderr, "UNIMPLEMENTED VMI call GetInitArgs\n");
    return NULL;
}

vmiError JNICALL GetSystemProperty (VMInterface * vmi, char *key, char **valuePtr)
{
    fprintf(stderr, "UNIMPLEMENTED VMI call GetSystemProperty\n");
    return VMI_ERROR_UNIMPLEMENTED;
}

vmiError JNICALL SetSystemProperty (VMInterface * vmi, char *key, char *value)
{
    fprintf(stderr, "UNIMPLEMENTED VMI call SetSystemProperty\n");
    return VMI_ERROR_UNIMPLEMENTED;
}

vmiError JNICALL CountSystemProperties (VMInterface * vmi, int *countPtr)
{
    fprintf(stderr, "UNIMPLEMENTED VMI call CountSystemProperties\n");
    return VMI_ERROR_UNIMPLEMENTED;
}

vmiError JNICALL IterateSystemProperties (VMInterface * vmi, vmiSystemPropertyIterator iterator, void *userData)
{
    fprintf(stderr, "UNIMPLEMENTED VMI call IterateSystemProperties\n");
    return VMI_ERROR_UNIMPLEMENTED;
}

/**
 * Extract the VM Interface from a JNI JavaVM
 *
 * @param[in] vm  The JavaVM to query
 *
 * @return a VMInterface pointer
 */
VMInterface* JNICALL 
VMI_GetVMIFromJavaVM(JavaVM* vm)
{
    return &vmi;
}	
/**
 * Extract the VM Interface from a JNIEnv
 *
 * @param[in] env  The JNIEnv to query
 *
 * @return a VMInterface pointer
 */
VMInterface* JNICALL 
VMI_GetVMIFromJNIEnv(JNIEnv* env)
{
    return &vmi;
}	
