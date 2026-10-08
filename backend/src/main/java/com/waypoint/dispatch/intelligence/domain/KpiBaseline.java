package com.waypoint.dispatch.intelligence.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The pilot's business KPIs (roadmap B-05), week by week per depot. A depot
 * records what it measured on paper; the three the app can count from its own
 * records are counted here, so the two can be read side by side.
 *
 * <p>Pure: the stops are facts, the zone is a parameter, nothing is read.
 */
public final class KpiBaseline {
  public static final String FRESH_ON_TIME = "fresh_on_time_pct";
  public static final String FRESH_BEFORE_8 = "fresh_before_8_pct";
  public static final String DEFERRALS = "deferrals_per_run";
  public static final String REPEAT_DEFERRALS = "repeat_deferrals";
  public static final String DISPUTES = "disputes_per_100";
  public static final String WRITE_OFFS = "write_off_lkr";
  public static final String CALLS = "calls_per_trip";
  public static final String ADOPTION = "adoption_pct";

  /** In the order the screen lists them. */
  public static final List<String> KPIS =
      List.of(FRESH_ON_TIME, FRESH_BEFORE_8, DEFERRALS, REPEAT_DEFERRALS, DISPUTES, WRITE_OFFS, CALLS, ADOPTION);

  private static final Set<String> PERCENT = Set.of(FRESH_ON_TIME, FRESH_BEFORE_8, ADOPTION);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  /** The ceiling of a figure that is not a share: no depot writes off a billion rupees a week by mistake twice. */
  private static final BigDecimal CEILING = new BigDecimal("999999999.99");
  private static final LocalTime EIGHT = LocalTime.of(8, 0);

  private KpiBaseline() {}

  /** A stop the app finished: its brand, outcome, lateness, arrival and whether the phone recorded it. */
  public record StopFact(LocalDate serviceDate, boolean fresh, String outcome, Integer lateMinutes,
                         Instant arrivedAt, boolean recordedOnPhone) {}

  /** A week's counted figures and how many stops each rests on. */
  public record Counted(BigDecimal value, int stops) {}

  /** The Monday of the week a date falls in. */
  public static LocalDate weekOf(LocalDate date) {
    return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  /** Refuses an unknown KPI, a week not starting on a Monday, a negative figure or a share above 100. */
  public static void check(String kpi, LocalDate weekStart, BigDecimal value) {
    if (!KPIS.contains(kpi)) throw new DomainException(ErrorCode.VALIDATION_FAILED, "Unknown KPI " + kpi);
    if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "A week starts on a Monday");
    }
    if (value.signum() < 0) throw new DomainException(ErrorCode.VALIDATION_FAILED, kpi + " cannot be negative");
    if (PERCENT.contains(kpi) && value.compareTo(HUNDRED) > 0) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, kpi + " is a share and cannot pass 100");
    }
    if (value.compareTo(CEILING) > 0) throw new DomainException(ErrorCode.VALIDATION_FAILED, kpi + " is too large");
  }

  /**
   * Per week, the KPIs the app counts: Fresh stops delivered by the window's
   * close, Fresh stops delivered with an arrival before 08:00, and the share of
   * finished stops whose arrival was recorded on the phone. A week with no stop
   * for a KPI has no figure for it, never a zero.
   */
  public static Map<LocalDate, Map<String, Counted>> count(List<StopFact> stops, ZoneId zone) {
    Map<LocalDate, Map<String, int[]>> tallies = new TreeMap<>();
    for (StopFact s : stops) {
      Map<String, int[]> week = tallies.computeIfAbsent(weekOf(s.serviceDate()), w -> new TreeMap<>());
      tally(week, ADOPTION, s.recordedOnPhone());
      boolean handed = "delivered".equals(s.outcome()) || "partial".equals(s.outcome());
      if (!s.fresh() || !handed) continue;
      tally(week, FRESH_ON_TIME, s.lateMinutes() == null || s.lateMinutes() <= 0);
      if (s.arrivedAt() != null) tally(week, FRESH_BEFORE_8, s.arrivedAt().atZone(zone).toLocalTime().isBefore(EIGHT));
    }
    Map<LocalDate, Map<String, Counted>> out = new TreeMap<>();
    tallies.forEach((week, kpis) -> {
      Map<String, Counted> figures = new TreeMap<>();
      kpis.forEach((kpi, t) -> figures.put(kpi, new Counted(share(t[0], t[1]), t[1])));
      out.put(week, figures);
    });
    return out;
  }

  private static void tally(Map<String, int[]> week, String kpi, boolean yes) {
    int[] t = week.computeIfAbsent(kpi, k -> new int[2]);
    if (yes) t[0]++;
    t[1]++;
  }

  private static BigDecimal share(int yes, int of) {
    return BigDecimal.valueOf(yes).multiply(HUNDRED).divide(BigDecimal.valueOf(of), 1, RoundingMode.HALF_UP);
  }
}
