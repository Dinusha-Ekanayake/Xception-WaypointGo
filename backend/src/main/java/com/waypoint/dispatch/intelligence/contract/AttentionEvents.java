package com.waypoint.dispatch.intelligence.contract;

import com.waypoint.dispatch.shared.event.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * What the attention watch tells the rest of the system (issue #268, FF-09).
 * Notification rings the dispatcher's bell; Issues may ask the store a question
 * by itself. The watch still decides nothing.
 */
public final class AttentionEvents {
  private AttentionEvents() {}

  /**
   * The watch found something on a live stop for the first time.
   *
   * @param kind FAILED_STOP, WINDOW_AT_RISK, RUNNING_LATE or PROOF_OWED
   * @param severity CRITICAL, HIGH or MEDIUM
   * @param minutesLeft minutes until the window closes, where a clock applies
   */
  public record AttentionRaised(
      UUID deliveryId,
      String kind,
      String severity,
      String depotCode,
      LocalDate serviceDate,
      String vehicleId,
      UUID tripId,
      String outletId,
      Optional<Integer> minutesLeft,
      Instant raisedAt)
      implements DomainEvent {
    public static final String TYPE = "attention.raised";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "attention";
    }

    @Override
    public String aggregateId() {
      return deliveryId + ":" + kind;
    }
  }

  /** A critical item is still not acknowledged after the wait: the dispatcher is reminded (capped). */
  public record AttentionReminded(
      UUID deliveryId, String kind, String depotCode, LocalDate serviceDate, String outletId, int remindedCount,
      Instant remindedAt)
      implements DomainEvent {
    public static final String TYPE = "attention.reminded";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "attention";
    }

    @Override
    public String aggregateId() {
      return deliveryId + ":" + kind;
    }
  }
}
