package com.waypoint.dispatch.issues.domain;

import static com.waypoint.dispatch.issues.contract.IssueViews.IssueStatus.ASSIGNED;
import static com.waypoint.dispatch.issues.contract.IssueViews.IssueStatus.CANCELLED;
import static com.waypoint.dispatch.issues.contract.IssueViews.IssueStatus.CLOSED;
import static com.waypoint.dispatch.issues.contract.IssueViews.IssueStatus.OPEN;
import static com.waypoint.dispatch.issues.contract.IssueViews.IssueStatus.RESOLVED;

import com.waypoint.dispatch.issues.contract.IssueViews.IssueStatus;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The legal moves of an issue, and the only place they are defined (conflict
 * B17, settled for the contract's states).
 *
 * <p>An issue is raised {@code OPEN}, may be assigned and reassigned, is resolved
 * with a recorded action and reason, and is closed once the resolution has been
 * checked. One raised in error is cancelled with a reason. Resolved, closed and
 * cancelled issues are history: a recurring problem is a new issue.
 */
public final class IssueLifecycle {
  private IssueLifecycle() {}

  private static final Map<IssueStatus, Set<IssueStatus>> EDGES = new EnumMap<>(IssueStatus.class);

  static {
    EDGES.put(OPEN, EnumSet.of(ASSIGNED, RESOLVED, CANCELLED));
    EDGES.put(ASSIGNED, EnumSet.of(ASSIGNED, RESOLVED, CANCELLED));
    EDGES.put(RESOLVED, EnumSet.of(CLOSED));
    EDGES.put(CLOSED, EnumSet.noneOf(IssueStatus.class));
    EDGES.put(CANCELLED, EnumSet.noneOf(IssueStatus.class));
  }

  /** Waiting on someone: what the inbox shows and what escalation watches. */
  public static final Set<IssueStatus> ACTIVE = EnumSet.of(OPEN, ASSIGNED);

  public static boolean isEdge(IssueStatus from, IssueStatus to) {
    return EDGES.get(from).contains(to);
  }

  public static Set<IssueStatus> next(IssueStatus from) {
    return EDGES.get(from).isEmpty() ? EnumSet.noneOf(IssueStatus.class) : EnumSet.copyOf(EDGES.get(from));
  }

  public static void require(IssueStatus from, IssueStatus to) {
    if (!isEdge(from, to)) {
      throw new DomainException(
          ErrorCode.CONFLICT,
          "An issue cannot move from " + from + " to " + to + "; legal next states are " + next(from),
          List.of("R-ISS-01"));
    }
  }
}
