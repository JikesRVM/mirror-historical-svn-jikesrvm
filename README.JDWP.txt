 JikesRVM/JDWP implementation note
  Byeongchoel Lee, UT Austin
  bclee@cs.utexas.edu

* Trying how it works?

  Download and build the JikesRVM in Linux-2.6.xx/x86

  (bash) svn checkout https://jikesrvm.svn.sourceforge.net/svnroot/jikesrvm/rvmroot/branches/RVM-JDWP-SoC2008 jdwp
  ...
  (bash) cd jdwp
  (bash) ant -Dhost.name=ia32-linux -Dconfig.name=prototype
  ...

  Consider the simple example testing script for trying how this works.

   (bash) export PATH="$PWD/dist/prototype_ia32-linux/rvm:$PATH"
   (bash) javac -g Fact.java
   (bash) expect Fact.exp
   spawn jdb -launch -connect com.sun.jdi.RawCommandLineLaunch:command=rvm "-Xrunjdwp:verbose=0,transport=dt_socket,address=8000,suspend=y" Fact,address=8000
   Initializing jdb ...
   VM Started: No frames on the current call stack

   MainThread[1] cont
   fact(4) = 24
   The application exited
   PASS

   spawn jdb -launch -connect com.sun.jdi.RawCommandLineLaunch:command=rvm "-Xrunjdwp:verbose=0,transport=dt_socket,address=8000,suspend=y" Fact,address=8000
   Initializing jdb ...
   VM Started: No frames on the current call stack
   MainThread[1] exit
   PASS
   ...

* The major source file change.

  * rvm/src/org/jikesrvm/*.java
    ==> JikesRVM internal support for the JDWP.

  * libraryInterface/GNUClasspath/CPL/src/gnu/classpath/jdwp/*.java
    ==> JikesRMV port of the GNU classpath JDWP.

  * libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/*.java
    ==> simple copy of the GNU claspath JDWP with the local bug patch
	The bug fix will be reported and these source files will be
	eventually removed before getting into the main branch.

  * For the JDWP specific patch, you can try the following.
    (bash) svn diff -r 14600

* JikesRVM/JDWP options

 The JikesRVM will implement the subset of the SUN JVM's JDWP command
 line options:

 [http://java.sun.com/javase/6/docs/technotes/guides/jpda/conninv.html#Invocation]

  option name       JikesRVM implementation
  -----------------------------------------
  help              YES                            
  transport         dt_socket only        gnu.classpath.jdwp.transport._TRANSPORT_PROPERTY
  server            YES                   gnu.classpath.jdwp.transport.SocketTransport._PROPERTY_SERVER
  address           YES                   gnu.classpath.jdwp.transport.SocketTransport._PROPERTY_ADDRESS
  timeout           NO
  launch            NO
  onthrow           NO
  onuncaught        NO
  suspend           YES                   gnu.classpath.jdwp.Jdwp._PROPERTY_SUSPEND

  The following is the help message from JikesRVM/JDWP.

  ---------------------------------------------------------------------------
  coral:~/w/jdwp$rvm -Xrunjdwp:help
  -Xrunjdwp[:help]		Print usage of the JDWP agent.
  -Xrunjdwp:[<option>=<value>, ...]		Configure the JDWP agent.

  Option             Default value       Description
  suspend=y|n        y                   Suspend VM before starting application.
  transport=...      none                Name transport. e.g. dt_socket
  server=...         n                   Listens for the debugger
  address=...        none                Transport address for the connection
  verbose=..         0                   JDWP subsystem verbose level
  ---------------------------------------------------------------------------

  An issue here is the default value for the "suspend." SUN JVM has
  "y" as a default "suspend" value, but the GNU classpath JDWP has "n"
  default value. For the compatibility with the existing tools, I'd
  prefer "y" as default value. I have two choices for this. The first
  one is to ask the GNU classpath community to switch to the "y"
  default value. The second one is that the JikesRVM's porting layer
  handles this.

* JikesRVM's command line argument for the JDWP agent launch.

  JikesRVM would support the following two types of the command line
  arguments.

  1. old: "-Xdebug -Xrunjdwp:<name1>[=<value1>],<name2>[=<value2>]..."

  2. new: "-agentlib:jdwp=<name1>[=<value1>],<name2>[=<value2>]..."

   -- Sun VM Invocation Options
  [http://java.sun.com/javase/6/docs/technotes/guides/jpda/conninv.html#Invocation]

  Both formats have the same effect of initializing the JDWP agent,
  and passing the JDWP agent a set of <name,value> pairs to decide how
  to connect to the debugger and which transport channel to use. Both
  SUN hotspot and IBM J9 support all these two JDWP argument
  formats. The JDB in the JDK 1.6 internally uses the old format, and
  the Eclipse JDT 3.3.2 uses the new format.

  I'm a little bit concerned with the new JDWP argument format. The
  new JDWP command line argument seems to assume that the JDWP agent
  runs in the native mode. In addition, the JDPA documentation shows
  doubtful view about the Java agent approach:

  "It is clear from experience that debugger support code, running on
  the debuggee and written in Java, contends with the debuggee in ways
  that cause hangs and other undesired behavior. Thus, the back-end is
  native code. This, in turn, implies that the JVM TI be a pure native
  interface."

   -- Java Platform Debugger Architecture
  [http://java.sun.com/javase/6/docs/technotes/guides/jpda/architecture.html]

  ------------------------------------------------------------------------
  coral:~/w/jdwp$java 
  ...
    -agentlib:<libname>[=<options>]
              load native agent library <libname>, e.g. -agentlib:hprof
              see also, -agentlib:jdwp=help and -agentlib:hprof=help
    -agentpath:<pathname>[=<options>]
              load native agent library by full pathname
    -javaagent:<jarpath>[=<options>]
              load Java programming language agent, see java.lang.instrument
  ...
  ------------------------------------------------------------------------

* Connecting JikesRVM to the existing debuggers such as JDB and Eclipse JDT

  I'd like to argue that the JikesRVM build image respects the
  JRE-like directory structure.

  ${JAVA_HOME}/bin
  ${JAVA_HOME}/bin/rvm
  ...

  This JRE-like structure is what the JDB and the eclipse JDT
  assume. This JRE-like structure would not be required to claim
  JVM-compatibility, and in theory the Java Platform Debugger
  Architecture (JPDA) does not enforce this JRE-like
  structure. However, it's better to respect the existing
  implementations to exploit the existing debuggers such as JDB and
  eclipse JDT.

  JDB: The SUN JPDA documentation shows the following 8 types of the
  connectors:

   + com.sun.jdi.CommandLineLaunch  Transport: dt_socket
   + com.sun.jdi.RawCommandLineLaunch  Transport: dt_socket
   + com.sun.jdi.SocketAttach  Transport: dt_socket
   + com.sun.jdi.SocketListen  Transport: dt_socket
   + com.sun.jdi.ProcessAttach  Transport: local
   + sun.jvm.hotspot.jdi.SACoreAttachingConnector  Transport: filesystem
   + sun.jvm.hotspot.jdi.SADebugServerAttachingConnector  Transport: RMI
   + sun.jvm.hotspot.jdi.SAPIDAttachingConnector  Transport: local process  
  [http://java.sun.com/javase/6/docs/technotes/guides/jpda/conninv.html#Connectors]

  Out of these 8 types, com.sun.jdi.CommandLineLaunch is the typical
  one. This connector takes the following three variables.

    * home: the JRE home directory. e.g. /var/local/bclee/jdk1.6/jre
    * vmexec: the JVM file name. e.g java
    * main: main class and its arguments e.g. Fact 1

  JDB internally decides the values for these variables, and creates a
  JVM process like the following.

    ${home}/bin/${vmexec} -Xdebug
    -Xrunjdwp:transport=dt_socket,address=...,suspend=y ${main}

  ---------------------------------------------------------------
  coral:~/w/jdwp$which jdb
  /var/local/bclee/jdk1.6/bin/jdb

  coral:~/w/jdwp$jdb Fact 1
  Initializing jdb ...
  > stop in Fact.main
  > run
  Breakpoint hit: "thread=main", Fact.main(), line=5 bci=0
  5        System.out.println("fact(4) = " + fact(4));
  main[1] 
  [5]+  Stopped                 

  coral:~/w/jdwp$ps -w h -C java -o args
  /var/local/bclee/jdk1.6/jre/bin/java -Xdebug
  -Xrunjdwp:transport=dt_socket,address=coral.cs.utexas.edu:38076,suspend=y
  Fact 1
  ---------------------------------------------------------------

  JDB can allow the user to specifiy some of these debugger connector
  variables:

  ---------------------------------------------------------------
  coral:~/w/jdwp$jdb -connect
  com.sun.jdi.CommandLineLaunch:vmexec=java,home=/var/local/bclee/ibm-java2-i386-50/jre
  Fact 1

  Initializing jdb ...
  > stop in Fact.main
  > run
  Breakpoint hit: "thread=main", Fact.main(), line=5 bci=0
  5        System.out.println("fact(4) = " + fact(4));
  main[1] 
  [5]+  Stopped                 

  coral:~/w/jdwp$ps -w h -C java -o args
  /var/local/bclee/ibm-java2-i386-50/jre/bin/java -Xdebug
  -Xrunjdwp:transport=dt_socket,address=coral.cs.utexas.edu:36139,suspend=y
  Fact 1
  ----------------------------------------------------------------

  Eclipse JDT: the Eclipse JDT allows various types of JVM debuggee
  environments such as Eclipse Application, Java Applet, Java
  Application, JUnit, OSGI framework and Remote Java Application. Out
  of these, the Java Application configuration would be the common
  case for the JikesRVM. This configuration seems to use the same
  launch interface as the jdb:

  ----------------------------------------------------------------
  coral:~/w/jdwp$ps -w h -C java -o args
  /v/filer4b/software/java5/jdk1.5.0/linux/bin/java -classpath
  /var/local/bclee/w/SPECjvm98
  -agentlib:jdwp=transport=dt_socket,suspend
  ----------------------------------------------------------------  

  The eclipse allows the user to specify a particular JRE for the
  debuggging, and it perhaps internally uses the
  com.sun.jdi.CommandLineLaunch or equivalent.

  The major difference between JDB and eclipse JDT is the way to
  specify the JDWP agent. The JDB in the JDK 1.6 still uses the old
  command line argument, and the eclipse JDT uses the new one. The Sun
  recommends using the new "-agentlib:jdwp" instead of the old
  "-Xrunjdwp:."
  [http://java.sun.com/javase/6/docs/technotes/guides/jpda/conninv.html#Invocation]

* Jikes RVM JDWP status at the beginning

 + branch 

  (bash) svn checkout  https://jikesrvm.svn.sourceforge.net/svnroot/jikesrvm/rvmroot/branches/RVM-33-JdwpSupport jdwp
  (bash) cd jdwp

 + log	
  (bash) svn log -r 13021:14081
  r13021 --> simply branch creation
  r13022 --> jdwp.13022.patch
  r13029 --> jdwp.13029.patch
  r13210 --> jdwp.13210.patch
  r13354 --> jdwp.13354.patch
  r13361 --> jdwp.13392.patch
  r13392 --> merge with r13380 trunk?
  r13403 --> jdwp.13403.patch

  + JikesRVM codes

    + The followings are related to the Jikes RVM port of the JDWP.

    file name                                         patches
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/VMMethod.java                                      r13022        r13354
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/VMIdManager.java                                   r13022
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/VMFrame.java                                       r13022        r13354
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/VMVirtualMachine.java                              r13022        r13354 r13361 r13403
    build/primordials/Classpath-0.95.txt                                                                         r13022        r13354
    rvm/src/org/jikesrvm/VM_CommandLineArgs.java                                                                 r13022        r13354
    rvm/src/org/jikesrvm/VM.java                                                                                        r13210 r13354
    rvm/src/org/jikesrvm/VM_Callbacks.java                                                                              r13210 r13354 r13361
    rvm/src/org/jikesrvm/classloader/VM_Class.java                                                                      r13210 r13354
    rvm/src/org/jikesrvm/classloader/VM_NormalMethod.java                                                               r13210
    rvm/src/org/jikesrvm/classloader/VM_TypeReference.java                                                                     r13354
    rvm/src/org/jikesrvm/compilers/baseline/ia32/VM_Compiler.java                                                              r13354 r13361
    rvm/src/org/jikesrvm/scheduler/VM_Thread.java                                                                       r13210 r13354 r13361
    rvm/src/org/jikesrvm/scheduler/VM_MainThread.java                                                                   r13210 
    rvm/src/org/jikesrvm/scheduler/VM_Scheduler.java                                                                           r13354 r13361 r13403
    rvm/src/org/jikesrvm/runtime/VM_ExitStatus.java                                                                            r13354

    + The followings are patches simply for the internal debugging and logging.

    file name                                         patches
    libraryInterface/Common/src/java/lang/Thread.java                                                                           r13354
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/Jdwp.java                                                                       r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/processor/ObjectReferenceCommandSet.java                                        r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/processor/ClassTypeCommandSet.java                                              r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/processor/VirtualMachineCommandSet.java                                         r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/processor/PacketProcessor.java                                                  r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/processor/ReferenceTypeCommandSet.java                                          r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/event/Event.java                                                                r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/event/filters/LocationOnlyFilter.java                                           r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/event/EventManager.java                                                         r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/transport/JdwpConnection.java                                                   r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/transport/JdwpPacket.java                                                       r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/value/ObjectValue.java                                                          r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/value/StringValue.java                                                          r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/util/MethodResult.java                                                          r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/util/Location.java                                                              r13403
    libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/debug/SystemLogger.java                                                              r13403

