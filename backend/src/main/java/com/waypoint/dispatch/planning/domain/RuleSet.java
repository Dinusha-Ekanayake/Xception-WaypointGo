package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

/**
 * The thresholds the constraints read. Predicates are code; numbers are
 * effective-dated parameters (MODULES §4), so changing a budget is data, not a
 * release.
 *
 * <p>A missing parameter refuses the run. There is deliberately no compiled-in
 * default: it would silently reintroduce an old value (POL-10).
 */
public record RuleSet(UUID id, Map<String, BigDecimal> parameters) {
  public static final String FRESH_BUDGET_MIN = "fresh.budget.min";
  public static final String DAYTIME_BUDGET_MIN = "daytime.budget.min";
  public static final String MAX_TRIPS = "trips.max.per.vehicle";
  public static final String CAPACITY_EPSILON = "capacity.epsilon";
  public static final String FRESH_DEPARTURE_MIN = "fresh.departure.minute.of.day";
  public static final String DAYTIME_DEPARTURE_MIN = "daytime.departure.minute.of.day";
  public static final String ESCALATION_SKIPS = "escalation.skips";
  public static final String STRICT_WINDOW_MIN = "strict.window.min";
  public static final String CADENCE_DAYS_PREFIX = "cadence.days.";
  public static final String ENGINE_BUDGET_MS = "engine.budget.ms";
  /** 1 to count the drive back from a trip's district before the next trip leaves; 0, the booklet's model (A-26). */
  public static final String RETURN_COUNTED = "trip.return.counted";
  /** Minutes at the depot between trips, reloading; 0 when absent. */
  public static final String RELOAD_MIN = "trip.reload.min";

  public RuleSet {
    parameters = Map.copyOf(parameters);
  }

  /** The booklet's values: P-01 to P-08, P-12, P-15 and P-17 to P-19. */
  public static Map<String, BigDecimal> bookletParameters() {
    return Map.ofEntries(
        Map.entry(FRESH_BUDGET_MIN, new BigDecimal("270")),
        Map.entry(DAYTIME_BUDGET_MIN, new BigDecimal("480")),
        Map.entry(MAX_TRIPS, new BigDecimal("2")),
        Map.entry(CAPACITY_EPSILON, new BigDecimal("0.000001")),
        Map.entry(FRESH_DEPARTURE_MIN, new BigDecimal("210")),
        Map.entry(DAYTIME_DEPARTURE_MIN, new BigDecimal("480")),
        Map.entry(ESCALATION_SKIPS, new BigDecimal("1")),
        Map.entry(STRICT_WINDOW_MIN, new BigDecimal("120")),
        Map.entry(CADENCE_DAYS_PREFIX + "Fresh", new BigDecimal("1")),
        Map.entry(CADENCE_DAYS_PREFIX + "Style", new BigDecimal("7")),
        Map.entry(CADENCE_DAYS_PREFIX + "Tech", new BigDecimal("1")),
        Map.entry(ENGINE_BUDGET_MS, new BigDecimal("10000")));
  }

  public BigDecimal require(String key) {
    BigDecimal value = parameters.get(key);
    if (value == null) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "rule parameter " + key + " has no value for this date; planning refuses rather than guess",
          java.util.List.of("POL-10"));
    }
    return value;
  }

  public BigDecimal freshBudgetMinutes() {
    return require(FRESH_BUDGET_MIN);
  }

  public BigDecimal daytimeBudgetMinutes() {
    return require(DAYTIME_BUDGET_MIN);
  }

  public int maxTrips() {
    return require(MAX_TRIPS).intValueExact();
  }

  public BigDecimal epsilon() {
    return require(CAPACITY_EPSILON);
  }

  public LocalTime freshDeparture() {
    return LocalTime.MIDNIGHT.plusMinutes(require(FRESH_DEPARTURE_MIN).longValueExact());
  }

  public LocalTime daytimeDeparture() {
    return LocalTime.MIDNIGHT.plusMinutes(require(DAYTIME_DEPARTURE_MIN).longValueExact());
  }

  public int escalationSkips() {
    return require(ESCALATION_SKIPS).intValueExact();
  }

  public long strictWindowMinutes() {
    return require(STRICT_WINDOW_MIN).longValueExact();
  }

  /** Days until the brand's next run, so deferring a weekly order costs a week (P-18). */
  public int cadenceDays(String brand) {
    return require(CADENCE_DAYS_PREFIX + brand).intValueExact();
  }

  /**
   * A parameter an engine version reads only to override its own constant, such
   * as the cost stage's iteration count. Absent means the engine's value, which
   * is part of its name, so no old value can come back silently (POL-10).
   */
  public java.util.Optional<BigDecimal> optional(String key) {
    return java.util.Optional.ofNullable(parameters.get(key));
  }

  /**
   * Whether a second trip waits for the drive back from the first trip's
   * district (R-PLN-47). Absent, as in the booklet's own parameters, means no:
   * the booklet's trip time leaves the return out (A-26).
   */
  public boolean returnCounted() {
    return optional(RETURN_COUNTED).map(v -> v.signum() > 0).orElse(false);
  }

  /** Minutes reloading at the depot before a second trip (R-PLN-47); 0 when absent. */
  public BigDecimal reloadMinutes() {
    return optional(RELOAD_MIN).orElse(BigDecimal.ZERO);
  }

  /**
   * The depot's values: the booklet's, with a second trip leaving only after
   * the drive back and a reload (R-PLN-47). The reload time is a placeholder
   * until the depot gives its own.
   */
  public static Map<String, BigDecimal> depotParameters() {
    Map<String, BigDecimal> out = new java.util.HashMap<>(bookletParameters());
    out.put(RETURN_COUNTED, BigDecimal.ONE);
    out.put(RELOAD_MIN, new BigDecimal("20"));
    return Map.copyOf(out);
  }

  public long engineBudgetMillis() {
    return require(ENGINE_BUDGET_MS).longValueExact();
  }

  /** Every key the domain reads, so a rule set can be checked complete before it is used. */
  public static java.util.Set<String> requiredKeys(java.util.Collection<String> brands) {
    java.util.Set<String> keys =
        new java.util.TreeSet<>(
            java.util.List.of(
                FRESH_BUDGET_MIN,
                DAYTIME_BUDGET_MIN,
                MAX_TRIPS,
                CAPACITY_EPSILON,
                FRESH_DEPARTURE_MIN,
                DAYTIME_DEPARTURE_MIN,
                ESCALATION_SKIPS,
                STRICT_WINDOW_MIN,
                ENGINE_BUDGET_MS));
    brands.forEach(b -> keys.add(CADENCE_DAYS_PREFIX + b));
    return keys;
  }
}
