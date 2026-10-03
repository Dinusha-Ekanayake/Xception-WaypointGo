package com.waypoint.dispatch.referencedata.infrastructure;

import com.waypoint.dispatch.platform.config.DirectoryLocator;
import com.waypoint.dispatch.referencedata.domain.CalendarDay;
import com.waypoint.dispatch.referencedata.domain.DeliveryWindow;
import com.waypoint.dispatch.referencedata.domain.Depot;
import com.waypoint.dispatch.referencedata.domain.District;
import com.waypoint.dispatch.referencedata.domain.DockType;
import com.waypoint.dispatch.referencedata.domain.Outlet;
import com.waypoint.dispatch.referencedata.domain.ParkingConstraint;
import com.waypoint.dispatch.referencedata.domain.ReferenceSnapshot;
import com.waypoint.dispatch.referencedata.domain.ServiceAllowance;
import com.waypoint.dispatch.referencedata.domain.TemperatureCapability;
import com.waypoint.dispatch.referencedata.domain.TravelProfile;
import com.waypoint.dispatch.referencedata.domain.Vehicle;
import com.waypoint.dispatch.referencedata.domain.VehicleType;
import com.waypoint.dispatch.shared.domain.DepotCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Reads the supplied reference CSVs into a snapshot.
 *
 * <p>This is the staging half of the import: it parses and shapes, it does not
 * judge. Validation is a separate, pure step so the rules can be tested without
 * a filesystem, and persistence is a third step so nothing is written until the
 * whole import is known to be good.
 *
 * <p>Depots and districts are derived from the data rather than configured,
 * because decision D8 makes depot a function of district.
 */
@Component
public class CsvReferenceImporter {

  /**
   * Staged content plus the hash that decides whether this import is new.
   *
   * @param trafficSpeed speed index by district, hour and monsoon (D9), versioned with the rest
   * @param roadConditions disruption index by district and date, a date-keyed series like the calendar
   */
  public record Staged(
      ReferenceSnapshot snapshot,
      List<TrafficSpeedRow> trafficSpeed,
      List<RoadConditionRow> roadConditions,
      String contentHash,
      String sourceLabel) {

    public Staged {
      trafficSpeed = List.copyOf(trafficSpeed);
      roadConditions = List.copyOf(roadConditions);
    }

    public Staged(ReferenceSnapshot snapshot, String contentHash, String sourceLabel) {
      this(snapshot, List.of(), List.of(), contentHash, sourceLabel);
    }
  }

  public record TrafficSpeedRow(String district, int hour, boolean monsoon, BigDecimal speedIndex) {}

  public record RoadConditionRow(String district, LocalDate date, BigDecimal disruptionIndex) {}

  private static final List<String> FILES =
      List.of(
          "outlets.csv",
          "vehicles.csv",
          "district_travel.csv",
          "service_allowance.csv",
          "calendar.csv",
          "traffic_speed.csv",
          "road_conditions.csv");

  public Staged stage(Path dataDir) {
    Path base = Files.isDirectory(dataDir) ? dataDir : DirectoryLocator.resolve(dataDir.toString(), "data");
    Path general = base.resolve("General Data");
    Path root = Files.isDirectory(general) ? general : base;

    List<TravelProfile> travel = new ArrayList<>();
    Map<String, DepotCode> districtDepots = new LinkedHashMap<>();
    for (CSVRecord row : read(root.resolve("district_travel.csv"))) {
      String district = row.get("district");
      districtDepots.put(district, new DepotCode(row.get("depot")));
      travel.add(
          new TravelProfile(
              district,
              row.get("road_class"),
              decimal(row, "free_flow_kmh"),
              decimal(row, "depot_to_district_km"),
              decimal(row, "depot_to_district_freeflow_min"),
              decimal(row, "inter_stop_km"),
              decimal(row, "inter_stop_freeflow_min")));
    }

    List<District> districts =
        districtDepots.entrySet().stream()
            .map(e -> new District(e.getKey(), e.getValue()))
            .toList();
    List<Depot> depots =
        new LinkedHashSet<>(districtDepots.values())
            .stream()
                .map(code -> new Depot(code, code.value(), ZoneId.of("Asia/Colombo")))
                .toList();

    List<Outlet> outlets = new ArrayList<>();
    LinkedHashSet<String> brands = new LinkedHashSet<>();
    for (CSVRecord row : read(root.resolve("outlets.csv"))) {
      brands.add(row.get("brand"));
      outlets.add(
          new Outlet(
              row.get("outlet_id"),
              row.get("brand"),
              row.get("district"),
              DockType.parse(row.get("dock_type")),
              ParkingConstraint.parse(row.get("parking_constraint")),
              new DeliveryWindow(time(row, "window_open_time"), time(row, "window_close_time")),
              mallWindow(row.get("mall_window"))));
    }

    List<Vehicle> vehicles = new ArrayList<>();
    for (CSVRecord row : read(root.resolve("vehicles.csv"))) {
      vehicles.add(
          new Vehicle(
              row.get("vehicle_id"),
              VehicleType.parse(row.get("type")),
              TemperatureCapability.parse(row.get("temp")),
              decimal(row, "weight_cap_kg"),
              decimal(row, "volume_cap_m3"),
              row.get("fuel_type"),
              decimal(row, "km_per_l"),
              decimal(row, "weekly_fuel_quota_l"),
              new DepotCode(row.get("depot"))));
    }

    List<ServiceAllowance> allowances = new ArrayList<>();
    for (CSVRecord row : read(root.resolve("service_allowance.csv"))) {
      allowances.add(
          new ServiceAllowance(
              row.get("brand"),
              DockType.parse(row.get("dock_type")),
              decimal(row, "service_allowance_min")));
    }

    List<CalendarDay> calendar = new ArrayList<>();
    for (CSVRecord row : read(root.resolve("calendar.csv"))) {
      calendar.add(
          new CalendarDay(
              LocalDate.parse(row.get("date")),
              Integer.parseInt(row.get("dow")),
              flag(row, "is_weekend"),
              Integer.parseInt(row.get("iso_year")),
              Integer.parseInt(row.get("iso_week")),
              flag(row, "is_payday"),
              blankToNull(row.get("festival")),
              decimal(row, "festival_ramp"),
              flag(row, "is_holiday"),
              flag(row, "monsoon"),
              flag(row, "is_operating"),
              false));
    }

    ReferenceSnapshot snapshot =
        new ReferenceSnapshot(
            UUID.randomUUID(),
            List.copyOf(brands),
            depots,
            districts,
            outlets,
            vehicles,
            travel,
            allowances,
            calendar);
    // Traffic speed and road conditions feed the predictor (issue #16). They are optional
    // here so an older data directory without them still imports; the predictor then
    // uses its fallback model and says so.
    List<TrafficSpeedRow> traffic = new ArrayList<>();
    Path trafficFile = root.resolve("traffic_speed.csv");
    if (Files.exists(trafficFile)) {
      for (CSVRecord row : read(trafficFile)) {
        traffic.add(
            new TrafficSpeedRow(
                row.get("district"),
                Integer.parseInt(row.get("hour")),
                flag(row, "monsoon"),
                decimal(row, "speed_index")));
      }
    }
    List<RoadConditionRow> roads = new ArrayList<>();
    Path roadFile = root.resolve("road_conditions.csv");
    if (Files.exists(roadFile)) {
      for (CSVRecord row : read(roadFile)) {
        roads.add(
            new RoadConditionRow(
                row.get("district"), LocalDate.parse(row.get("date")), decimal(row, "disruption_index")));
      }
    }
    return new Staged(snapshot, traffic, roads, hashOf(root), root.toString());
  }

  /**
   * A hash over the file contents, so re-importing identical data is a no-op
   * rather than a new version of the world.
   */
  private static String hashOf(Path root) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String name : FILES) {
        Path file = root.resolve(name);
        if (Files.exists(file)) {
          digest.update(name.getBytes(StandardCharsets.UTF_8));
          digest.update(Files.readAllBytes(file));
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (Exception e) {
      throw new IllegalStateException("Could not hash reference data", e);
    }
  }

  private static Iterable<CSVRecord> read(Path file) {
    if (!Files.exists(file)) {
      throw new IllegalStateException("Reference file not found: " + file.toAbsolutePath());
    }
    try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return CSVFormat.DEFAULT
          .builder()
          .setHeader()
          .setSkipHeaderRecord(true)
          .setTrim(true)
          .build()
          .parse(reader)
          .getRecords();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + file, e);
    }
  }

  private static Optional<DeliveryWindow> mallWindow(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String[] parts = raw.split("-");
    return Optional.of(
        new DeliveryWindow(LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim())));
  }

  private static LocalTime time(CSVRecord row, String column) {
    return LocalTime.parse(row.get(column).trim());
  }

  private static BigDecimal decimal(CSVRecord row, String column) {
    String value = row.get(column);
    return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.trim());
  }

  private static boolean flag(CSVRecord row, String column) {
    return "1".equals(row.get(column).trim());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
