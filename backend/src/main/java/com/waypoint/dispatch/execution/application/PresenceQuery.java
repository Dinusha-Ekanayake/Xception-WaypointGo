package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetView;
import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Whether each vehicle's driver is connected right now (R-EXE-25): their app
 * reached the server within {@link #CONNECTED}. A connected driver hears
 * notifications and can record stops; one who is not cannot, whatever the last
 * position says. Read for the vehicles on a depot's run sheets that day, under
 * the same scope as the run sheets, on real time, never the demo clock.
 */
@Component
public class PresenceQuery {
  /** The driver app reaches the server every few seconds; sessions record it at most once a minute. */
  static final Duration CONNECTED = Duration.ofMinutes(2);

  /**
   * @param lastSeenAt when the driver's app last reached the server; empty with no driver or no live session
   */
  public record PresenceView(String vehicleId, Optional<UUID> driverId, Optional<Instant> lastSeenAt, boolean connected) {}

  private final ExecutionDataQuery sheets;
  private final IdentityQuery identity;
  private final Clock clock;

  public PresenceQuery(ExecutionDataQuery sheets, IdentityQuery identity, Clock clock) {
    this.sheets = sheets;
    this.identity = identity;
    this.clock = clock.realTime();
  }

  public List<PresenceView> ofDepot(Actor actor, String depotCode, LocalDate serviceDate) {
    Map<String, Optional<UUID>> drivers = new LinkedHashMap<>();
    for (RunSheetView sheet : sheets.runSheetsOfDepot(actor, depotCode, serviceDate)) {
      drivers.computeIfAbsent(sheet.vehicleId(), v -> identity.driverOn(v, serviceDate));
    }
    Map<UUID, Instant> seen =
        identity.lastSeen(drivers.values().stream().flatMap(Optional::stream).distinct().toList());
    Instant now = clock.now();
    List<PresenceView> views = new ArrayList<>();
    drivers.forEach((vehicle, driver) -> {
      Optional<Instant> at = driver.map(seen::get);
      boolean connected = at.map(a -> Duration.between(a, now).compareTo(CONNECTED) <= 0).orElse(false);
      views.add(new PresenceView(vehicle, driver, at, connected));
    });
    return List.copyOf(views);
  }
}
