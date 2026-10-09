package com.waypoint.dispatch.execution.contract;

import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryRecordView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetView;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The only way another module reads delivery records. A driver's scope is one
 * vehicle on one date, enforced in SQL (R-IAM, edge case EXE-13).
 */
public interface ExecutionQuery {

  Optional<RunSheetView> runSheet(String vehicleId, LocalDate serviceDate);

  Optional<DeliveryRecordView> deliveryRecord(UUID deliveryId);

  /** The latest delivery record for an order, if any stop was attempted. */
  Optional<DeliveryRecordView> deliveryForOrder(UUID orderId);

  /**
   * A depot's stops that reached an outcome between two service dates
   * inclusive, oldest first, on a keyset cursor (issue #16: the training export
   * and service-time history). Row-level security narrows it to the actor.
   */
  /**
   * A vehicle's recorded GPS fixes between two instants, oldest first, at most
   * {@code limit} (R-ML-12: an arrival the driver did not tap, read from where the
   * vehicle was). Row-level security narrows it to the actor.
   */
  java.util.List<ExecutionViews.TrailPointView> vehicleFixes(String vehicleId, java.time.Instant from, java.time.Instant to, int limit);

  com.waypoint.dispatch.shared.domain.Page<ExecutionViews.StopActualView> actuals(
      String depotCode, LocalDate from, LocalDate to, Optional<String> cursor, int limit);
}
