package com.waypoint.dispatch.warehouse.application;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The polling fallback and the reconciler, on their timetables. */
public final class WarehouseJobs {
  private WarehouseJobs() {}

  /** Polls held orders until the warehouse webhook exists, then backs it up. */
  @Component
  public static class StatusPollJob implements ScheduledJob {
    private final WarehouseReconciler reconciler;

    public StatusPollJob(WarehouseReconciler reconciler) {
      this.reconciler = reconciler;
    }

    @Override
    public String name() {
      return "warehouse.status-poll";
    }

    @Override
    public String cron() {
      return "15 */2 * * * *";
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.WAREHOUSE;
    }

    @Override
    public void run(Instant now) {
      reconciler.poll(now, 200);
    }
  }

  @Component
  public static class ReconcileJob implements ScheduledJob {
    private final WarehouseReconciler reconciler;

    public ReconcileJob(WarehouseReconciler reconciler) {
      this.reconciler = reconciler;
    }

    @Override
    public String name() {
      return "warehouse.reconcile";
    }

    @Override
    public String cron() {
      return "45 */15 * * * *";
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.WAREHOUSE;
    }

    @Override
    public void run(Instant now) {
      reconciler.reconcile(now, Optional.empty(), 500);
    }
  }
}
