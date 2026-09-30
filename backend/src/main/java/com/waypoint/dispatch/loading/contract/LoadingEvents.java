package com.waypoint.dispatch.loading.contract;

import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import com.waypoint.dispatch.shared.event.DomainEvent;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Events Loading publishes. Consumers: Planning, Execution, Ordering, Warehouse, Issues, Notification. */
public final class LoadingEvents {
  private LoadingEvents() {}

  public record LoadingStarted(UUID tripId, UUID planId, String vehicleId) implements DomainEvent {
    public static final String TYPE = "loading.started";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "trip";
    }

    @Override
    public String aggregateId() {
      return tripId.toString();
    }
  }

  public record LoadingShortfall(
      UUID shortfallId,
      UUID tripId,
      UUID orderId,
      String depotCode,
      CheckStatus kind,
      int missingUnits,
      String reason)
      implements DomainEvent {
    public static final String TYPE = "loading.shortfall";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "trip";
    }

    @Override
    public String aggregateId() {
      return tripId.toString();
    }
  }

  public record InterchangeRequested(
      UUID tripId,
      UUID planId,
      String currentVehicleId,
      String replacementVehicleId,
      String reason)
      implements DomainEvent {
    public static final String TYPE = "loading.interchange_requested";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "trip";
    }

    @Override
    public String aggregateId() {
      return tripId.toString();
    }
  }

  public record ReleasedStop(
      int sequence, UUID orderId, String outletId, LocalTime plannedArrival) {}

  /**
   * The trip left the dock. Carries its stops so Execution builds the run sheet
   * from this event alone.
   */
  public record TripReleased(
      UUID tripId,
      UUID planId,
      int planVersion,
      String vehicleId,
      String depotCode,
      LocalDate serviceDate,
      List<ReleasedStop> stops)
      implements DomainEvent {
    public static final String TYPE = "trip.released";

    public TripReleased {
      stops = List.copyOf(stops);
    }

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "trip";
    }

    @Override
    public String aggregateId() {
      return tripId.toString();
    }
  }
}
