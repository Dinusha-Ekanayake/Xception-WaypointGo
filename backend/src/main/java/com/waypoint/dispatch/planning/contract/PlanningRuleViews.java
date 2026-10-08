package com.waypoint.dispatch.planning.contract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Effective planning configuration, including a future scheduled successor. */
public final class PlanningRuleViews {
  private PlanningRuleViews() {}

  public record Parameter(String key, String category, String label, String unit,
                          String control, boolean editable, long minimum, long maximum,
                          BigDecimal value) {}

  public record RuleSetView(UUID ruleSetId, long rowVersion, LocalDate effectiveFrom,
                            LocalDate effectiveTo, String note, List<Parameter> parameters) {}

  /**
   * A parameter's value as the depot actually works it (R-PLN-47): for
   * {@code trip.reload.min}, the median reload of the last weeks and the minutes
   * three in four stay within, from at least ten released second trips.
   */
  public record Measured(String key, String depotCode, long median, long p75, int samples,
                         LocalDate from, LocalDate to) {}

  public record Catalogue(RuleSetView active, RuleSetView scheduled, List<Measured> measured) {}
}
