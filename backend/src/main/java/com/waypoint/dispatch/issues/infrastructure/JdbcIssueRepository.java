package com.waypoint.dispatch.issues.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueSeverity;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueStatus;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueType;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.issues.domain.Issue;
import com.waypoint.dispatch.issues.domain.Issue.Resolution;
import com.waypoint.dispatch.issues.domain.ResolutionAction;
import com.waypoint.dispatch.platform.db.Database;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Issues in PostgreSQL. Always called inside a transaction someone else opened,
 * as {@code waypoint_issues}, so row-level security has already narrowed what
 * any statement here can see or write.
 *
 * <p>Subjects come back with their issue as one JSON aggregate, so a page of
 * issues is one query rather than one per issue.
 */
@Repository
public class JdbcIssueRepository {
  private static final String COLUMNS =
      """
      i.issue_id, i.issue_type, i.severity, i.severity_rank, i.status, i.depot_code, i.outlet_id, i.description,
      i.investigation, i.source_key, i.assignee_user_id, i.resolution_action, i.resolution_note, i.resolved_by,
      i.resolved_at, i.redelivery_requested_at, i.escalated_at, i.raised_by, i.raised_at, i.row_version,
      (SELECT coalesce(json_agg(json_build_array(s.subject_type, s.subject_id)
                                ORDER BY s.subject_type, s.subject_id), '[]'::json)
         FROM issues.issue_subjects s WHERE s.issue_id = i.issue_id)::text AS subjects
      """;

  private static final TypeReference<List<List<String>>> PAIRS = new TypeReference<>() {};

  private final Database database;
  private final ObjectMapper json;

  public JdbcIssueRepository(Database database, ObjectMapper json) {
    this.database = database;
    this.json = json;
  }

  /** An issue and its sort key for the inbox. */
  public record Stored(Issue issue, int severityRank) {}

  // ---- reads ---------------------------------------------------------------

  public Optional<Stored> find(UUID issueId) {
    return one("SELECT " + COLUMNS + " FROM issues.issues i WHERE i.issue_id = ?", issueId);
  }

  public Optional<Stored> findBySourceKey(String sourceKey) {
    return one("SELECT " + COLUMNS + " FROM issues.issues i WHERE i.source_key = ?", sourceKey);
  }

  /**
   * Open and assigned issues of a depot, most severe first, then oldest, on the
   * keyset {@code (severity_rank DESC, raised_at, issue_id)}; never OFFSET.
   */
  public List<Stored> openForDepot(
      String depotCode, Optional<Integer> afterRank, Optional<Instant> afterRaised, Optional<UUID> afterId, int limit) {
    if (afterRank.isEmpty()) {
      return many(
          "SELECT " + COLUMNS + " FROM issues.issues i"
              + " WHERE i.depot_code = ? AND i.status IN ('open','assigned')"
              + " ORDER BY i.severity_rank DESC, i.raised_at, i.issue_id LIMIT ?",
          depotCode,
          limit);
    }
    // Descending rank and ascending time cannot share one row comparison, so the
    // keyset is spelled out: a lower rank, or the same rank and a later position.
    return many(
        "SELECT " + COLUMNS + " FROM issues.issues i"
            + " WHERE i.depot_code = ? AND i.status IN ('open','assigned')"
            + " AND (i.severity_rank < ? OR (i.severity_rank = ? AND (i.raised_at, i.issue_id) > (?, ?)))"
            + " ORDER BY i.severity_rank DESC, i.raised_at, i.issue_id LIMIT ?",
        depotCode,
        afterRank.get(),
        afterRank.get(),
        Timestamp.from(afterRaised.orElseThrow()),
        afterId.orElseThrow(),
        limit);
  }

  /** Every issue about a subject, newest first, whatever its status. */
  public List<Stored> about(SubjectRef subject) {
    return many(
        "SELECT " + COLUMNS + " FROM issues.issues i WHERE EXISTS (SELECT 1 FROM issues.issue_subjects s"
            + " WHERE s.issue_id = i.issue_id AND s.subject_type = ? AND s.subject_id = ?)"
            + " ORDER BY i.raised_at DESC, i.issue_id",
        subject.type(),
        subject.id());
  }

  /** Open, unassigned and not yet escalated: what the escalation job weighs (an escalation timer). */
  public List<Stored> awaitingAssignment() {
    return many(
        "SELECT " + COLUMNS + " FROM issues.issues i WHERE i.status = 'open' AND i.escalated_at IS NULL"
            + " ORDER BY i.raised_at, i.issue_id");
  }

  /** Active issues by severity and the oldest raise time, for the gauges. */
  public record Backlog(Map<IssueSeverity, Long> openBySeverity, Optional<Instant> oldestRaisedAt) {}

  public Backlog backlog() {
    Map<IssueSeverity, Long> counts = new HashMap<>();
    for (IssueSeverity s : IssueSeverity.values()) {
      counts.put(s, 0L);
    }
    Instant oldest = null;
    for (Map<String, Object> row :
        database.query(
            "SELECT severity, count(*) AS n, min(raised_at) AS oldest FROM issues.issues"
                + " WHERE status IN ('open','assigned') GROUP BY severity")) {
      counts.put(severity(row.get("severity")), ((Number) row.get("n")).longValue());
      Instant o = instant(row.get("oldest"));
      oldest = oldest == null || o.isBefore(oldest) ? o : oldest;
    }
    return new Backlog(counts, Optional.ofNullable(oldest));
  }

  public Map<String, String> parameters(LocalDate date) {
    Map<String, String> values = new HashMap<>();
    for (Map<String, Object> row :
        database.query(
            "SELECT parameter_key, parameter_value FROM issues.parameters"
                + " WHERE effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)",
            Date.valueOf(date),
            Date.valueOf(date))) {
      values.put((String) row.get("parameter_key"), (String) row.get("parameter_value"));
    }
    return values;
  }

  public record HistoryEntry(
      Optional<IssueStatus> from,
      IssueStatus to,
      String action,
      String reason,
      Optional<UUID> actorId,
      Instant at) {}

  public List<HistoryEntry> history(UUID issueId) {
    List<HistoryEntry> out = new ArrayList<>();
    for (Map<String, Object> row :
        database.query(
            "SELECT from_status, to_status, action, reason, actor_id, occurred_at FROM issues.issue_history"
                + " WHERE issue_id = ? ORDER BY history_id",
            issueId)) {
      out.add(
          new HistoryEntry(
              Optional.ofNullable((String) row.get("from_status")).map(JdbcIssueRepository::status),
              status(row.get("to_status")),
              (String) row.get("action"),
              (String) row.get("reason"),
              Optional.ofNullable((UUID) row.get("actor_id")),
              instant(row.get("occurred_at"))));
    }
    return out;
  }

  // ---- writes --------------------------------------------------------------

  /** @return false when an issue from the same source event already exists */
  public boolean insert(Issue issue, Instant at) {
    int inserted =
        database.update(
            """
            INSERT INTO issues.issues
                (issue_id, issue_type, severity, status, depot_code, outlet_id, description, investigation,
                 source_key, assignee_user_id, raised_by, raised_at, row_version, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
            ON CONFLICT (source_key) DO NOTHING
            """,
            issue.issueId(),
            code(issue.type()),
            code(issue.severity()),
            code(issue.status()),
            issue.depotCode(),
            issue.outletId().orElse(null),
            issue.description(),
            issue.investigation(),
            issue.sourceKey().orElse(null),
            issue.assignee().orElse(null),
            issue.raisedBy(),
            Timestamp.from(issue.raisedAt()),
            Timestamp.from(at));
    if (inserted == 0) {
      return false;
    }
    for (SubjectRef s : issue.subjects()) {
      database.update(
          "INSERT INTO issues.issue_subjects (issue_id, subject_type, subject_id) VALUES (?, ?, ?)",
          issue.issueId(),
          s.type(),
          s.id());
    }
    return true;
  }

  /** Writes {@code next} over the row at {@code expectedVersion}; returns the new version. */
  public long update(Issue next, long expectedVersion, Instant at) {
    Optional<Resolution> r = next.resolution();
    database.updateExpectingOneRow(
        """
        UPDATE issues.issues
           SET status = ?, assignee_user_id = ?, resolution_action = ?, resolution_note = ?, resolved_by = ?,
               resolved_at = ?, redelivery_requested_at = ?, escalated_at = ?,
               row_version = row_version + 1, updated_at = ?
         WHERE issue_id = ? AND row_version = ?
        """,
        code(next.status()),
        next.assignee().orElse(null),
        r.map(x -> code(x.action())).orElse(null),
        r.map(Resolution::note).orElse(null),
        r.map(Resolution::by).orElse(null),
        r.map(x -> Timestamp.from(x.at())).orElse(null),
        next.redeliveryRequestedAt().map(Timestamp::from).orElse(null),
        next.escalatedAt().map(Timestamp::from).orElse(null),
        Timestamp.from(at),
        next.issueId(),
        expectedVersion);
    return expectedVersion + 1;
  }

  public void record(
      UUID issueId,
      Optional<IssueStatus> from,
      IssueStatus to,
      String action,
      String reason,
      UUID actorId,
      Optional<UUID> eventId,
      Instant at) {
    database.update(
        """
        INSERT INTO issues.issue_history (issue_id, from_status, to_status, action, reason, actor_id, event_id,
                                          occurred_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        issueId,
        from.map(JdbcIssueRepository::code).orElse(null),
        code(to),
        action,
        reason,
        actorId,
        eventId.orElse(null),
        Timestamp.from(at));
  }

  // ---- mapping -------------------------------------------------------------

  public static String code(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  public static IssueStatus status(Object code) {
    return IssueStatus.valueOf(String.valueOf(code).toUpperCase(Locale.ROOT));
  }

  private static IssueSeverity severity(Object code) {
    return IssueSeverity.valueOf(String.valueOf(code).toUpperCase(Locale.ROOT));
  }

  private Optional<Stored> one(String sql, Object... params) {
    List<Stored> found = many(sql, params);
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  private List<Stored> many(String sql, Object... params) {
    List<Stored> out = new ArrayList<>();
    for (Map<String, Object> row : database.query(sql, params)) {
      out.add(new Stored(map(row), ((Number) row.get("severity_rank")).intValue()));
    }
    return out;
  }

  private Issue map(Map<String, Object> row) {
    Optional<Resolution> resolution =
        Optional.ofNullable((String) row.get("resolution_action"))
            .map(
                a ->
                    new Resolution(
                        ResolutionAction.valueOf(a.toUpperCase(Locale.ROOT)),
                        (String) row.get("resolution_note"),
                        (UUID) row.get("resolved_by"),
                        instant(row.get("resolved_at"))));
    return new Issue(
        (UUID) row.get("issue_id"),
        IssueType.valueOf(String.valueOf(row.get("issue_type")).toUpperCase(Locale.ROOT)),
        severity(row.get("severity")),
        status(row.get("status")),
        (String) row.get("depot_code"),
        Optional.ofNullable((String) row.get("outlet_id")),
        subjects((String) row.get("subjects")),
        (String) row.get("description"),
        (Boolean) row.get("investigation"),
        Optional.ofNullable((String) row.get("source_key")),
        Optional.ofNullable((UUID) row.get("assignee_user_id")),
        resolution,
        Optional.ofNullable(row.get("redelivery_requested_at")).map(JdbcIssueRepository::instant),
        Optional.ofNullable(row.get("escalated_at")).map(JdbcIssueRepository::instant),
        (UUID) row.get("raised_by"),
        instant(row.get("raised_at")),
        ((Number) row.get("row_version")).longValue());
  }

  private List<SubjectRef> subjects(String text) {
    try {
      return json.readValue(text, PAIRS).stream().map(p -> new SubjectRef(p.get(0), p.get(1))).toList();
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("unreadable issue subjects", e);
    }
  }

  private static Instant instant(Object value) {
    if (value instanceof Timestamp t) {
      return t.toInstant();
    }
    if (value instanceof OffsetDateTime o) {
      return o.toInstant();
    }
    throw new IllegalStateException("not a timestamp: " + value);
  }
}
