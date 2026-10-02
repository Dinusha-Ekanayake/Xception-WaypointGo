package com.waypoint.dispatch.issues.application;

import com.waypoint.dispatch.issues.contract.IssueQuery;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueHistoryView;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueView;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.issues.domain.Issue;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository.Stored;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Cursor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Reads issues, for other modules through {@link IssueQuery} and for the
 * dispatcher's inbox and the people who raised them through the web layer.
 *
 * <p>Every read runs as {@code waypoint_issues} in a read-only transaction of its
 * own, so row-level security narrows it in SQL (rule 7): a depot's issues to its
 * dispatchers and loaders, an outlet's to its manager, and anyone's own reports
 * to them. One issue outside scope is {@code 404}; a depot inbox asked for
 * outside scope is {@code 403} plus an audit row, never an empty inbox.
 */
@Component
public class IssueDataQuery implements IssueQuery {
  public static final String READ = "issue:Read";

  private final Database database;
  private final JdbcIssueRepository issues;
  private final AuditLog audit;

  public IssueDataQuery(Database database, JdbcIssueRepository issues, AuditLog audit) {
    this.database = database;
    this.issues = issues;
    this.audit = audit;
  }

  // ---- contract: as the ambient actor --------------------------------------

  @Override
  public Page<IssueView> openIssues(String depotCode, Optional<String> cursor, int limit) {
    return read(ambient(), () -> open(depotCode, cursor, limit));
  }

  @Override
  public List<IssueView> issuesFor(SubjectRef subject) {
    return read(ambient(), () -> about(subject));
  }

  // ---- web: as the authenticated actor -------------------------------------

  public Page<IssueView> openIssues(Actor actor, String depotCode, Optional<String> cursor, Integer limit) {
    requireDepot(actor, depotCode);
    return read(actor.userId(), () -> open(depotCode, cursor, Page.limit(limit)));
  }

  public IssueView issue(Actor actor, UUID issueId) {
    return read(actor.userId(), () -> issues.find(issueId).map(IssueDataQuery::toView))
        .orElseThrow(() -> notFound(issueId));
  }

  public List<IssueHistoryView> history(Actor actor, UUID issueId) {
    return read(
        actor.userId(),
        () -> {
          if (issues.find(issueId).isEmpty()) {
            throw notFound(issueId);
          }
          return issues.history(issueId).stream()
              .map(h -> new IssueHistoryView(h.from(), h.to(), h.action(), h.reason(), h.actorId(), h.at()))
              .toList();
        });
  }

  /** Every issue about a subject the actor can see; others' issues are simply not in scope. */
  public List<IssueView> issuesFor(Actor actor, SubjectRef subject) {
    if (subject.type() == null || !Issue.SUBJECT_TYPES.contains(subject.type())) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "type must be one of " + Issue.SUBJECT_TYPES, List.of("R-ISS-03"));
    }
    return read(actor.userId(), () -> about(subject));
  }

  // ---- internals -----------------------------------------------------------

  private UUID ambient() {
    return database.ambientActor().orElse(null);
  }

  private <T> T read(UUID actorId, Supplier<T> work) {
    return database.readAs(ModuleRole.ISSUES, actorId, work);
  }

  private static DomainException notFound(UUID issueId) {
    return new DomainException(ErrorCode.NOT_FOUND, "No issue " + issueId);
  }

  private void requireDepot(Actor actor, String depotCode) {
    boolean inScope =
        read(
            actor.userId(),
            () -> Boolean.TRUE.equals(database.queryOne("SELECT app.actor_has_depot(?) AS ok", depotCode).get("ok")));
    if (!inScope) {
      String resource = "wpt:issue:depot:" + depotCode;
      String reason = "outside the actor's scope";
      audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), READ, resource, reason));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason);
    }
  }

  /** Most severe first, then oldest; the cursor is the last row's sort key and carries no personal data. */
  private Page<IssueView> open(String depotCode, Optional<String> cursor, int limit) {
    int size = Page.limit(limit);
    List<String> key = Cursor.decode(cursor.orElse(null), 3);
    Optional<Integer> rank = Optional.empty();
    Optional<Instant> raised = Optional.empty();
    Optional<UUID> id = Optional.empty();
    if (!key.isEmpty()) {
      try {
        rank = Optional.of(Integer.parseInt(key.get(0)));
        raised = Optional.of(Instant.parse(key.get(1)));
        id = Optional.of(UUID.fromString(key.get(2)));
      } catch (IllegalArgumentException | DateTimeParseException e) {
        throw Cursor.invalid();
      }
    }
    List<Stored> rows = issues.openForDepot(depotCode, rank, raised, id, size + 1);
    Page<Stored> page =
        Page.fromOverfetch(
            rows, size,
            s -> Cursor.encode(
                Integer.toString(s.severityRank()), s.issue().raisedAt().toString(), s.issue().issueId().toString()));
    return new Page<>(page.items().stream().map(IssueDataQuery::toView).toList(), page.nextCursor());
  }

  private List<IssueView> about(SubjectRef subject) {
    return issues.about(subject).stream().map(IssueDataQuery::toView).toList();
  }

  static IssueView toView(Stored stored) {
    Issue i = stored.issue();
    return new IssueView(
        i.issueId(),
        i.type(),
        i.severity(),
        i.status(),
        i.depotCode(),
        i.outletId(),
        i.subjects(),
        i.description(),
        i.assignee(),
        i.resolution().map(r -> r.action().name()),
        i.resolution().map(Issue.Resolution::note),
        i.raisedBy(),
        i.raisedAt(),
        i.resolution().map(Issue.Resolution::at),
        i.rowVersion());
  }
}
