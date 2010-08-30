package org.mmtk.harness.scheduler.rawthreads;

import java.util.ArrayList;
import java.util.List;

import org.mmtk.harness.lang.Trace;
import org.mmtk.harness.lang.Trace.Item;

/**
 * A Monitor object in the Raw Threads model
 */
public class RawMonitor extends org.mmtk.vm.Monitor {

  private static final boolean TRACE = false;

  private boolean isLocked = false;

  private final RawThreadModel model;

  private final List<RawThread> waitList = new ArrayList<RawThread>();

  RawMonitor(RawThreadModel model) {
    this.model = model;
  }

  @Override
  public void lock() {
    if (TRACE) System.out.println("lock() : in");
    while (isLocked) {
      Trace.trace(Item.SCHEDULER,"Yielded onto monitor queue ");
      model.yield(waitList);
    }
    isLocked = true;
    if (TRACE) System.out.println("lock() : out");
  }

  @Override
  public void unlock() {
    if (TRACE) System.out.println("unlock() : in");
    isLocked = false;
    model.makeRunnable(waitList);
    if (TRACE) System.out.println("unlock() : out");
  }

  @Override
  public void await() {
    if (TRACE) System.out.println("await() : in");
    unlock();
    Trace.trace(Item.SCHEDULER,"Yielded onto monitor queue ");
    model.yield(waitList);
    lock();
    if (TRACE) System.out.println("await() : out");
  }

  @Override
  public void broadcast() {
    if (TRACE) System.out.println("broadcast() : in");
    model.makeRunnable(waitList);
    if (TRACE) System.out.println("broadcast() : out");
  }

}
