package com.waypoint.dispatch.domain;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Loads and caches data/General Data/*.csv once at startup (mirrors loadReference). */
@Component
public class ReferenceLoader {
  private final Path dataDir;
  private volatile ReferenceData cached;
  @Value("${app.demo-mode:0}") private String demoMode = "1";
  @Value("${app.calendar-file:}") private String calendarFile = "";

  public ReferenceLoader(@Value("${app.data-dir:../data}") String dataDir) {
    this.dataDir = Paths.get(dataDir);
  }

  @PostConstruct
  public void init() {
    cached = load();
  }

  public ReferenceData get() {
    ReferenceData ref = cached;
    if (ref == null) {
      ref = load();
      cached = ref;
    }
    return ref;
  }

  public void reload() {
    cached = load();
  }

  private List<Map<String, String>> csv(String file) {
    Path path = dataDir.resolve(file);
    CSVFormat format =
        CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        CSVParser parser = format.parse(reader)) {
      List<Map<String, String>> rows = new ArrayList<>();
      for (CSVRecord record : parser) {
        rows.add(record.toMap());
      }
      return rows;
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read reference CSV: " + path + ": " + e.getMessage(), e);
    }
  }

  private ReferenceData load() {
    List<ReferenceData.Outlet> outlets = new ArrayList<>();
    for (Map<String, String> row : csv("General Data/outlets.csv")) {
      outlets.add(
          new ReferenceData.Outlet(
              req(row, "outlet_id"),
              req(row, "brand"),
              req(row, "district"),
              req(row, "depot"),
              req(row, "dock_type"),
              req(row, "parking_constraint"),
              row.getOrDefault("mall_window", ""),
              req(row, "window_open_time"),
              req(row, "window_close_time")));
    }
    List<ReferenceData.Vehicle> vehicles = new ArrayList<>();
    for (Map<String, String> row : csv("General Data/vehicles.csv")) {
      vehicles.add(
          new ReferenceData.Vehicle(
              req(row, "vehicle_id"),
              req(row, "type"),
              req(row, "temp"),
              num(row, "weight_cap_kg"),
              num(row, "volume_cap_m3"),
              row.getOrDefault("fuel_type", "diesel"),
              num(row, "km_per_l"),
              num(row, "weekly_fuel_quota_l"),
              req(row, "depot"),
              row.getOrDefault("status", "")));
    }
    List<ReferenceData.CalendarRow> calendar = new ArrayList<>();
    for (Map<String, String> row : csv("General Data/calendar.csv")) {
      calendar.add(new ReferenceData.CalendarRow(req(row, "date"), req(row, "is_operating")));
    }
    if ("0".equals(demoMode)) {
      // Explicit production policy: Monday-Saturday; supplied/custom rows override it.
      var days = new java.util.TreeMap<String, String>();
      var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Colombo"));
      for (var day = today.minusYears(1); !day.isAfter(today.plusYears(2)); day = day.plusDays(1)) {
        days.put(day.toString(), day.getDayOfWeek() == java.time.DayOfWeek.SUNDAY ? "0" : "1");
      }
      for (var row : calendar) days.put(row.date(), row.isOperating());
      if (!calendarFile.isBlank()) {
        for (var row : csv(calendarFile)) {
          var date = java.time.LocalDate.parse(req(row, "date")).toString();
          String operating = req(row, "is_operating");
          if (!List.of("0", "1").contains(operating)) throw new IllegalStateException("Invalid calendar flag");
          days.put(date, operating);
        }
      }
      calendar.clear();
      days.forEach((day, flag) -> calendar.add(new ReferenceData.CalendarRow(day, flag)));
    }
    List<ReferenceData.DistrictTravel> travel = new ArrayList<>();
    for (Map<String, String> row : csv("General Data/district_travel.csv")) {
      travel.add(
          new ReferenceData.DistrictTravel(
              req(row, "district"),
              num(row, "depot_to_district_freeflow_min"),
              num(row, "inter_stop_freeflow_min"),
              num(row, "depot_to_district_km"),
              num(row, "inter_stop_km")));
    }
    List<ReferenceData.ServiceAllowance> allowance = new ArrayList<>();
    for (Map<String, String> row : csv("General Data/service_allowance.csv")) {
      allowance.add(
          new ReferenceData.ServiceAllowance(
              req(row, "brand"), req(row, "dock_type"), num(row, "service_allowance_min")));
    }
    return new ReferenceData(outlets, vehicles, calendar, travel, allowance);
  }

  /** Raw CSV rows for seed/scenario inputs (Training Data, Test Data). */
  public List<Map<String, String>> dataset(String file) {
    return csv(file);
  }

  public Path getDataDir() {
    return dataDir;
  }

  private static String req(Map<String, String> row, String key) {
    String v = row.get(key);
    if (v == null || v.isEmpty()) throw new IllegalStateException("Missing " + key + " in reference CSV");
    return v;
  }

  private static double num(Map<String, String> row, String key) {
    return Double.parseDouble(req(row, key));
  }
}
