package com.waypoint.dispatch.intelligence.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Roadmap B-05: a depot's business KPIs week by week, as recorded and as the app counts them. */
public final class KpiViews {
  /** The command that records a week's figures (append only; the newest per KPI is current). */
  public static final String RECORD = "ml:RecordKpiWeek";

  private KpiViews() {}

  /** One recorded figure: who entered it and when (decisions are recorded). */
  public record KpiEntryView(BigDecimal value, Optional<String> note, UUID enteredBy, Instant enteredAt) {}

  /** A figure the app counted, and how many stops it rests on. */
  public record KpiCountedView(BigDecimal value, int stops) {}

  /** One week from its Monday: the recorded figures by KPI and the counted ones. */
  public record KpiWeekView(LocalDate weekStart, Map<String, KpiEntryView> recorded, Map<String, KpiCountedView> counted) {}

  /** The KPIs in screen order, which the app counts, and the weeks newest first. */
  public record KpiReportView(String depotCode, List<String> kpis, List<String> countedByApp, List<KpiWeekView> weeks) {}
}
