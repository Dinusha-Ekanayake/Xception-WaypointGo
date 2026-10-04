package com.waypoint.dispatch.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.domain.McpScopes;
import com.waypoint.dispatch.identity.domain.McpScopes.WriteTool;
import com.waypoint.dispatch.identity.infrastructure.SessionTokens;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandBus;
import com.waypoint.dispatch.platform.messaging.CommandResult;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.error.Violation;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Confirmed MCP writes (R-IAM-35, issue #177). Two steps, so a person sees
 * exactly what will happen before it does:
 *
 * <ol>
 *   <li><b>Prepare</b> stores the exact command, with its command id fixed, and
 *       answers with a preview and a confirmation that lasts two minutes.
 *   <li><b>Confirm</b> spends that confirmation, once, from the same connection,
 *       and submits the stored command through {@link CommandBus}, which
 *       authorizes it under the person's own policy and scope, checks the
 *       idempotency receipt and {@code expectedVersion}, and audits it.
 * </ol>
 *
 * <p>Nothing the client sends at confirm can change the command: it names only
 * the confirmation. Only the safe tools of {@link McpScopes#writeTools()} exist,
 * each needs its write scope on the connection and the person's
 * {@code mcp:Write} grant, and writes have their own rate limit (P-32).
 *
 * <p>Like the other connection bookkeeping this is not itself a business
 * command; the business command is the one the bus runs at confirm.
 */
@Component
public class McpWriteHandler {
  static final Duration CONFIRMATION_LIFETIME = Duration.ofMinutes(2);
  static final int MAX_PAYLOAD_CHARS = 8_192;

  private final Database database;
  private final SessionRegistry sessions;
  private final SessionTokens tokens;
  private final McpAccessHandler access;
  private final CommandBus bus;
  private final AuditLog audit;
  private final Metrics metrics;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public McpWriteHandler(Database database, SessionRegistry sessions, SessionTokens tokens, McpAccessHandler access,
      CommandBus bus, AuditLog audit, Metrics metrics, ObjectMapper mapper, Clock clock) {
    this.database = database;
    this.sessions = sessions;
    this.tokens = tokens;
    this.access = access;
    this.bus = bus;
    this.audit = audit;
    this.metrics = metrics;
    this.mapper = mapper;
    this.clock = clock.realTime();
  }

  /** What the person is asked to confirm. The confirmation is shown once and never stored. */
  public record Prepared(String confirmation, Instant expiresAt, String tool, String kind,
      UUID commandId, Long expectedVersion, JsonNode payload) {}

  /** What the bus did with the confirmed command. */
  public record Confirmed(UUID commandId, String tool, String kind, boolean replayed, Object result) {}

  public Prepared prepare(String token, String toolName, Long expectedVersion, JsonNode payload) {
    Connected caller = caller(token);
    WriteTool tool = McpScopes.writeTool(toolName).orElseThrow(
        () -> new DomainException(ErrorCode.VALIDATION_FAILED, "tool is not an MCP write tool"));
    requireAllowed(caller, tool);
    if (payload == null || !payload.isObject()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "payload must be an object");
    }
    String body = json(payload);
    if (body.length() > MAX_PAYLOAD_CHARS) {
      throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE, "payload is over " + MAX_PAYLOAD_CHARS + " characters");
    }
    if (tool.versioned() != (expectedVersion != null)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, tool.versioned()
          ? "expectedVersion is required: " + tool.name() + " changes an existing record"
          : "expectedVersion must be empty: " + tool.name() + " creates a record");
    }
    if (expectedVersion != null && expectedVersion < 1) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion must be at least 1");
    }

    Instant now = clock.now();
    Instant expires = now.plus(CONFIRMATION_LIFETIME);
    UUID commandId = UuidV7.generate(now, random);
    String confirmation = "mcpw." + tokens.newToken();
    database.asModule(ModuleRole.IAM, caller.actor().userId(), () -> database.update(
        """
        INSERT INTO iam.mcp_write_confirmations
            (confirmation_hash, session_key, user_id, oauth_client_id, tool, command_id, kind,
             expected_version, payload, issued_at, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
        """,
        tokens.hash(confirmation), caller.connection().sessionKey(), caller.actor().userId(),
        caller.connection().oauthClientId(), tool.name(), commandId, tool.kind(), expectedVersion, body,
        Timestamp.from(now), Timestamp.from(expires)));
    // The payload is never audited: it can carry personal text. The command id joins the rows.
    audit.recordStandalone(AuditEntry.allowed(caller.actor().userId(), caller.actor().deviceId(), McpAccessHandler.WRITE,
        "wpt:mcp:write:" + tool.name(), "MCP write prepared: " + tool.kind() + client(caller)).withCommand(commandId));
    metrics.increment("waypoint.mcp.writes", "tool", tool.name(), "step", "prepared");
    return new Prepared(confirmation, expires, tool.name(), tool.kind(), commandId, expectedVersion, payload);
  }

  public Confirmed confirm(String token, String confirmation, String correlationId) {
    Connected caller = caller(token);
    if (confirmation == null || !confirmation.startsWith("mcpw.")) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "confirmation is not one this server issued");
    }
    Instant now = clock.now();
    Map<String, Object> row = database.asModule(ModuleRole.IAM, caller.actor().userId(), () -> database.queryOne(
        """
        UPDATE iam.mcp_write_confirmations SET consumed_at = ?
         WHERE confirmation_hash = ? AND session_key = ? AND consumed_at IS NULL AND expires_at > ?
        RETURNING tool, command_id, kind, expected_version, payload::text AS payload, issued_at
        """,
        Timestamp.from(now), tokens.hash(confirmation), caller.connection().sessionKey(), Timestamp.from(now)));
    if (row == null) {
      metrics.increment("waypoint.mcp.writes", "tool", "unknown", "step", "refused");
      throw new DomainException(ErrorCode.CONFLICT,
          "This confirmation has expired, was already used or belongs to another connection; prepare the write again",
          List.of("R-IAM-35"));
    }
    WriteTool tool = McpScopes.writeTool((String) row.get("tool")).orElseThrow();
    // Re-checked at confirm: a scope or grant withdrawn in the last two minutes holds.
    requireAllowed(caller, tool);
    UUID commandId = (UUID) row.get("command_id");
    Number version = (Number) row.get("expected_version");
    Command command = new Command(commandId, tool.kind(), version == null ? null : version.longValue(),
        read((String) row.get("payload")), ((Timestamp) row.get("issued_at")).toInstant());
    CommandResult result;
    try {
      result = bus.dispatch(caller.actor(), command, correlationId);
    } catch (DomainException refused) {
      metrics.increment("waypoint.mcp.writes", "tool", tool.name(), "step", "rejected");
      throw refused;
    }
    audit.recordStandalone(AuditEntry.allowed(caller.actor().userId(), caller.actor().deviceId(), McpAccessHandler.WRITE,
        "wpt:mcp:write:" + tool.name(), "MCP write confirmed: " + tool.kind() + client(caller))
        .withCommand(commandId).withCorrelation(correlationId));
    metrics.increment("waypoint.mcp.writes", "tool", tool.name(), "step", "confirmed");
    return new Confirmed(commandId, tool.name(), tool.kind(), result.replayed(), result.value());
  }

  private record Connected(Actor actor, SessionRegistry.McpConnection connection) {}

  private Connected caller(String token) {
    var session = sessions.resolveMcp(token).orElseThrow(
        () -> new DomainException(ErrorCode.UNAUTHENTICATED, "MCP credential is expired or revoked"));
    var connection = sessions.mcpConnection(token).orElseThrow(
        () -> new DomainException(ErrorCode.UNAUTHENTICATED, "MCP credential is expired or revoked"));
    return new Connected(sessions.actorOf(session), connection);
  }

  /** Client scope, then the person's MCP write grant. The command's own policy is the bus's. */
  private void requireAllowed(Connected caller, WriteTool tool) {
    if (!caller.connection().scopes().contains(tool.scope())) {
      audit.recordStandalone(AuditEntry.denied(caller.actor().userId(), caller.actor().deviceId(), McpAccessHandler.WRITE,
          "wpt:mcp:write:" + tool.name(), "MCP connection was not granted " + tool.scope()));
      throw DomainException.withViolations(ErrorCode.FORBIDDEN, "This connection was not granted " + tool.scope(),
          List.of(Violation.onField("R-IAM-34", "scope", tool.scope())));
    }
    access.requireWriteGrant(caller.actor());
  }

  private static String client(Connected caller) {
    UUID client = caller.connection().oauthClientId();
    return client == null ? " (local)" : " (client " + client + ")";
  }

  private String json(JsonNode node) {
    try {
      return mapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "payload is not valid JSON");
    }
  }

  private JsonNode read(String text) {
    try {
      return mapper.readTree(text);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a stored MCP write payload is not JSON", e);
    }
  }
}
