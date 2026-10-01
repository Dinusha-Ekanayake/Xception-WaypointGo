package com.waypoint.dispatch.loading.application;

import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import com.waypoint.dispatch.loading.contract.LoadingViews.HolderView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ItemView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ManifestLineView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ManifestView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ReadyTripView;
import com.waypoint.dispatch.loading.contract.LoadingViews.SessionStatus;
import com.waypoint.dispatch.loading.contract.LoadingViews.ShortfallView;
import com.waypoint.dispatch.loading.domain.ItemLine;
import com.waypoint.dispatch.loading.domain.LoadingSession;
import com.waypoint.dispatch.loading.infrastructure.JdbcLoadingRepository;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Rows to views. No reads of its own except the outlet lookup for windows and names. */
final class LoadingViewMapper {
  private LoadingViewMapper() {}

  static ReadyTripView readyTrip(Map<String, Object> r) {
    return new ReadyTripView(
        (UUID) r.get("trip_id"),
        (String) r.get("vehicle_id"),
        integer(r, "trip_number"),
        integer(r, "trips_for_vehicle"),
        time(r.get("planned_departure")),
        status(r, ((Number) r.get("pending")).longValue() > 0, ((Number) r.get("flagged")).longValue() > 0),
        (String) r.get("brand_code"),
        (String) r.get("district_name"),
        (String) r.get("temperature"),
        (String) r.get("dock_code"),
        integer(r, "stop_count"),
        integer(r, "order_count"),
        (BigDecimal) r.get("weight_kg"),
        (BigDecimal) r.get("volume_m3"),
        holder(r),
        instant(r.get("released_at")),
        ((Number) r.get("row_version")).longValue());
  }

  static ManifestView manifest(
      Map<String, Object> trip,
      List<Map<String, Object>> stops,
      List<Map<String, Object>> items,
      ReferenceQuery reference) {
    List<ManifestLineView> lines = new ArrayList<>();
    boolean anyPending = false;
    boolean anyFlagged = false;
    int loadSequence = 1;
    for (Map<String, Object> stop : stops) {
      UUID orderId = (UUID) stop.get("order_id");
      List<ItemView> orderItems = new ArrayList<>();
      for (Map<String, Object> i : items) {
        if (orderId.equals(i.get("order_id"))) {
          orderItems.add(item(i));
        }
      }
      anyPending |= orderItems.stream().anyMatch(i -> i.status() == CheckStatus.PENDING);
      anyFlagged |= orderItems.stream().anyMatch(i -> ItemLine.isFlag(i.status()));
      Optional<OutletView> outlet = reference.outlet((String) stop.get("outlet_id"), null);
      lines.add(
          new ManifestLineView(
              loadSequence++,
              integer(stop, "stop_sequence"),
              orderId,
              (String) stop.get("order_ref"),
              (String) stop.get("outlet_id"),
              outlet.map(OutletView::districtName).orElse((String) stop.get("outlet_id")),
              outlet.map(o -> o.effectiveWindowOpen().orElse(o.windowOpen())),
              outlet.map(o -> o.effectiveWindowClose().orElse(o.windowClose())),
              Optional.ofNullable(time(stop.get("planned_arrival"))),
              (String) stop.get("temperature"),
              integer(stop, "item_count"),
              (BigDecimal) stop.get("weight_kg"),
              (BigDecimal) stop.get("volume_m3"),
              orderStatus(orderItems),
              orderItems.stream().mapToInt(ItemView::loadedUnits).sum(),
              orderItems.stream().mapToInt(ItemView::attempt).max().orElse(0),
              orderItems));
    }
    return new ManifestView(
        (UUID) trip.get("trip_id"),
        (UUID) trip.get("plan_id"),
        integer(trip, "plan_version"),
        (String) trip.get("depot_code"),
        ((java.sql.Date) trip.get("service_date")).toLocalDate(),
        (String) trip.get("vehicle_id"),
        integer(trip, "trip_number"),
        integer(trip, "trips_for_vehicle"),
        (String) trip.get("brand_code"),
        (String) trip.get("district_name"),
        (String) trip.get("temperature"),
        time(trip.get("planned_departure")),
        (String) trip.get("dock_code"),
        (BigDecimal) trip.get("weight_cap_kg"),
        (BigDecimal) trip.get("volume_cap_m3"),
        status(trip, anyPending, anyFlagged),
        holder(trip),
        instant(trip.get("released_at")),
        lines,
        ((Number) trip.get("row_version")).longValue());
  }

  static ShortfallView shortfall(Map<String, Object> r) {
    return new ShortfallView(
        (UUID) r.get("shortfall_id"),
        (UUID) r.get("trip_id"),
        (UUID) r.get("order_id"),
        Optional.ofNullable((Integer) r.get("line_no")),
        JdbcLoadingRepository.status(r.get("kind")),
        integer(r, "missing_units"),
        (String) r.get("reason"),
        (UUID) r.get("reported_by"),
        ((Timestamp) r.get("reported_at")).toInstant(),
        instant(r.get("resolved_at")));
  }

  // ---- internals ---------------------------------------------------------------

  private static ItemView item(Map<String, Object> i) {
    return new ItemView(
        integer(i, "line_no"),
        (String) i.get("product_id"),
        integer(i, "units"),
        i.get("status") == null ? CheckStatus.PENDING : JdbcLoadingRepository.status(i.get("status")),
        i.get("loaded_units") == null ? 0 : integer(i, "loaded_units"),
        i.get("attempt") == null ? 0 : integer(i, "attempt"),
        instant(i.get("recorded_at")),
        Optional.ofNullable((UUID) i.get("actor_user_id")));
  }

  /** PENDING while any item is unchecked, LOADED when all are loaded, otherwise the first flag. */
  private static CheckStatus orderStatus(List<ItemView> items) {
    if (items.stream().anyMatch(i -> i.status() == CheckStatus.PENDING)) {
      return CheckStatus.PENDING;
    }
    return items.stream()
        .map(ItemView::status)
        .filter(ItemLine::isFlag)
        .findFirst()
        .orElse(CheckStatus.LOADED);
  }

  private static SessionStatus status(Map<String, Object> r, boolean anyPending, boolean anyFlagged) {
    return LoadingSession.statusOf(JdbcLoadingRepository.phase((String) r.get("phase")), anyPending, anyFlagged);
  }

  private static Optional<HolderView> holder(Map<String, Object> r) {
    if (r.get("holder_user_id") == null) {
      return Optional.empty();
    }
    return Optional.of(
        new HolderView(
            (UUID) r.get("holder_user_id"),
            (String) r.get("holder_name"),
            Optional.ofNullable((String) r.get("holder_code")),
            ((Timestamp) r.get("held_since")).toInstant(),
            ((Timestamp) r.get("holder_active_at")).toInstant()));
  }

  private static int integer(Map<String, Object> r, String column) {
    return ((Number) r.get(column)).intValue();
  }

  private static LocalTime time(Object value) {
    return value == null ? null : ((Time) value).toLocalTime();
  }

  private static Optional<Instant> instant(Object value) {
    return value == null ? Optional.empty() : Optional.of(((Timestamp) value).toInstant());
  }
}
