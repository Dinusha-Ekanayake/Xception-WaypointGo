package com.waypoint.dispatch.issues.application;

import com.waypoint.dispatch.issues.contract.IssueViews.IssueSeverity;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueStatus;
import com.waypoint.dispatch.issues.domain.Issue;
import com.waypoint.dispatch.issues.domain.SeverityPolicy;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository.Backlog;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository.Stored;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * An escalation timer: "waiting forever" is not a state. An issue still open and
 * unassigned past its severity's deadline is stamped escalated, with a history
 * row and a metric, once (decision 8). It publishes no event until Notification
 * exists to route one; the dispatcher's inbox shows the stamp.
 *
 * <p>Also refreshes the backlog gauges: open issues by severity and the age of
 * the oldest, which are the detection signals for an inbox nobody is clearing.
 */
@Component
public class IssueEscalationJob implements ScheduledJob {
  private final Database database;
  private final JdbcIssueRepository issues;
  private final Metrics metrics;
  private final Map<IssueSeverity, AtomicLong> open = new EnumMap<>(IssueSeverity.class);
  private final AtomicLong oldestOpenSeconds = new AtomicLong();

  public IssueEscalationJob(Database database, JdbcIssueRepository issues, Metrics metrics) {
    this.database = database;
    this.issues = issues;
    this.metrics = metrics;
    for (IssueSeverity s : IssueSeverity.values()) {
      AtomicLong count = new AtomicLong();
      open.put(s, count);
      metrics.gauge("waypoint.issue.open", count::get, "severity", s.name());
    }
    metrics.gauge("waypoint.issue.oldest_open_seconds", oldestOpenSeconds::get);
  }

  @Override
  public String name() {
    return "issues.escalation";
  }

  @Override
  public String cron() {
    return "0 */5 * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ISSUES;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many issues were escalated */
  int runAt(Instant now) {
    SeverityPolicy policy =
        database.asSystem(
            ModuleRole.ISSUES,
            () -> SeverityPolicy.from(issues.parameters(now.atZone(Clock.OPERATING_ZONE).toLocalDate())));
    List<Issue> waiting =
        database.asSystem(ModuleRole.ISSUES, () -> issues.awaitingAssignment().stream().map(Stored::issue).toList());
    int escalated = 0;
    for (Issue candidate : waiting) {
      Duration deadline = policy.deadline(candidate.severity());
      if (!candidate.isOverdue(now, deadline)) {
        continue;
      }
      try {
        if (database.asSystem(ModuleRole.ISSUES, () -> escalate(candidate, now, deadline))) {
          escalated++;
        }
      } catch (DomainException | DataAccessException e) {
        metrics.increment("waypoint.issue.escalation_failed");
      }
    }
    Backlog backlog = database.asSystem(ModuleRole.ISSUES, issues::backlog);
    backlog.openBySeverity().forEach((s, n) -> open.get(s).set(n));
    oldestOpenSeconds.set(backlog.oldestRaisedAt().map(o -> Duration.between(o, now).toSeconds()).orElse(0L));
    return escalated;
  }

  /** Re-read inside the transaction: someone may have assigned it since the list was taken. */
  private boolean escalate(Issue seen, Instant now, Duration deadline) {
    Optional<Issue> found = issues.find(seen.issueId()).map(Stored::issue);
    if (found.isEmpty() || !found.get().isOverdue(now, deadline)) {
      return false;
    }
    Issue current = found.get();
    Issue next = current.escalate(now, deadline);
    issues.update(next, current.rowVersion(), now);
    issues.record(
        current.issueId(), Optional.of(IssueStatus.OPEN), IssueStatus.OPEN, "escalated",
        "unassigned past the " + current.severity() + " deadline of " + deadline.toMinutes() + " minutes",
        Actor.SYSTEM_ID, Optional.empty(), now);
    metrics.increment("waypoint.issue.escalated", "severity", current.severity().name());
    return true;
  }
}
