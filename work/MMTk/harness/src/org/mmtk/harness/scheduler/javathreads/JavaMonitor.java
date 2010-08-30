package org.mmtk.harness.scheduler.javathreads;

public class JavaMonitor extends org.mmtk.vm.Monitor {

  private static final boolean TRACE = false;

  private final Object monitor = new Object();

  private boolean isLocked = false;

  @Override
  public void await() {
    if (TRACE) System.out.println("await() : in");
    synchronized(monitor) {
      try {
        unlock();
        monitor.wait();
        lock();
      } catch (InterruptedException e) { }
    }
    if (TRACE) System.out.println("await() : out");
  }

  @Override
  public void broadcast() {
    if (TRACE) System.out.println("broadcast() : in");
    synchronized(monitor) {
      monitor.notifyAll();
    }
    if (TRACE) System.out.println("broadcast() : out");
  }

  @Override
  public void lock() {
    if (TRACE) System.out.println("lock() : in");
    synchronized(monitor) {
      while (isLocked) {
        try {
          monitor.wait();
        } catch (InterruptedException e) { }
      }
      isLocked = true;
    }
    if (TRACE) System.out.println("lock() : out");
  }

  @Override
  public void unlock() {
    if (TRACE) System.out.println("unlock() : in");
    synchronized(monitor) {
      isLocked = false;
      monitor.notifyAll();
    }
    if (TRACE) System.out.println("unlock() : out");
  }

}
