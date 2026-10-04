package com.waypoint.dispatch.intelligence.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What the attention watch found on live trips (issue #268), as other modules and the screens read it. */
public final class AttentionViews {
  private AttentionViews() {}

  /** The command kind, and the action a policy grants: {@code depotCode}, {@code deliveryId}, {@code kind}, {@code reason}. */
  public static final String ACKNOWLEDGE = "ml:AcknowledgeAttention";

  public enum AttentionKind {
    FAILED_STOP,
    WINDOW_AT_RISK,
    RUNNING_LATE,
    PROOF_OWED
  }

  public enum AttentionSeverity {
    CRITICAL,
    HIGH,
    MEDIUM
  }

  /**
   * @param minutesLeft minutes until the outlet's window closes, where a clock applies
   * @param remindedCount how many times the dispatcher has been reminded of it
   */
  public record AttentionItemView(
      UUID deliveryId,
      AttentionKind kind,
      AttentionSeverity severity,
      String depotCode,
      LocalDate serviceDate,
      String vehicleId,
      UUID tripId,
      String outletId,
      Optional<Integer> minutesLeft,
      Instant raisedAt,
      int remindedCount,
      long rowVersion) {}

  /**
   * The open items for a depot and day, most urgent first.
   *
   * @param checkedAt when the watch last looked at this depot; empty when it never has
   * @param stale true when the watch has not looked for longer than it should: the list may be out of date
   */
  public record AttentionBoardView(
      String depotCode, LocalDate serviceDate, List<AttentionItemView> items, Optional<Instant> checkedAt, boolean stale) {

    public AttentionBoardView {
      items = List.copyOf(items);
    }
  }
}
