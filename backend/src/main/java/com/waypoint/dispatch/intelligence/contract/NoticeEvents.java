package com.waypoint.dispatch.intelligence.contract;

import com.waypoint.dispatch.shared.event.DomainEvent;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** Events Intelligence publishes about a published plan's stops (roadmap T1-02). */
public final class NoticeEvents {
  private NoticeEvents() {}

  /**
   * A published stop will likely arrive after its window closes (R-ML-22).
   * {@code audience} is {@code DISPATCHER} while the flag is still learning or
   * the store has had its two this week, else {@code STORE}. {@code expectedArrival}
   * is the time it is expected before the trip leaves, to five minutes, given
   * only when that is after the window closes.
   */
  public record StopLikelyLate(
      UUID orderId, UUID planId, String outletId, String depotCode, LocalDate serviceDate,
      LocalTime plannedArrival, LocalTime windowClose, int percent, String audience,
      java.util.Optional<LocalTime> expectedArrival)
      implements DomainEvent {
    public static final String TYPE = "stop.likely_late";

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
