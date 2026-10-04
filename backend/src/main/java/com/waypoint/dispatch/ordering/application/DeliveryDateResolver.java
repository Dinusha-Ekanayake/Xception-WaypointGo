package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.ordering.domain.DeliveryDate;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Joins the pure {@link DeliveryDate} rule to the calendar Reference Data
 * publishes and the closures Ordering keeps, so placement and the preview the
 * store sees before submitting cannot disagree (D-I).
 *
 * <p>Must run inside a transaction as {@code waypoint_ordering}.
 */
@Component
public class DeliveryDateResolver {
  private final ReferenceQuery reference;
  private final JdbcOrderRepository orders;

  public DeliveryDateResolver(ReferenceQuery reference, JdbcOrderRepository orders) {
    this.reference = reference;
    this.orders = orders;
  }

  public DeliveryDate resolve(String depotCode, LocalDate requested, Instant now) {
    return DeliveryDate.resolve(
        requested, now, reference::isOperating, date -> orders.isClosed(depotCode, date));
  }
}
