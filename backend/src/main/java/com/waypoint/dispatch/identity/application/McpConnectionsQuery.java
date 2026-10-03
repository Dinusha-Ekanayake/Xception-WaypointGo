package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.domain.McpScopes;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who is connected (issue #177). A person sees and ends their own MCP
 * connections from the browser; an administrator sees every registered client
 * with how many people it holds connected, to decide what to block.
 */
@Component
public class McpConnectionsQuery {
  private final Database database;
  private final SessionRegistry sessions;
  private final AuditLog audit;
  private final Clock clock;

  public McpConnectionsQuery(Database database, SessionRegistry sessions, AuditLog audit, Clock clock) {
    this.database = database;
    this.sessions = sessions;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * @param connectionId the stored SHA-256 of the credential: it names the
   *     connection to its owner and is no use as a credential
   * @param clientName empty for a local connection
   */
  public record ConnectionView(String connectionId, Optional<UUID> clientId, Optional<String> clientName,
      String transport, List<String> scopes, Instant connectedAt, Instant lastUsedAt) {
    public ConnectionView {
      scopes = List.copyOf(scopes);
    }
  }

  public record ClientView(UUID clientId, String clientName, Instant registeredAt, Optional<Instant> lastUsedAt,
      int activeConnections, Optional<Instant> blockedAt, Optional<String> blockReason, long rowVersion) {}

  public List<ConnectionView> own(Actor actor) {
    Instant now = clock.now();
    return database.readAs(ModuleRole.IAM, actor.userId(), () -> database.query(
            """
            SELECT s.token_hash, s.oauth_client_id, c.client_name, s.issued_at, s.last_seen_at,
                   array_to_string(s.mcp_scopes, ',') AS scopes, s.mcp_scopes IS NULL AS legacy
              FROM iam.sessions s
              LEFT JOIN iam.oauth_clients c ON c.client_id = s.oauth_client_id
             WHERE s.user_id = ? AND s.mcp_read_only AND s.absolute_expiry > ? AND s.idle_expiry > ?
             ORDER BY s.last_seen_at DESC
             LIMIT 50
            """,
            actor.userId(), Timestamp.from(now), Timestamp.from(now))
        .stream()
        .map(row -> {
          UUID client = (UUID) row.get("oauth_client_id");
          String scopes = (String) row.get("scopes");
          return new ConnectionView(
              (String) row.get("token_hash"),
              Optional.ofNullable(client),
              Optional.ofNullable((String) row.get("client_name")),
              client == null ? "local" : "remote",
              McpScopes.stored(Boolean.TRUE.equals(row.get("legacy")) ? null
                  : scopes == null || scopes.isEmpty() ? List.of() : List.of(scopes.split(","))).stream().sorted().toList(),
              ((Timestamp) row.get("issued_at")).toInstant(),
              ((Timestamp) row.get("last_seen_at")).toInstant());
        })
        .toList());
  }

  /**
   * Ends one of the actor's own connections. Another person's connection, or
   * one that is already gone, is the same not found: the id reveals nothing.
   */
  public void revokeOwn(Actor actor, String connectionId) {
    boolean revoked = database.asModule(ModuleRole.IAM, actor.userId(), () -> {
      var row = database.queryOne(
          "SELECT 1 AS found FROM iam.sessions WHERE token_hash = ? AND user_id = ? AND mcp_read_only",
          connectionId, actor.userId());
      if (row == null) {
        return false;
      }
      sessions.revokeByKeyInTransaction(connectionId, "owner_revoked");
      return true;
    });
    if (!revoked) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No such connection of yours");
    }
    audit.recordStandalone(AuditEntry.allowed(actor.userId(), actor.deviceId(), McpAccessHandler.CONNECT,
        McpAccessHandler.RESOURCE, "MCP connection ended by its owner from the browser"));
  }

  public List<ClientView> clients(Actor actor) {
    Instant now = clock.now();
    return database.readAs(ModuleRole.IAM, actor.userId(), () -> database.query(
            """
            SELECT c.client_id, c.client_name, c.registered_at, c.last_used_at, c.blocked_at, c.block_reason,
                   c.row_version,
                   (SELECT count(*) FROM iam.sessions s
                     WHERE s.oauth_client_id = c.client_id AND s.absolute_expiry > ? AND s.idle_expiry > ?) AS active
              FROM iam.oauth_clients c
             ORDER BY c.last_used_at DESC NULLS LAST, c.registered_at DESC
             LIMIT 200
            """,
            Timestamp.from(now), Timestamp.from(now))
        .stream()
        .map(row -> new ClientView(
            (UUID) row.get("client_id"),
            (String) row.get("client_name"),
            ((Timestamp) row.get("registered_at")).toInstant(),
            Optional.ofNullable((Timestamp) row.get("last_used_at")).map(Timestamp::toInstant),
            ((Number) row.get("active")).intValue(),
            Optional.ofNullable((Timestamp) row.get("blocked_at")).map(Timestamp::toInstant),
            Optional.ofNullable((String) row.get("block_reason")),
            ((Number) row.get("row_version")).longValue()))
        .toList());
  }
}
