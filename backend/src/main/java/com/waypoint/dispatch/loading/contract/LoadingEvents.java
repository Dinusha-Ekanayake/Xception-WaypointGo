package com.waypoint.dispatch.loading.contract;

import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import com.waypoint.dispatch.shared.event.DomainEvent;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
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

  /**
   * @param photoAttachmentIds FF-02: the photos the loader took of the line, minted
   *     on the device and uploaded through the issue photos; empty for none, and
   *     for an event written before photos were carried.
   */
  public record LoadingShortfall(
      UUID shortfallId,
      UUID tripId,
      UUID orderId,
      String depotCode,
      CheckStatus kind,
      int missingUnits,
      String reason,
      List<UUID> photoAttachmentIds)
      implements DomainEvent {
    public static final String TYPE = "loading.shortfall";

    public LoadingShortfall {
      photoAttachmentIds = photoAttachmentIds == null ? List.of() : List.copyOf(photoAttachmentIds);
    }

    public LoadingShortfall(
        UUID shortfallId, UUID tripId, UUID orderId, String depotCode, CheckStatus kind, int missingUnits, String reason) {
      this(shortfallId, tripId, orderId, depotCode, kind, missingUnits, reason, List.of());
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

  /** @param serviceMinutes the planned time at the stop (roadmap T1-03); empty when the plan did not carry it */
  public record ReleasedStop(
      int sequence, UUID orderId, String outletId, LocalTime plannedArrival, Optional<Integer> serviceMinutes) {

    public ReleasedStop {
      serviceMinutes = serviceMinutes == null ? Optional.empty() : serviceMinutes;
    }

    public ReleasedStop(int sequence, UUID orderId, String outletId, LocalTime plannedArrival) {
      this(sequence, orderId, outletId, plannedArrival, Optional.empty());
    }
  }

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
