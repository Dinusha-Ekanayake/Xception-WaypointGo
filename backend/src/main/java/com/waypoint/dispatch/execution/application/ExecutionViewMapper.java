package com.waypoint.dispatch.execution.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryLineView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryRecordView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetStopView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetView;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Rows to contract views. No decisions here. */
final class ExecutionViewMapper {
  private static final ObjectMapper JSON = new ObjectMapper();

  private ExecutionViewMapper() {}

  static RunSheetStopView stop(Map<String, Object> row) {
    DeliveryRecord r = JdbcDeliveryRepository.record(row);
    return new RunSheetStopView(
        r.deliveryId(), r.tripId(), r.sequence(), r.orderId(), r.outletId(), r.itemCount(), r.mallOutlet(),
        r.plannedArrival(), r.window().open(), r.window().close(), instant(row.get("expected_arrival")),
        r.startedAt(), r.arrivedAt(), r.completedAt(), r.waitMinutes(), r.lateMinutes(), r.outcome(), r.deliveredUnits(),
        r.proofId().isPresent(), r.rowVersion(), lines(row), Optional.ofNullable((String) row.get("store_answer_waived")));
  }

  /** One sheet per vehicle, in the order the rows came: trips as they left, stops as planned. */
  static List<RunSheetView> sheets(List<Map<String, Object>> rows) {
    Map<String, List<Map<String, Object>>> byVehicle = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      byVehicle.computeIfAbsent((String) row.get("vehicle_id"), v -> new ArrayList<>()).add(row);
    }
    List<RunSheetView> sheets = new ArrayList<>();
    byVehicle.forEach(
        (vehicle, stops) ->
            sheets.add(
                new RunSheetView(
                    vehicle,
                    JdbcDeliveryRepository.record(stops.get(0)).serviceDate(),
                    stops.stream().map(ExecutionViewMapper::stop).toList())));
    return sheets;
  }

  static DeliveryRecordView view(Map<String, Object> row) {
    DeliveryRecord r = JdbcDeliveryRepository.record(row);
    return new DeliveryRecordView(
        r.deliveryId(), r.orderId(), r.tripId(), r.outletId(), r.vehicleId(), r.serviceDate(), r.outcome(),
        r.arrivedAt(), r.serviceStartedAt(), r.completedAt(), r.waitMinutes(), r.lateMinutes(),
        r.lateReason(), r.timingUncertain(), r.deliveredUnits(), r.failureReason(), r.dispositionNote(),
        r.lowEvidence(), r.proofId(), instant(row.get("client_recorded_at")),
        ((Timestamp) row.get("server_recorded_at")).toInstant(), r.rowVersion(), lines(row),
        r.sequence(), Optional.ofNullable((Integer) row.get("trip_stop_count")), r.plannedArrival(),
        instant(row.get("expected_arrival")), ((Timestamp) row.get("released_at")).toInstant(),
        r.startedAt(), Optional.empty());
  }

  /** The products of a record, read as one JSON array with the record so a run sheet stays one query. */
  static List<DeliveryLineView> lines(Map<String, Object> row) {
    Object raw = row.get("lines");
    if (raw == null) {
      return List.of();
    }
    try {
      List<DeliveryLineView> lines = new ArrayList<>();
      for (JsonNode line : JSON.readTree(raw.toString())) {
        JsonNode delivered = line.get("deliveredUnits");
        lines.add(new DeliveryLineView(
            line.get("productId").asText(),
            line.get("orderedUnits").asInt(),
            delivered == null || delivered.isNull() ? Optional.empty() : Optional.of(delivered.asInt())));
      }
      return lines;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Delivery lines are not the JSON the read builds", e);
    }
  }

  private static Optional<Instant> instant(Object value) {
    return Optional.ofNullable((Timestamp) value).map(Timestamp::toInstant);
  }
}
