package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Every two minutes, the trips out on the road are estimated again (roadmap
 * T1-03), so a vehicle held up between stops is seen late before it arrives
 * anywhere. Each trip in its own transaction.
 */
@Component
public class EtaRefreshJob implements ScheduledJob {
  private final Database database;
  private final JdbcDeliveryRepository deliveries;
  private final EtaPropagation propagation;

  EtaRefreshJob(Database database, JdbcDeliveryRepository deliveries, EtaPropagation propagation) {
    this.database = database;
    this.deliveries = deliveries;
    this.propagation = propagation;
  }

  @Override
  public String name() {
    return "execution.eta-refresh";
  }

  @Override
  public String cron() {
    return "0 */2 * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.EXECUTION;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many stores were told a new time */
  public int runAt(Instant now) {
    List<UUID> trips =
        database.asSystem(
            ModuleRole.EXECUTION,
            () -> deliveries.tripsUnderWay(
                now.atZone(Clock.OPERATING_ZONE).toLocalDate(), now.atZone(Clock.OPERATING_ZONE).toLocalTime()));
    int told = 0;
    for (UUID trip : trips) {
      told += database.asSystem(ModuleRole.EXECUTION, () -> propagation.refresh(Actor.SYSTEM, trip, now));
    }
    return told;
  }
}
