package com.waypoint.dispatch.planning.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanStatus;
import com.waypoint.dispatch.planning.domain.ConstraintResult;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import com.waypoint.dispatch.planning.domain.RuleSet;
import com.waypoint.dispatch.platform.db.Database;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Plans in PostgreSQL. Always called inside a transaction someone else opened,
 * as {@code waypoint_planning}, so row-level security has already narrowed what
 * any statement here can see or write.
 *
 * <p>Every update of a run is {@code WHERE plan_id = ? AND row_version = ?} and
 * fails on zero rows. Children are inserted only while their run is a draft;
 * the database refuses anything else, so a published plan cannot be edited even
 * by code that forgets to check (R-PLN-28).
 */
@Repository
public class JdbcPlanRepository {
  private static final String RUN_COLUMNS =
      """
      plan_id, depot_code, service_date, plan_version, row_version, status, reference_version_id,
      rule_set_id, priority_policy_version_id, supersedes, demand_fingerprint, stale, partial, engine,
      planned_without_predictor, generated_at, generated_by, published_at, published_by
      """;

  private static final TypeReference<List<Map<String, Object>>> CHECKS = new TypeReference<>() {};

  private final Database database;
  private final ObjectMapper json;

  public JdbcPlanRepository(Database database, ObjectMapper json) {
    this.database = database;
    this.json = json;
  }

  // ---- rows ----------------------------------------------------------------

  /**
   * The run header.
   *
   * @param ruleSetId the rule set the run read its thresholds from
   * @param generatedBy the actor who asked for the run; the system actor for a consumer
   */
  public record RunRow(
      UUID planId,
      String depotCode,
      LocalDate serviceDate,
      int planVersion,
      PlanStatus status,
      UUID referenceVersionId,
      UUID ruleSetId,
      UUID priorityPolicyVersionId,
      Optional<UUID> supersedes,
      String demandFingerprint,
      boolean stale,
      boolean partial,
      String engine,
      boolean plannedWithoutPredictor,
      Instant generatedAt,
      UUID generatedBy,
      Optional<Instant> publishedAt,
      Optional<UUID> publishedBy,
      long rowVersion) {}

  /** @param litres including the return leg (R-PLN-24) */
  public record TripRow(
      UUID tripId,
      String vehicleId,
      int tripNumber,
      String brandCode,
      String districtName,
      String temperature,
      BigDecimal weightKg,
      BigDecimal volumeM3,
      BigDecimal plannedMinutes,
      LocalTime plannedDeparture,
      BigDecimal litres) {}

  /** One order's decision. A served order carries its trip, stop and arrival; nothing else does. */
  public record AllocationRow(
      UUID orderId,
      String outletId,
      AllocationDecision decision,
      Optional<UUID> tripId,
      Optional<Integer> stopSequence,
      Optional<LocalTime> plannedArrival,
      Optional<LocalTime> windowOpen,
      Optional<LocalTime> windowClose,
      BigDecimal serviceMinutes,
      Optional<String> bindingRule,
      String reason,
      List<ConstraintResult> checks) {

    public AllocationRow {
      checks = List.copyOf(checks);
    }
  }

  public record DeferralRow(
      UUID orderId,
      String outletId,
      LocalDate serviceDate,
      String ruleId,
      String reason,
      int skipCount,
      UUID actorId,
      Instant deferredAt) {}

  /** @param fromOutletId empty for the first leg, which leaves the depot */
  public record LegRow(
      UUID tripId,
      int legSequence,
      Optional<String> fromOutletId,
      String toOutletId,
      LocalTime plannedDeparture,
      LocalTime plannedArrival,
      BigDecimal plannedMinutes) {}

  public record FuelRow(String vehicleId, BigDecimal litres) {}

  // ---- reads: runs -----------------------------------------------------------

  public Optional<RunRow> findRun(UUID planId) {
    return oneRun("SELECT " + RUN_COLUMNS + " FROM planning.runs WHERE plan_id = ?", planId);
  }

  /** The current published version for the depot and day; the unique index allows one. */
  public Optional<RunRow> published(String depotCode, LocalDate serviceDate) {
    return oneRun(
        "SELECT " + RUN_COLUMNS + " FROM planning.runs"
            + " WHERE depot_code = ? AND service_date = ? AND status = 'published'",
        depotCode,
        Date.valueOf(serviceDate));
  }

  /** The newest draft for the depot and day, if one is open. */
  public Optional<RunRow> latestDraft(String depotCode, LocalDate serviceDate) {
    return oneRun(
        "SELECT " + RUN_COLUMNS + " FROM planning.runs"
            + " WHERE depot_code = ? AND service_date = ? AND status = 'draft'"
            + " ORDER BY plan_version DESC LIMIT 1",
        depotCode,
        Date.valueOf(serviceDate));
  }

  /** The highest plan version used for the depot and day, in any status; zero when none. */
  public int latestPlanVersion(String depotCode, LocalDate serviceDate) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT coalesce(max(plan_version), 0) AS v FROM planning.runs"
                + " WHERE depot_code = ? AND service_date = ?",
            depotCode,
            Date.valueOf(serviceDate));
    return ((Number) row.get("v")).intValue();
  }

  // ---- reads: children --------------------------------------------------------

  /** By vehicle, then trip number. */
  public List<TripRow> trips(UUID planId) {
    List<TripRow> trips = new ArrayList<>();
    for (Map<String, Object> row :
        database.query(
            """
            SELECT trip_id, vehicle_id, trip_number, brand_code, district_name, temperature,
                   weight_kg, volume_m3, planned_minutes, planned_departure, litres
              FROM planning.trips WHERE plan_id = ? ORDER BY vehicle_id, trip_number
            """,
            planId)) {
      trips.add(
          new TripRow(
              (UUID) row.get("trip_id"),
              (String) row.get("vehicle_id"),
              ((Number) row.get("trip_number")).intValue(),
              (String) row.get("brand_code"),
              (String) row.get("district_name"),
              (String) row.get("temperature"),
              (BigDecimal) row.get("weight_kg"),
              (BigDecimal) row.get("volume_m3"),
              (BigDecimal) row.get("planned_minutes"),
              time(row.get("planned_departure")).orElseThrow(),
              (BigDecimal) row.get("litres")));
    }
    return trips;
  }

  /** Served first by trip and stop, then the rest by order id, so the order is stable. */
  public List<AllocationRow> allocations(UUID planId) {
    List<AllocationRow> allocations = new ArrayList<>();
    for (Map<String, Object> row :
        database.query(
            """
            SELECT order_id, outlet_id, decision, trip_id, stop_sequence, planned_arrival,
                   window_open, window_close, service_minutes, binding_rule, reason, checks::text AS checks
              FROM planning.allocations WHERE plan_id = ?
             ORDER BY trip_id NULLS LAST, stop_sequence, order_id
            """,
            planId)) {
      allocations.add(
          new AllocationRow(
              (UUID) row.get("order_id"),
              (String) row.get("outlet_id"),
              decision(row.get("decision")),
              Optional.ofNullable((UUID) row.get("trip_id")),
              Optional.ofNullable((Number) row.get("stop_sequence")).map(Number::intValue),
              time(row.get("planned_arrival")),
              time(row.get("window_open")),
              time(row.get("window_close")),
              (BigDecimal) row.get("service_minutes"),
              Optional.ofNullable((String) row.get("binding_rule")),
              (String) row.get("reason"),
              readChecks((String) row.get("checks"))));
    }
    return allocations;
  }

  public List<DeferralRow> deferrals(UUID planId) {
    List<DeferralRow> deferrals = new ArrayList<>();
    for (Map<String, Object> row :
        database.query(
            """
            SELECT order_id, outlet_id, service_date, rule_id, reason, skip_count, actor_id, deferred_at
              FROM planning.deferrals WHERE plan_id = ? ORDER BY outlet_id, order_id
            """,
            planId)) {
      deferrals.add(
          new DeferralRow(
              (UUID) row.get("order_id"),
              (String) row.get("outlet_id"),
              ((Date) row.get("service_date")).toLocalDate(),
              (String) row.get("rule_id"),
              (String) row.get("reason"),
              ((Number) row.get("skip_count")).intValue(),
              (UUID) row.get("actor_id"),
              ((Timestamp) row.get("deferred_at")).toInstant()));
    }
    return deferrals;
  }

  public List<LegRow> legs(UUID tripId) {
    List<LegRow> legs = new ArrayList<>();
    for (Map<String, Object> row :
        database.query(
            """
            SELECT trip_id, leg_sequence, from_outlet_id, to_outlet_id, planned_departure,
                   planned_arrival, planned_minutes
              FROM planning.route_legs WHERE trip_id = ? ORDER BY leg_sequence
            """,
            tripId)) {
      legs.add(
          new LegRow(
              (UUID) row.get("trip_id"),
              ((Number) row.get("leg_sequence")).intValue(),
              Optional.ofNullable((String) row.get("from_outlet_id")),
              (String) row.get("to_outlet_id"),
              time(row.get("planned_departure")).orElseThrow(),
              time(row.get("planned_arrival")).orElseThrow(),
              (BigDecimal) row.get("planned_minutes")));
    }
    return legs;
  }

  /**
   * Litres committed in the ISO week containing {@code anyDay}, by published
   * runs only: a draft never consumes quota, and a superseded run's fuel is
   * replaced by its successor's (D-K, conflict B15).
   */
  public BigDecimal fuelUsed(String vehicleId, LocalDate anyDay) {
    Map<String, Object> row =
        database.queryOne(
            """
            SELECT coalesce(sum(f.litres), 0) AS litres
              FROM planning.fuel_usage f JOIN planning.runs r ON r.plan_id = f.plan_id
             WHERE f.vehicle_id = ? AND f.week_starting = ? AND r.status = 'published'
            """,
            vehicleId,
            Date.valueOf(weekStarting(anyDay)));
    return (BigDecimal) row.get("litres");
  }

  // ---- reads: configuration ------------------------------------------------------

  /**
   * The rule set in force on {@code date}. Empty when none is: the caller
   * refuses the run rather than fall back to a compiled-in value (POL-10).
   */
  public Optional<RuleSet> effectiveRuleSet(LocalDate date) {
    Map<String, Object> set =
        database.queryOne(
            """
            SELECT rule_set_id FROM planning.rule_sets
             WHERE effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
            """,
            Date.valueOf(date),
            Date.valueOf(date));
    return set == null ? Optional.empty() : Optional.of(ruleSet((UUID) set.get("rule_set_id")));
  }

  /** A stamped rule set, for replaying or publishing a run against what it was built on (POL-03). */
  public RuleSet ruleSet(UUID ruleSetId) {
    Map<String, BigDecimal> parameters = new HashMap<>();
    for (Map<String, Object> row :
        database.query(
            "SELECT parameter_key, parameter_value FROM planning.rule_parameters WHERE rule_set_id = ?",
            ruleSetId)) {
      parameters.put((String) row.get("parameter_key"), (BigDecimal) row.get("parameter_value"));
    }
    return new RuleSet(ruleSetId, parameters);
  }

  /**
   * The deferral priority in force for a depot on a date. A version scoped to
   * the depot wins over the global one (POL-08).
   */
  public Optional<PriorityPolicy> effectivePolicy(String depotCode, LocalDate date) {
    Map<String, Object> row =
        database.queryOne(
            """
            SELECT policy_version_id, keys::text AS keys FROM planning.policy_versions
             WHERE kind = 'deferral_priority' AND (depot_code = ? OR depot_code IS NULL)
               AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
             ORDER BY depot_code NULLS LAST LIMIT 1
            """,
            depotCode,
            Date.valueOf(date),
            Date.valueOf(date));
    return Optional.ofNullable(row).map(this::policy);
  }

  public Optional<PriorityPolicy> policy(UUID policyVersionId) {
    return Optional.ofNullable(
            database.queryOne(
                "SELECT policy_version_id, keys::text AS keys FROM planning.policy_versions"
                    + " WHERE policy_version_id = ?",
                policyVersionId))
        .map(this::policy);
  }

  // ---- writes ------------------------------------------------------------------

  public void insertRun(RunRow run, UUID commandId, Instant at) {
    database.update(
        """
        INSERT INTO planning.runs
            (plan_id, depot_code, service_date, plan_version, row_version, status,
             reference_version_id, rule_set_id, priority_policy_version_id, supersedes,
             demand_fingerprint, stale, partial, engine, planned_without_predictor,
             generated_at, generated_by, command_id, published_at, published_by, updated_at)
        VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        run.planId(),
        run.depotCode(),
        Date.valueOf(run.serviceDate()),
        run.planVersion(),
        code(run.status()),
        run.referenceVersionId(),
        run.ruleSetId(),
        run.priorityPolicyVersionId(),
        run.supersedes().orElse(null),
        run.demandFingerprint(),
        run.stale(),
        run.partial(),
        run.engine(),
        run.plannedWithoutPredictor(),
        Timestamp.from(run.generatedAt()),
        run.generatedBy(),
        commandId,
        run.publishedAt().map(Timestamp::from).orElse(null),
        run.publishedBy().orElse(null),
        Timestamp.from(at));
  }

  public void insertTrips(UUID planId, String depotCode, List<TripRow> trips) {
    for (TripRow t : trips) {
      database.update(
          """
          INSERT INTO planning.trips
              (trip_id, plan_id, depot_code, vehicle_id, trip_number, brand_code, district_name,
               temperature, weight_kg, volume_m3, planned_minutes, planned_departure, litres)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          t.tripId(),
          planId,
          depotCode,
          t.vehicleId(),
          t.tripNumber(),
          t.brandCode(),
          t.districtName(),
          t.temperature(),
          t.weightKg(),
          t.volumeM3(),
          t.plannedMinutes(),
          Time.valueOf(t.plannedDeparture()),
          t.litres());
    }
  }

  public void insertAllocations(UUID planId, String depotCode, List<AllocationRow> allocations) {
    for (AllocationRow a : allocations) {
      database.update(
          """
          INSERT INTO planning.allocations
              (plan_id, order_id, depot_code, outlet_id, decision, trip_id, stop_sequence,
               planned_arrival, window_open, window_close, service_minutes, binding_rule, reason, checks)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
          """,
          planId,
          a.orderId(),
          depotCode,
          a.outletId(),
          code(a.decision()),
          a.tripId().orElse(null),
          a.stopSequence().orElse(null),
          a.plannedArrival().map(Time::valueOf).orElse(null),
          a.windowOpen().map(Time::valueOf).orElse(null),
          a.windowClose().map(Time::valueOf).orElse(null),
          a.serviceMinutes(),
          a.bindingRule().orElse(null),
          a.reason(),
          writeChecks(a.checks()));
    }
  }

  public void insertDeferrals(UUID planId, String depotCode, List<DeferralRow> deferrals) {
    for (DeferralRow d : deferrals) {
      database.update(
          """
          INSERT INTO planning.deferrals
              (plan_id, order_id, depot_code, outlet_id, service_date, rule_id, reason,
               skip_count, actor_id, deferred_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          planId,
          d.orderId(),
          depotCode,
          d.outletId(),
          Date.valueOf(d.serviceDate()),
          d.ruleId(),
          d.reason(),
          d.skipCount(),
          d.actorId(),
          Timestamp.from(d.deferredAt()));
    }
  }

  public void insertLegs(UUID planId, String depotCode, List<LegRow> legs) {
    for (LegRow l : legs) {
      database.update(
          """
          INSERT INTO planning.route_legs
              (trip_id, leg_sequence, plan_id, depot_code, from_outlet_id, to_outlet_id,
               planned_departure, planned_arrival, planned_minutes)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          l.tripId(),
          l.legSequence(),
          planId,
          depotCode,
          l.fromOutletId().orElse(null),
          l.toOutletId(),
          Time.valueOf(l.plannedDeparture()),
          Time.valueOf(l.plannedArrival()),
          l.plannedMinutes());
    }
  }

  public void insertFuel(UUID planId, String depotCode, LocalDate serviceDate, List<FuelRow> fuel) {
    for (FuelRow f : fuel) {
      database.update(
          """
          INSERT INTO planning.fuel_usage
              (plan_id, vehicle_id, depot_code, service_date, week_starting, litres)
          VALUES (?, ?, ?, ?, ?, ?)
          """,
          planId,
          f.vehicleId(),
          depotCode,
          Date.valueOf(serviceDate),
          Date.valueOf(weekStarting(serviceDate)),
          f.litres());
    }
  }

  /** Draft to published. The unique index refuses a second published plan for the day. */
  public long publish(UUID planId, long expectedVersion, UUID publishedBy, Instant at) {
    database.updateExpectingOneRow(
        """
        UPDATE planning.runs
           SET status = 'published', published_at = ?, published_by = ?,
               row_version = row_version + 1, updated_at = ?
         WHERE plan_id = ? AND row_version = ? AND status = 'draft'
        """,
        Timestamp.from(at),
        publishedBy,
        Timestamp.from(at),
        planId,
        expectedVersion);
    return expectedVersion + 1;
  }

  /** Published to superseded: the one change a published plan admits (R-PLN-28). */
  public long supersede(UUID planId, long expectedVersion, Instant at) {
    database.updateExpectingOneRow(
        """
        UPDATE planning.runs SET status = 'superseded', row_version = row_version + 1, updated_at = ?
         WHERE plan_id = ? AND row_version = ? AND status = 'published'
        """,
        Timestamp.from(at),
        planId,
        expectedVersion);
    return expectedVersion + 1;
  }

  public long cancel(UUID planId, long expectedVersion, Instant at) {
    database.updateExpectingOneRow(
        """
        UPDATE planning.runs SET status = 'cancelled', row_version = row_version + 1, updated_at = ?
         WHERE plan_id = ? AND row_version = ? AND status = 'draft'
        """,
        Timestamp.from(at),
        planId,
        expectedVersion);
    return expectedVersion + 1;
  }

  /** Demand, reference data or the calendar changed under a draft (decision 7). */
  public long markStale(UUID planId, long expectedVersion, Instant at) {
    database.updateExpectingOneRow(
        """
        UPDATE planning.runs SET stale = true, row_version = row_version + 1, updated_at = ?
         WHERE plan_id = ? AND row_version = ? AND status = 'draft'
        """,
        Timestamp.from(at),
        planId,
        expectedVersion);
    return expectedVersion + 1;
  }

  // ---- mapping -------------------------------------------------------------------

  public static LocalDate weekStarting(LocalDate anyDay) {
    return anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  public static String code(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  private static PlanStatus status(Object code) {
    return PlanStatus.valueOf(String.valueOf(code).toUpperCase(Locale.ROOT));
  }

  private static AllocationDecision decision(Object code) {
    return AllocationDecision.valueOf(String.valueOf(code).toUpperCase(Locale.ROOT));
  }

  private static Optional<LocalTime> time(Object value) {
    return Optional.ofNullable((Time) value).map(Time::toLocalTime);
  }

  private PriorityPolicy policy(Map<String, Object> row) {
    try {
      List<String> keys = json.readValue((String) row.get("keys"), new TypeReference<List<String>>() {});
      return PriorityPolicy.parse((UUID) row.get("policy_version_id"), keys);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("unreadable priority policy " + row.get("policy_version_id"), e);
    }
  }

  private String writeChecks(List<ConstraintResult> checks) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (ConstraintResult c : checks) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("ruleId", c.ruleId());
      m.put("constraint", c.constraint());
      m.put("passed", c.passed());
      m.put("reason", c.reason());
      m.put("slack", c.slack().map(BigDecimal::toPlainString).orElse(null));
      out.add(m);
    }
    try {
      return json.writeValueAsString(out);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("constraint results could not be written", e);
    }
  }

  private List<ConstraintResult> readChecks(String text) {
    try {
      List<ConstraintResult> checks = new ArrayList<>();
      for (Map<String, Object> m : json.readValue(text, CHECKS)) {
        checks.add(
            new ConstraintResult(
                (String) m.get("ruleId"),
                (String) m.get("constraint"),
                Boolean.TRUE.equals(m.get("passed")),
                (String) m.get("reason"),
                Optional.ofNullable(m.get("slack")).map(s -> new BigDecimal(s.toString()))));
      }
      return checks;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("unreadable constraint results", e);
    }
  }

  private Optional<RunRow> oneRun(String sql, Object... params) {
    List<Map<String, Object>> rows = database.query(sql, params);
    return rows.isEmpty() ? Optional.empty() : Optional.of(run(rows.get(0)));
  }

  private static RunRow run(Map<String, Object> row) {
    return new RunRow(
        (UUID) row.get("plan_id"),
        (String) row.get("depot_code"),
        ((Date) row.get("service_date")).toLocalDate(),
        ((Number) row.get("plan_version")).intValue(),
        status(row.get("status")),
        (UUID) row.get("reference_version_id"),
        (UUID) row.get("rule_set_id"),
        (UUID) row.get("priority_policy_version_id"),
        Optional.ofNullable((UUID) row.get("supersedes")),
        (String) row.get("demand_fingerprint"),
        (Boolean) row.get("stale"),
        (Boolean) row.get("partial"),
        (String) row.get("engine"),
        (Boolean) row.get("planned_without_predictor"),
        ((Timestamp) row.get("generated_at")).toInstant(),
        (UUID) row.get("generated_by"),
        Optional.ofNullable((Timestamp) row.get("published_at")).map(Timestamp::toInstant),
        Optional.ofNullable((UUID) row.get("published_by")),
        ((Number) row.get("row_version")).longValue());
  }
}
