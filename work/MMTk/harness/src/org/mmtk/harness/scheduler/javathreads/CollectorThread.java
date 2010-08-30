/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.harness.scheduler.javathreads;

import org.mmtk.harness.Main;
import org.mmtk.harness.lang.Trace;
import org.mmtk.harness.lang.Trace.Item;
import org.mmtk.plan.CollectorContext;

class CollectorThread extends JavaThread {
  private static int collectorId = 0;

  private final CollectorContext context;

  protected CollectorThread(CollectorContext context, boolean daemon) {
    this.context = context;
    setName("Collector-"+(++collectorId));
    setDaemon(daemon);
    setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
      public void uncaughtException(Thread t, Throwable e) {
        Trace.trace(Item.SCHEDULER, "Catching uncaught exception for thread %s%n%s",
            Thread.currentThread().getName(),
            e.getClass().getCanonicalName());
        e.printStackTrace();
        Main.exitWithFailure();
      }
    });
  }

  CollectorThread(CollectorContext context) {
    this(context,true);
  }

  protected void init() {
    // We need to run the Collector constructor in an MMTkThread so that we can access the
    // Thread local 'Log' object.  Otherwise Log.writes in constructors don't work.
    JavaThreadModel.setCurrentCollector(context);
  }

  @Override
  public void run() {
    init();
    context.run();
  }
}
