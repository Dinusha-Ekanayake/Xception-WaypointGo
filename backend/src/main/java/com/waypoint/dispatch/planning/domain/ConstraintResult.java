package com.waypoint.dispatch.planning.domain;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * One constraint's verdict on one vehicle's day.
 *
 * @param ruleId an identifier from RULES-AND-POLICIES, for example R-PLN-06
 * @param constraint the registry name, for example {@code VolumeCapacity}
 * @param reason human readable in both outcomes, so the UI can explain a pass too
 * @param slack remaining headroom in the rule's unit; negative by how much it failed
 */
public record ConstraintResult(
    String ruleId, String constraint, boolean passed, String reason, Optional<BigDecimal> slack) {

  public static ConstraintResult pass(String ruleId, String constraint, String reason, BigDecimal slack) {
    return new ConstraintResult(ruleId, constraint, true, reason, Optional.ofNullable(slack));
  }

  public static ConstraintResult fail(String ruleId, String constraint, String reason, BigDecimal slack) {
    return new ConstraintResult(ruleId, constraint, false, reason, Optional.ofNullable(slack));
  }
}
