                JikesRVM/JDWP implementation note

		    Byeongchoel Lee, UT Austin
		          bclee@cs.utexas.edu

* Downloading and building the JikesRVM/JDWP

  Download and build the JikesRVM in Linux-2.6.xx/x86

  (bash) svn checkout
        https://jikesrvm.svn.sourceforge.net/svnroot/jikesrvm/rvmroot/branches/RVM-JDWP-SoC2008
        jdwp
  ...
  (bash) cd jdwp;ant -Dhost.name=ia32-linux -Dconfig.name=prototype

* Check the JikesRVM command line argument

  (bash) export PATH="$PWD/dist/prototype_ia32-linux/rvm:$PATH"

  (bash) rvm -X
  ...
    -Xrunjdwp:<option>       Load and configure JDWP agent
             :help           Print usage choices for -X;runjdwp
  (bash) rvm -Xrunjdwp:help
  -Xrunjdwp[:help]		Print usage of the JDWP agent.
  -Xrunjdwp:[<option>=<value>, ...]		Configure the JDWP agent.

  Option             Default value       Description
  suspend=y|n        y                   Suspend VM before starting application.
  transport=...      none                Name transport. e.g. dt_socket
  server=...         n                   Listens for the debugger
  address=...        none                Transport address for the connection
  verbose=..         0                   JDWP subsystem verbose level

* Check rdb command

  (bash) export PATH="$PWD/bin:$PATH"

  (bash) rdb
  Usage: rdb <options> <class> <arguments>
   JikesRVM jdb launcher

   where options include:
       -help                   print this message and exit
      -jdwpverbose <level>    JDWP agent's verbose level

   options forward to the jdb: 
     -sourcepath <directories separated by ":">
     -dbgtrace
     -tclient
     -tserver

     Try "jdb -help" for more information. The remaining <class> and
   <arguments> are forwared to the jdb

* Try rdb

  (bash)cat -n Fact.java
     1	/**
     2	 * Simple 10-20 lines test program for the initial JikesRVM/JDWP Google SOC
     3	 * work.
     4	 */
     5	public class Fact {
     6	
     7	  /** The main method.*/
     8	  public static void main(String[] args) {
     9	    System.out.println("fact(4) = " + fact(4));
    10	  }
    11	
    12	  /**
    13	   * compute the factorial number
    14	   *
    15	   * @param n the input number.
    16	   */
    17	  private static int fact(int n) {
    18	    if ( n <= 0 ) {
    19	      return 1;
    20	    } else {
    21	      return n * fact( n - 1 );
    22	    }
    23	  }
    24	}

  (bash) javac -g Fact.java

  (bash)rdb Fact
  Deferring uncaught java.lang.Throwable.
  It will be set after the class is loaded.
  Initializing jdb ...
  > 
  VM Started: No frames on the current call stack

  MainThread[1] stop at Fact:18
  Set breakpoint Fact:18
  MainThread[1] cont
  > 
  Breakpoint hit: "thread=MainThread", Fact.fact(), line=18 bci=0
  18        if ( n <= 0 ) {

  MainThread[1] list
  14       *
  15       * @param n the input number.
  16       */
  17      private static int fact(int n) {
  18 =>     if ( n <= 0 ) {
  19          return 1;
  20        } else {
  21          return n * fact( n - 1 );
  22        }
  23      }
  MainThread[1] where
    [1] Fact.fact (Fact.java:18)
    [2] Fact.main (Fact.java:9)
  MainThread[1] print n
   n = 4
  MainThread[1] exit

* Try an regression test script

  Consider the simple example testing script for trying how this works.

   (bash) export PATH="$PWD/dist/prototype_ia32-linux/rvm:$PATH"
   (bash) javac -g Fact.java
   (bash) expect Fact.exp
   rdb Fact
   Initializing jdb ...
   VM Started: No frames on the current call stack

   MainThread[1] cont
   fact(4) = 24
   The application exited
   PASS

   rdb Fact
   Initializing jdb ...
   VM Started: No frames on the current call stack
   MainThread[1] exit
   PASS
   ...
