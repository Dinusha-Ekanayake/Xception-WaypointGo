package com.waypoint.dispatch.execution.domain;

import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The driver moves on before the store answered (issue #21, store-led handover).
 *
 * <p>Waiting for the store is never a gate (R-RCP-09), but leaving is a decision,
 * so it carries a reason from a closed list (rule 8) that the dispatcher can count.
 * Only a stop that was handed over has a store answer to wait for.
 */
public final class StoreAnswerWaiver {
  private StoreAnswerWaiver() {}

  public enum Reason {
    /** Nobody at the store could check the load. */
    STORE_ABSENT,
    /** The phone could not reach the server to see the answer. */
    NO_SIGNAL,
    /** The driver disagrees with what the store reported; an issue carries the detail. */
    DISAGREE;

    public String code() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Reason parse(String value) {
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED,
            "reason must be one of " + Arrays.stream(values()).map(Reason::code).collect(Collectors.joining(", ")));
      }
    }
  }

  /** Delivered in full or in part: the goods are with the store, so there is an answer to wait for. */
  public static void requireHandedOver(DeliveryOutcome outcome) {
    if (outcome != DeliveryOutcome.DELIVERED && outcome != DeliveryOutcome.PARTIAL) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "Only a handed-over stop waits for the store's answer; this one is " + outcome.name().toLowerCase(Locale.ROOT));
    }
  }
}
