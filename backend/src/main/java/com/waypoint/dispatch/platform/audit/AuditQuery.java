package com.waypoint.dispatch.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Cursor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads the audit log (issue #6, {@code audit:Read}).
 *
 * <p>The caller has already been authorized for {@link #READ}. There is no row
 * scope: an auditor and an administrator read everything, and everyone else holds
 * no such grant, so they get {@code 403} and an audit row rather than an empty page.
 *
 * <p>Newest first, keyset paginated on {@code (occurred_at, audit_id)}. The cursor
 * carries a time and a number, never an actor or a target.
 */
@Component
public class AuditQuery {
  public static final String READ = "audit:Read";

  private final Database database;
  private final ObjectMapper mapper;
  private final Optional<PolicyHistory> policyHistory;

  public AuditQuery(Database database, ObjectMapper mapper, Optional<PolicyHistory> policyHistory) {
    this.database = database;
    this.mapper = mapper;
    this.policyHistory = policyHistory;
  }

  /** Every field optional; those given are combined with AND. */
  public record Filter(
      UUID actorId,
      String targetType,
      String targetId,
      String action,
      String decision,
      String correlationId,
      UUID commandId,
      Instant from,
      Instant to) {}

  public record AuditRowView(
      long auditId,
      Instant occurredAt,
      UUID actorId,
      UUID deviceId,
      String action,
      String resource,
      String decision,
      String reason,
      String correlationId,
      UUID commandId,
      String targetType,
      String targetId,
      JsonNode before,
      JsonNode after,
      Long policyGeneration) {}

  /** The receipt a command left: enough to tie the outcome to the row, never the body. */
  public record ReceiptView(
      UUID actorId, String kind, int status, String payloadFingerprint, Instant recordedAt) {}

  /**
   * @param policyGenerationAtDecision as stamped on the audit row; null for rows
   *     written before it existed
   * @param policyUnchangedSince true when no policy, attachment, role or scope has
   *     changed since, so {@code policyVersions} is exactly what governed the decision
   */
  public record DecisionReplay(
      UUID commandId,
      List<AuditRowView> auditRows,
      List<ReceiptView> receipts,
      Long policyGenerationAtDecision,
      Long currentPolicyGeneration,
      Boolean policyUnchangedSince,
      List<PolicyHistory.VersionInForce> policyVersions) {}

  public Page<AuditRowView> list(Actor actor, Filter filter, Optional<String> cursor, int limit) {
    int size = Page.limit(limit);
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT audit_id, occurred_at, actor_id, device_id, action, resource, decision, reason,
                   correlation_id, command_id, target_type, target_id,
                   before_state::text AS before_state, after_state::text AS after_state,
                   policy_generation
              FROM integration.audit_log
             WHERE true
            """);
    List<Object> params = new ArrayList<>();
    and(sql, params, "actor_id = ?", filter.actorId());
    and(sql, params, "target_type = ?", filter.targetType());
    and(sql, params, "target_id = ?", filter.targetId());
    and(sql, params, "action = ?", filter.action());
    and(sql, params, "decision = ?", filter.decision());
    and(sql, params, "correlation_id = ?", filter.correlationId());
    and(sql, params, "command_id = ?", filter.commandId());
    and(sql, params, "occurred_at >= ?", filter.from() == null ? null : Timestamp.from(filter.from()));
    and(sql, params, "occurred_at < ?", filter.to() == null ? null : Timestamp.from(filter.to()));

    List<String> after = Cursor.decode(cursor.orElse(null), 2);
    if (!after.isEmpty()) {
      try {
        sql.append(" AND (occurred_at, audit_id) < (?::timestamptz, ?::bigint)");
        params.add(Timestamp.from(Instant.parse(after.get(0))));
        params.add(Long.parseLong(after.get(1)));
      } catch (DateTimeParseException | NumberFormatException e) {
        throw Cursor.invalid();
      }
    }
    sql.append(" ORDER BY occurred_at DESC, audit_id DESC LIMIT ?");
    params.add(size + 1);

    List<Map<String, Object>> rows =
        database.asModule(
            ModuleRole.INTEGRATION,
            actor.userId(),
            () -> database.query(sql.toString(), params.toArray()));
    List<AuditRowView> views = rows.stream().map(this::view).toList();
    return Page.fromOverfetch(
        views, size, v -> Cursor.encode(v.occurredAt().toString(), Long.toString(v.auditId())));
  }

  /**
   * POL-03: a command's decision, reconstructed from what was recorded when it was
   * taken and not re-evaluated against today's rules.
   */
  public DecisionReplay decision(Actor actor, UUID commandId) {
    List<AuditRowView> rows =
        database.asModule(
            ModuleRole.INTEGRATION,
            actor.userId(),
            () ->
                database
                    .query(
                        """
                        SELECT audit_id, occurred_at, actor_id, device_id, action, resource, decision,
                               reason, correlation_id, command_id, target_type, target_id,
                               before_state::text AS before_state, after_state::text AS after_state,
                               policy_generation
                          FROM integration.audit_log
                         WHERE command_id = ?
                         ORDER BY occurred_at, audit_id
                        """,
                        commandId)
                    .stream()
                    .map(this::view)
                    .toList());
    List<ReceiptView> receipts =
        database.asModule(
            ModuleRole.INTEGRATION,
            actor.userId(),
            () ->
                database
                    .query(
                        "SELECT actor_id, command_kind, result_status, payload_hash, created_at"
                            + " FROM integration.command_receipts WHERE command_id = ?"
                            + " ORDER BY created_at",
                        commandId)
                    .stream()
                    .map(
                        row ->
                            new ReceiptView(
                                (UUID) row.get("actor_id"),
                                (String) row.get("command_kind"),
                                ((Number) row.get("result_status")).intValue(),
                                (String) row.get("payload_hash"),
                                ((Timestamp) row.get("created_at")).toInstant()))
                    .toList());
    if (rows.isEmpty() && receipts.isEmpty()) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No record of command " + commandId);
    }

    // The row that carries the stamp is the decision; without one, the earliest
    // row or the receipt still says who acted and when.
    AuditRowView decided =
        rows.stream()
            .filter(r -> r.policyGeneration() != null)
            .findFirst()
            .orElse(rows.isEmpty() ? null : rows.get(0));
    UUID decidedActor = decided != null ? decided.actorId() : receipts.get(0).actorId();
    Instant decidedAt = decided != null ? decided.occurredAt() : receipts.get(0).recordedAt();
    Long stamped = decided == null ? null : decided.policyGeneration();
    PolicyHistory.Snapshot policy =
        policyHistory.isPresent() && decidedActor != null
            ? policyHistory.get().at(decidedActor, decidedAt)
            : null;
    return new DecisionReplay(
        commandId,
        rows,
        receipts,
        stamped,
        policy == null ? null : policy.currentGeneration(),
        policy == null || stamped == null ? null : stamped == policy.currentGeneration(),
        policy == null ? List.of() : policy.versions());
  }

  private AuditRowView view(Map<String, Object> row) {
    return new AuditRowView(
        ((Number) row.get("audit_id")).longValue(),
        ((Timestamp) row.get("occurred_at")).toInstant(),
        (UUID) row.get("actor_id"),
        (UUID) row.get("device_id"),
        (String) row.get("action"),
        (String) row.get("resource"),
        (String) row.get("decision"),
        (String) row.get("reason"),
        (String) row.get("correlation_id"),
        (UUID) row.get("command_id"),
        (String) row.get("target_type"),
        (String) row.get("target_id"),
        json((String) row.get("before_state")),
        json((String) row.get("after_state")),
        row.get("policy_generation") == null ? null : ((Number) row.get("policy_generation")).longValue());
  }

  private JsonNode json(String text) {
    if (text == null) {
      return null;
    }
    try {
      return mapper.readTree(text);
    } catch (Exception e) {
      throw new IllegalStateException("Stored audit snapshot is not readable JSON", e);
    }
  }

  private static void and(StringBuilder sql, List<Object> params, String clause, Object value) {
    if (value != null && !(value instanceof String s && s.isBlank())) {
      sql.append(" AND ").append(clause);
      params.add(value);
    }
  }
}
