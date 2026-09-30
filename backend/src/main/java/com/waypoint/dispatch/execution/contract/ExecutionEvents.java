package com.waypoint.dispatch.execution.contract;

import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.shared.event.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Events Execution publishes. Consumers: Ordering, Receipt, Warehouse, Issues, Notification. */
public final class ExecutionEvents {
  private ExecutionEvents() {}

  public record DeliveryStarted(UUID deliveryId, UUID orderId, UUID tripId, String outletId)
      implements DomainEvent {
    public static final String TYPE = "delivery.started";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "delivery";
    }

    @Override
    public String aggregateId() {
      return deliveryId.toString();
    }
  }

  /** @param outcome {@code DELIVERED} or {@code PARTIAL} */
  public record DeliveryCompleted(
      UUID deliveryId,
      UUID orderId,
      UUID tripId,
      String outletId,
      DeliveryOutcome outcome,
      Optional<Integer> deliveredUnits,
      Instant completedAt,
      Optional<Integer> lateMinutes)
      implements DomainEvent {
    public static final String TYPE = "delivery.completed";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "delivery";
    }

    @Override
    public String aggregateId() {
      return deliveryId.toString();
    }
  }

  public record DeliveryFailed(
      UUID deliveryId,
      UUID orderId,
      UUID tripId,
      String outletId,
      String depotCode,
      String reason,
      Instant failedAt)
      implements DomainEvent {
    public static final String TYPE = "delivery.failed";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "delivery";
    }

    @Override
    public String aggregateId() {
      return deliveryId.toString();
    }
  }

  /** The expected arrival moved, so the store and dispatcher can be told (R-EXE-15). */
  public record EtaChanged(
      UUID deliveryId, UUID orderId, String outletId, Instant expectedArrival, int delayMinutes)
      implements DomainEvent {
    public static final String TYPE = "eta.changed";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "delivery";
    }

    @Override
    public String aggregateId() {
      return deliveryId.toString();
    }
  }

  /**
   * A driver reported a vehicle problem. Reference data is not changed by this
   * event: the dispatcher decides and applies {@code vehicle:SetDayStatus}
   * (decision B18).
   */
  public record VehicleFaultReported(
      String vehicleId, String depotCode, LocalDate serviceDate, String description, Instant at)
      implements DomainEvent {
    public static final String TYPE = "vehicle.fault_reported";

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

  public record RoadDisruptionReported(
      String vehicleId,
      String depotCode,
      Optional<String> districtName,
      String description,
      Instant at)
      implements DomainEvent {
    public static final String TYPE = "road.disruption_reported";

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
}
