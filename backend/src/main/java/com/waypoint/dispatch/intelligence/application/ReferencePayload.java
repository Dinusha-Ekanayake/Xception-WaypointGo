package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.CalendarDayView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The reference tables the model service needs, rebuilt from Waypoint's
 * reference contract in the shape of the supplied CSVs: same table names, same
 * column names. The model's feature code then runs unchanged, and a request
 * records exactly which reference data the prediction was made with.
 */
@Component
class ReferencePayload {
  static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

  /**
   * The calendar starts where the training data does: the forecast's trend
   * terms are measured from it, and a festival ramp needs the days that lead to it.
   */
  static final LocalDate CALENDAR_FROM = LocalDate.of(2024, 1, 1);

  private final ReferenceQuery reference;

  ReferencePayload(ReferenceQuery reference) {
    this.reference = reference;
  }

  /** Every table for scoring one depot's routes on one day. */
  Map<String, Object> forDepotDay(String depotCode, LocalDate day, UUID versionId) {
    List<OutletView> outlets = reference.outletsOfDepot(depotCode, versionId);
    List<VehicleView> vehicles = reference.vehiclesOfDepot(depotCode, versionId);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("outlets", outlets.stream().map(ReferencePayload::outlet).toList());
    out.put("vehicles", vehicles.stream().map(ReferencePayload::vehicle).toList());

    List<Map<String, Object>> allowances = new ArrayList<>();
    Set<String> combos = new LinkedHashSet<>();
    for (OutletView o : outlets) {
      if (combos.add(o.brandCode() + "|" + o.dockType())) {
        reference.serviceAllowance(o.brandCode(), o.dockType(), versionId).ifPresent(a -> allowances.add(row(
            "brand", a.brandCode(), "dock_type", a.dockType(), "service_allowance_min", a.minutes())));
      }
    }
    out.put("service_allowance", allowances);

    List<Map<String, Object>> travel = new ArrayList<>();
    for (String district : new LinkedHashSet<>(outlets.stream().map(OutletView::districtName).toList())) {
      reference.travelProfile(district, versionId).ifPresent(t -> travel.add(row(
          "district", t.districtName(), "depot", t.depotCode(), "road_class", t.roadClass(),
          "free_flow_kmh", t.freeFlowKmh(), "depot_to_district_km", t.depotToDistrictKm(),
          "depot_to_district_freeflow_min", t.depotToDistrictFreeflowMin(), "inter_stop_km", t.interStopKm(),
          "inter_stop_freeflow_min", t.interStopFreeflowMin())));
    }
    out.put("district_travel", travel);
    out.put("traffic_speed", reference.trafficSpeed(versionId).stream()
        .map(t -> row("district", t.districtName(), "hour", t.hour(), "monsoon", t.monsoon() ? 1 : 0,
            "speed_index", t.speedIndex()))
        .toList());
    // A festival ramp is read backwards from the next festival, so the calendar
    // runs a year past the day.
    out.put("calendar", calendar(day.minusDays(60), day.plusDays(370)));
    out.put("road_conditions", reference.roadConditions(day, day).stream()
        .map(r -> row("district", r.districtName(), "date", r.date().toString(), "disruption_index",
            r.disruptionIndex()))
        .toList());
    return out;
  }

  List<Map<String, Object>> calendar(LocalDate from, LocalDate to) {
    return reference.calendarDays(from, to).stream().map(ReferencePayload::day).toList();
  }

  private static Map<String, Object> outlet(OutletView o) {
    boolean mall = o.effectiveWindowOpen().isPresent()
        && (!o.effectiveWindowOpen().get().equals(o.windowOpen())
            || !o.effectiveWindowClose().orElse(o.windowClose()).equals(o.windowClose()));
    return row(
        "outlet_id", o.outletId(), "brand", o.brandCode(), "district", o.districtName(), "depot", o.depotCode(),
        "dock_type", o.dockType(), "parking_constraint", o.parkingConstraint(),
        "mall_window", mall ? time(o.effectiveWindowOpen().get()) + "-" + time(o.effectiveWindowClose().orElseThrow())
            : null,
        "window_open_time", time(o.windowOpen()), "window_close_time", time(o.windowClose()));
  }

  private static Map<String, Object> vehicle(VehicleView v) {
    return row("vehicle_id", v.vehicleId(), "type", v.vehicleType(), "temp", v.refrigerated() ? "reefer" : "ambient",
        "weight_cap_kg", v.weightCapKg(), "volume_cap_m3", v.volumeCapM3(), "depot", v.depotCode());
  }

  private static Map<String, Object> day(CalendarDayView d) {
    DayOfWeek dow = d.date().getDayOfWeek();
    return row(
        "date", d.date().toString(), "dow", dow.getValue() - 1, "is_weekend",
        dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY ? 1 : 0, "iso_year", d.isoYear(), "iso_week",
        d.isoWeek(), "is_payday", d.payday() ? 1 : 0, "festival", d.festival(), "festival_ramp", d.festivalRamp(),
        "is_holiday", d.holiday() ? 1 : 0, "monsoon", d.monsoon() ? 1 : 0, "is_operating", d.operating() ? 1 : 0);
  }

  static String time(LocalTime t) {
    return t.format(HHMM);
  }

  /** An insertion-ordered row that, unlike {@code Map.of}, holds a null. */
  static Map<String, Object> row(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }
}
