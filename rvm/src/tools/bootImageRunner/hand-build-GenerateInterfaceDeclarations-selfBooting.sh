#! /usr/bin/env bash
#
# (C) Copyright IBM Corp. 2001, 2003
#
# $Id$
#
# Hand-build and run the program GenerateInterfaceDeclarations.java.
# This is useful if you are modifying that program and want a quicker 
# turn-around than building all of Jikes RVM gives you.
#
# @author Steven Augart
# @date 24 September 2003

set -e
. $RVM_BUILD/environment.host

type -a jbuild.prep.target &> /dev/null || (cd $RVM_BUILD && cp jbuild.prep.{host,target})

TMP=./Classes.tmp
rm -rf $TMP
mkdir -p $TMP

set -v
$RVM_BUILD/jbuild.toolPrep --disable-modification-exit-status $TMP *.java

cd $TMP

$RVM_BUILD/jbuild.tool com/ibm/JikesRVM/GenerateInterfaceDeclarations/GenerateInterfaceDeclarations.java

# $JAL_BUILD contains the .so files; thus the argument -nativeLibDir $JAL_BUILD
# It actually won't matter in this case; we never run the associated code.

$HOST_JAVA_RT -Xmx200M	  com.ibm.JikesRVM.GenerateInterfaceDeclarations.GenerateInterfaceDeclarations  -alternateRealityClasspath .:$JAL_BUILD/RVM.classes:$JAL_BUILD/RVM.classes/rvmrt.jar -alternateRealityNativeLibDir $JAL_BUILD -out declarations.out -ia 0x43000000
# $HOST_JAVA_RT -Xmx200M	  -classpath .:$JAL_BUILD/RVM.classes:$JAL_BUILD/RVM.classes/rvmrt.jar	  com.ibm.JikesRVM.GenerateInterfaceDeclarations.GenerateInterfaceDeclarations -out declarations.out -ia 0x43000000
