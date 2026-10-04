package com.waypoint.dispatch.receipt.domain;

import static com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus.AUTO_CLOSED;
import static com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus.CONFIRMED;
import static com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus.DISPUTED;
import static com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus.PARTIAL;
import static com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus.PENDING;

import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The legal moves of a receipt, and the only place they are defined.
 *
 * <p>A receipt starts {@code PENDING} when the delivery is recorded and the store
 * answers once: confirmed, partial or disputed. Silence becomes {@code AUTO_CLOSED}
 * after the window (R-RCP-05), never a confirmation. Auto-close is a state, not a
 * deadline for the truth: a shortage reported after it is still accepted (RCP-08),
 * so {@code AUTO_CLOSED} may still move to {@code PARTIAL} or {@code DISPUTED}.
 * A disputed receipt never auto-closes, because the store did answer.
 */
public final class ReceiptStateMachine {
  private ReceiptStateMachine() {}

  private static final Map<ReceiptStatus, Set<ReceiptStatus>> EDGES = new EnumMap<>(ReceiptStatus.class);

  static {
    EDGES.put(PENDING, EnumSet.of(CONFIRMED, PARTIAL, DISPUTED, AUTO_CLOSED));
    EDGES.put(AUTO_CLOSED, EnumSet.of(PARTIAL, DISPUTED));
    EDGES.put(CONFIRMED, EnumSet.noneOf(ReceiptStatus.class));
    EDGES.put(PARTIAL, EnumSet.noneOf(ReceiptStatus.class));
    EDGES.put(DISPUTED, EnumSet.noneOf(ReceiptStatus.class));
  }

  public static final Set<ReceiptStatus> TERMINAL = EnumSet.of(CONFIRMED, PARTIAL, DISPUTED);

  public static boolean isEdge(ReceiptStatus from, ReceiptStatus to) {
    return EDGES.get(from).contains(to);
  }

  public static Set<ReceiptStatus> next(ReceiptStatus from) {
    return EDGES.get(from).isEmpty() ? EnumSet.noneOf(ReceiptStatus.class) : EnumSet.copyOf(EDGES.get(from));
  }

  /** Throws unless {@code from -> to} is one legal edge. */
  public static void require(ReceiptStatus from, ReceiptStatus to) {
    if (!isEdge(from, to)) {
      throw new DomainException(
          ErrorCode.CONFLICT,
          "A receipt cannot move from " + from + " to " + to + "; legal next states are " + next(from),
          List.of("R-RCP-05"));
    }
  }
}
