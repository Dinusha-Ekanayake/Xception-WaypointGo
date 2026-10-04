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

  public record Catalogue(RuleSetView active, RuleSetView scheduled) {}
}
