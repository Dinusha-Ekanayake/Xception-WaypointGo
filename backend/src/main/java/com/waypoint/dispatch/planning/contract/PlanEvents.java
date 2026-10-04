package com.waypoint.dispatch.planning.contract;

import com.waypoint.dispatch.shared.event.DomainEvent;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Events Planning publishes. Consumers: Ordering, Loading, Execution,
 * Notification.
 *
 * <p>A published or revised plan carries its trips and stops in the event, so
 * Loading and Execution build their own records from it and never read
 * Planning's tables.
 */
public final class PlanEvents {
  private PlanEvents() {}

  public record PlannedStop(
      int sequence, UUID orderId, String outletId, LocalTime plannedArrival) {}

  public record PlannedTrip(
      UUID tripId,
      String vehicleId,
      int tripNumber,
      String brandCode,
      String districtName,
      String temperature,
      LocalTime plannedDeparture,
      List<PlannedStop> stops) {

    public PlannedTrip {
      stops = List.copyOf(stops);
    }
  }

  public record PlanPublished(
      UUID planId,
      String depotCode,
      LocalDate serviceDate,
      int planVersion,
      Optional<UUID> supersedes,
      List<PlannedTrip> trips)
      implements DomainEvent {
    public static final String TYPE = "plan.published";

    public PlanPublished {
      trips = List.copyOf(trips);
    }

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "plan";
    }

    @Override
    public String aggregateId() {
      return planId.toString();
    }
  }

  /**
   * A new published version replaced {@code supersedes}; consumers rebuild from it.
   *
   * @param changedTripIds the trips a driver would see differently from the plan
   *     this replaced. Absent on an event written before this was recorded, and
   *     then every trip counts as changed
   * @param affectedOutletIds outlets reached on a different trip or at a different
   *     time; absent as above, and then none is told separately
   */
  public record PlanRevised(
      UUID planId,
      String depotCode,
      LocalDate serviceDate,
      int planVersion,
      UUID supersedes,
      String reason,
      List<PlannedTrip> trips,
      Optional<List<UUID>> changedTripIds,
      Optional<List<String>> affectedOutletIds)
      implements DomainEvent {
    public static final String TYPE = "plan.revised";

    public PlanRevised {
      trips = List.copyOf(trips);
      changedTripIds = changedTripIds == null ? Optional.empty() : changedTripIds.map(List::copyOf);
      affectedOutletIds = affectedOutletIds == null ? Optional.empty() : affectedOutletIds.map(List::copyOf);
    }

    /** A revision with no record of what changed: everyone on it hears. */
    public PlanRevised(
        UUID planId,
        String depotCode,
        LocalDate serviceDate,
        int planVersion,
        UUID supersedes,
        String reason,
        List<PlannedTrip> trips) {
      this(planId, depotCode, serviceDate, planVersion, supersedes, reason, trips, Optional.empty(), Optional.empty());
    }

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "plan";
    }

    @Override
    public String aggregateId() {
      return planId.toString();
    }
  }

  /** @param ruleId the binding constraint, never a generic message (R-PLN-19) */
  public record OrderDeferred(
      UUID orderId,
      UUID planId,
      String outletId,
      LocalDate serviceDate,
      String ruleId,
      String reason,
      int skipCount)
      implements DomainEvent {
    public static final String TYPE = "order.deferred";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "order";
    }

    @Override
    public String aggregateId() {
      return orderId.toString();
    }
  }

  /**
   * A dispatcher asked an outlet's store manager to act on an order the plan
   * could not serve (too big for any vehicle, or deferred). Carries the
   * dispatcher's own words; Planning decides nothing from it.
   */
  public record StoreContacted(
      UUID orderId, UUID planId, String outletId, LocalDate serviceDate, String message, UUID contactedBy)
      implements DomainEvent {
    public static final String TYPE = "plan.store_contacted";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "order";
    }

    @Override
    public String aggregateId() {
      return orderId.toString();
    }
  }

  public record OrderUnservable(
      UUID orderId, UUID planId, String outletId, String ruleId, String reason)
      implements DomainEvent {
    public static final String TYPE = "order.unservable";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "order";
    }

    @Override
    public String aggregateId() {
      return orderId.toString();
    }
  }
}
