package com.waypoint.dispatch.demo.contract;

import com.waypoint.dispatch.shared.event.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Events Demo publishes. */
public final class DemoEvents {
  private DemoEvents() {}

  /**
   * An administrator asked for sample notifications in the demo driver's inbox,
   * so judges can see the driver's notifications without running a plan or a
   * loading first. Notification writes them; nothing else changes.
   */
  public record DriverNotificationsRequested(UUID requestId, UUID driverUserId, LocalDate serviceDate, Instant at)
      implements DomainEvent {
    public static final String TYPE = "demo.driver_notifications_requested";

    @Override public String type() { return TYPE; }
    @Override public String aggregateType() { return "demo"; }
    @Override public String aggregateId() { return requestId.toString(); }
  }
}
