package com.waypoint.dispatch.intelligence.contract;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the dispatcher decided about a week the capacity plan shows short
 * (roadmap T2-01): append only, so a change of mind is a second decision and
 * the trail keeps both.
 */
public final class CapacityViews {
  private CapacityViews() {}

  /**
   * The command kind, and the action a policy grants. Payload: {@code depotCode},
   * {@code isoYear}, {@code isoWeek}, {@code action}, {@code vehicles} (optional),
   * {@code refrigerated}, {@code reason}.
   */
  public static final String RECORD = "ml:RecordCapacityDecision";

  public enum CapacityAction {
    HIRE_VEHICLES,
    MOVE_WORKSHOP,
    ADVISE_STORES,
    ACCEPT_SHORTFALL,
    OTHER
  }

  public record CapacityDecisionView(
      UUID decisionId,
      String depotCode,
      int isoYear,
      int isoWeek,
      CapacityAction action,
      Optional<Integer> vehicles,
      boolean refrigerated,
      String reason,
      UUID decidedBy,
      Instant decidedAt) {}
}
