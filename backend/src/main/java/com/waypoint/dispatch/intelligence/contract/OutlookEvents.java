package com.waypoint.dispatch.intelligence.contract;

import com.waypoint.dispatch.intelligence.contract.PredictionViews.OutlookStatus;
import com.waypoint.dispatch.shared.event.DomainEvent;
import java.time.LocalDate;
import java.util.UUID;

/** Events Intelligence publishes about the date outlook (issue #224). */
public final class OutlookEvents {
  private OutlookEvents() {}

  /**
   * A day an order is booked for has worsened to busy or at risk since it was
   * booked (R-ML-08). Advice: the order still goes for that day, and the plan
   * made the afternoon before decides.
   */
  public record OrderOutlookChanged(
      UUID orderId, String outletId, String depotCode, LocalDate deliveryDate, OutlookStatus status, String reason)
      implements DomainEvent {
    public static final String TYPE = "order.outlook_changed";

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
