package com.waypoint.dispatch.issues.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What an inquiry into an issue and the approval of its write-off must satisfy
 * (roadmap FF-03, policy P-DG-1). Pure: the people, the value and the limit go
 * in, a refusal with the rule comes out.
 */
public final class WriteOffRules {
  private WriteOffRules() {}

  /** A value above this is a typing slip, not a write-off of one order. */
  public static final BigDecimal MAX_VALUE_LKR = new BigDecimal("10000000");
  /** At most this many photos are named by one inquiry (R-ISS-10). */
  public static final int MAX_PHOTOS = 10;

  /** The outcome, trimmed: what the inquiry found, 3 to 1000 characters. */
  public static String requireOutcome(String outcome) {
    String trimmed = outcome == null ? "" : outcome.trim();
    if (trimmed.length() < 3 || trimmed.length() > 1000) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Say what the inquiry found, in 3 to 1000 characters");
    }
    return trimmed;
  }

  /** A write-off value in rupees, at most two decimals, from 0 to {@link #MAX_VALUE_LKR}. */
  public static BigDecimal requireValue(BigDecimal value) {
    if (value == null || value.signum() < 0 || value.compareTo(MAX_VALUE_LKR) > 0 || value.scale() > 2) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "A write-off value is in rupees, from 0 to 10,000,000, with at most two decimals");
    }
    return value;
  }

  public static List<UUID> requirePhotos(List<UUID> photos) {
    if (photos.size() > MAX_PHOTOS) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "An inquiry names at most " + MAX_PHOTOS + " photos");
    }
    return List.copyOf(photos);
  }

  /** Whether approving needs the Head of Distribution: above the depot limit, or any Tech line (P-DG-1). */
  public static boolean needsHead(BigDecimal value, boolean techLine, BigDecimal depotLimit) {
    return techLine || value.compareTo(depotLimit) > 0;
  }

  /**
   * Refuses an approval the policy does not allow: by the person who recorded the
   * write-off or who decided the issue (separation of duties), or above what the
   * approver's role may approve. A rejection is never limited by value.
   */
  public static void requireMayDecide(
      UUID approver,
      UUID recordedBy,
      Optional<UUID> issueDecidedBy,
      boolean approving,
      BigDecimal value,
      boolean techLine,
      BigDecimal depotLimit,
      boolean approverIsHead) {
    if (approver.equals(recordedBy)) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED, "Someone other than who recorded the write-off decides it", List.of("P-DG-1"));
    }
    if (issueDecidedBy.isPresent() && issueDecidedBy.get().equals(approver)) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED, "The person who decided the issue cannot approve its write-off", List.of("P-DG-1"));
    }
    if (approving && needsHead(value, techLine, depotLimit) && !approverIsHead) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          techLine
              ? "A Tech line is approved by the Head of Distribution"
              : "Above LKR " + depotLimit.toPlainString() + " the Head of Distribution approves",
          List.of("P-DG-1"));
    }
  }
}
