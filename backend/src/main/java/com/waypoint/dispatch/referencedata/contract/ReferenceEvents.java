package com.waypoint.dispatch.referencedata.contract;

import com.waypoint.dispatch.shared.event.DomainEvent;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Events Reference data will publish once the outbox relay exists. Consumer:
 * Planning, which replans only for dates and vehicles the event names.
 *
 * <p>Reference data consumes nothing, which is what makes it the foundation
 * (FOUNDATION-PLAN 1.8). These events go out; nothing comes in.
 */
public final class ReferenceEvents {
  private ReferenceEvents() {}

  /** A new snapshot became current; caches reload and later plans stamp this id. */
  public record ReferenceVersionPublished(UUID versionId, String contentHash)
      implements DomainEvent {
    public static final String TYPE = "reference.version_published";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "reference_version";
    }

    @Override
    public String aggregateId() {
      return versionId.toString();
    }
  }

  /** @param status {@code available}, {@code in_workshop} or {@code unavailable} */
  public record VehicleStatusChanged(
      String vehicleId, LocalDate serviceDate, String status, Optional<String> reason)
      implements DomainEvent {
    public static final String TYPE = "vehicle.status_changed";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "vehicle";
    }

    @Override
    public String aggregateId() {
      return vehicleId;
    }
  }

  public record CalendarOverridden(LocalDate date, boolean operating, String reason)
      implements DomainEvent {
    public static final String TYPE = "calendar.overridden";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "calendar_day";
    }

    @Override
    public String aggregateId() {
      return date.toString();
    }
  }
}
