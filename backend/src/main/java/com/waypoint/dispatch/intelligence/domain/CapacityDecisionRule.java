package com.waypoint.dispatch.intelligence.domain;

import com.waypoint.dispatch.intelligence.contract.CapacityViews.CapacityAction;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.Arrays;
import java.util.List;

/**
 * What makes a capacity decision complete (roadmap T2-01). Pure: the handler
 * reads the payload, this decides whether it can be recorded.
 *
 * <p>A reason is always required (rule 8). Hiring names how many vehicles, so
 * the next week's plan can be checked against it; other actions may.
 */
public final class CapacityDecisionRule {
  public static final int MIN_REASON = 3;
  public static final int MAX_REASON = 500;
  public static final int MAX_VEHICLES = 60;

  private CapacityDecisionRule() {}

  public static CapacityAction action(String text) {
    return Arrays.stream(CapacityAction.values()).filter(a -> a.name().equals(text)).findFirst().orElseThrow(
        () -> new DomainException(ErrorCode.VALIDATION_FAILED, "action is one of " + Arrays.toString(CapacityAction.values())));
  }

  public static void check(int isoYear, int isoWeek, CapacityAction action, Integer vehicles, String reason) {
    if (isoWeek < 1 || isoWeek > 53 || isoYear < 2000 || isoYear > 2100) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "isoYear and isoWeek name a real week");
    }
    if (reason == null || reason.trim().length() < MIN_REASON || reason.length() > MAX_REASON) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED,
          "reason needs " + MIN_REASON + " to " + MAX_REASON + " characters", List.of("rule 8"));
    }
    if (vehicles != null && (vehicles < 1 || vehicles > MAX_VEHICLES)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "vehicles is between 1 and " + MAX_VEHICLES);
    }
    if (action == CapacityAction.HIRE_VEHICLES && vehicles == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "hiring names how many vehicles");
    }
  }
}
