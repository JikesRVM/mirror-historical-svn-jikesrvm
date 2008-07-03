JikesRVM/JDWP implementation note
  Byeongchoel Lee, UT Austin. bclee@cs.utexas.edu

* Trying how it works?

  Download and build the JikesRVM in Linux-2.6.xx/x86

  (bash) svn checkout https://jikesrvm.svn.sourceforge.net/svnroot/jikesrvm/rvmroot/branches/RVM-JDWP-SoC2008 jdwp
  ...
  (bash) cd jdwp
  (bash) ant -Dhost.name=ia32-linux -Dconfig.name=prototype
  ...

  Consider the simple example testing script for trying how this works.

   (bash) javac -g Fact.java
   (bash) expect Fact.exp
   spawn jdb -launch -connect com.sun.jdi.RawCommandLineLaunch:command=rvm "-Xrunjdwp:verbose=0,transport=dt_socket,address=8000,suspend=y" Fact,address=8000
   Set uncaught java.lang.Throwable
   Set deferred uncaught java.lang.Throwable
   Initializing jdb ...
   > 
   VM Started: "thread=MainThread", org.jikesrvm.scheduler.greenthreads.GreenProcessor.dispatch(), line=324 bci=201
   
   MainThread[1] threads
   Group main:
     (org.jikesrvm.scheduler.MainThread)0x1 MainThread cond. waiting
   MainThread[1] where
     [1] org.jikesrvm.scheduler.greenthreads.GreenProcessor.dispatch (GreenProcessor.java:324)
     [2] org.jikesrvm.scheduler.greenthreads.GreenThread.morph (GreenThread.java:469)
     [3] org.jikesrvm.scheduler.greenthreads.GreenThread.yield (GreenThread.java:394)
     [4] org.jikesrvm.scheduler.greenthreads.GreenThread.suspendInternal (GreenThread.java:756)
     [5] org.jikesrvm.scheduler.RVMThread.suspend (RVMThread.java:858)
     [6] gnu.classpath.jdwp.VMVirtualMachine.suspendThread (VMVirtualMachine.java:104)
     [7] gnu.classpath.jdwp.VMVirtualMachine.suspendAllThreads (VMVirtualMachine.java:112)
     [8] gnu.classpath.jdwp.Jdwp._enforceSuspendPolicy (Jdwp.java:348)
     [9] gnu.classpath.jdwp.Jdwp.notify (Jdwp.java:225)
     [10] gnu.classpath.jdwp.VMVirtualMachine$JDWPEventNotifier.notifyStartup (VMVirtualMachine.java:1,055)
     [11] org.jikesrvm.Callbacks.notifyStartup (Callbacks.java:805)
     [12] org.jikesrvm.scheduler.MainThread.run (MainThread.java:196)
     [13] org.jikesrvm.scheduler.RVMThread.run (RVMThread.java:592)
     [14] org.jikesrvm.scheduler.RVMThread.startoff (RVMThread.java:617)
   MainThread[1] sourcepath rvm/src
   MainThread[1] list
   320        }
   321    
   322        threadId = activeThread.getLockingId();
   323        activeThreadStackLimit = activeThread.stackLimit; // Delay this to last possible moment so we can sysWrite
   324 =>     Magic.threadSwitch(previousThread, activeThread.contextRegisters);
   325      }
   326    
   327      /**
   328       * Handle a request from the garbage collector to flush the mutator context.
   329       */
   MainThread[1] up
   MainThread[2] list
   465          VM._assert(myThread.beingDispatched, "morph: not beingDispatched");
   466        }
   467        // become another thread
   468        //
   469 =>     GreenProcessor.getCurrentProcessor().dispatch(timerTick);
   470        // respond to interrupt sent to this thread by some other thread
   471        // NB this can create a stack trace, so is interruptible
   472        if (myThread.throwInterruptWhenScheduled) {
   473          myThread.postExternalInterrupt();
   474        }
   MainThread[2] exit
   PASS

* The major source file change.

  * rvm/src/org/jikesrvm/*.java
    ==> JikesRVM internal support for the JDWP.

  * libraryInterface/GNUClasspath/CPL/src/gnu/classpath/jdwp/*.java
    ==> JikesRMV port of the GNU classpath JDWP.

  * libraryInterface/GNUClasspath/LGPL/src/gnu/classpath/jdwp/*.java
    ==> simple copy of the GNU claspath JDWP with the local bug patch
	The bug fix will be reported and these source files will be
	eventually removed before getting into the main branch.
