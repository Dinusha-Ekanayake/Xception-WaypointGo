package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Blocking a registered MCP client (R-IAM-37, issue #177). An administrator's
 * decision, so it is a command with a reason and the client's version: blocking
 * revokes every connection the client holds in the same transaction, and the
 * authorization and token steps refuse it until it is unblocked.
 */
@Configuration
public class McpClientHandlers {
  public static final String BLOCK = "mcp:BlockClient";
  public static final String UNBLOCK = "mcp:UnblockClient";
  public static final String REVOKE_USER = "mcp:RevokeUserConnections";

  @Bean
  BlockClientHandler blockMcpClientHandler(Database database, SessionRegistry sessions, Clock clock) {
    return new BlockClientHandler(database, sessions, clock);
  }

  @Bean
  UnblockClientHandler unblockMcpClientHandler(Database database) {
    return new UnblockClientHandler(database);
  }

  @Bean
  RevokeUserConnectionsHandler revokeUserMcpConnectionsHandler(Database database, SessionRegistry sessions) {
    return new RevokeUserConnectionsHandler(database, sessions);
  }

  abstract static class ClientHandler implements CommandHandler {
    final Database database;
    private final String kind;

    ClientHandler(Database database, String kind) {
      this.database = database;
      this.kind = kind;
    }

    @Override
    public final String kind() {
      return kind;
    }

    @Override
    public final String action() {
      return kind;
    }

    @Override
    public final ModuleRole moduleRole() {
      return ModuleRole.IAM;
    }

    @Override
    public String resource(Command command) {
      UUID client = CommandPayload.of(command).optionalUuid("clientId");
      return client == null ? null : "wpt:mcp:client:" + client;
    }

    Map<String, Object> current(UUID clientId, Long expectedVersion) {
      if (expectedVersion == null) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required");
      }
      Map<String, Object> row = database.queryOne(
          "SELECT row_version, blocked_at FROM iam.oauth_clients WHERE client_id = ? FOR UPDATE", clientId);
      if (row == null) {
        throw new DomainException(ErrorCode.NOT_FOUND, "No MCP client " + clientId);
      }
      long actual = ((Number) row.get("row_version")).longValue();
      if (actual != expectedVersion) {
        throw new DomainException(
            ErrorCode.VERSION_CONFLICT, "MCP client " + clientId + " is at version " + actual + ", not " + expectedVersion);
      }
      return row;
    }
  }

  static final class BlockClientHandler extends ClientHandler {
    private final SessionRegistry sessions;
    private final Clock clock;

    BlockClientHandler(Database database, SessionRegistry sessions, Clock clock) {
      super(database, BLOCK);
      this.sessions = sessions;
      this.clock = clock.realTime();
    }

    @Override
    public Object handle(Actor actor, Command command) {
      CommandPayload payload = CommandPayload.of(command);
      UUID clientId = payload.uuid("clientId");
      String reason = payload.requiredText("reason").trim();
      if (reason.isEmpty() || reason.length() > 500) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "reason must be 1 to 500 characters");
      }
      Map<String, Object> row = current(clientId, command.expectedVersion());
      if (row.get("blocked_at") != null) {
        throw new DomainException(ErrorCode.CONFLICT, "MCP client " + clientId + " is already blocked");
      }
      database.updateExpectingOneRow(
          "UPDATE iam.oauth_clients SET blocked_at = ?, blocked_by = ?, block_reason = ?,"
              + " row_version = row_version + 1 WHERE client_id = ? AND row_version = ?",
          Timestamp.from(clock.now()), actor.userId(), reason, clientId, command.expectedVersion());
      int revoked = 0;
      for (Map<String, Object> session :
          database.query("SELECT token_hash FROM iam.sessions WHERE oauth_client_id = ?", clientId)) {
        sessions.revokeByKeyInTransaction((String) session.get("token_hash"), "client_blocked");
        revoked++;
      }
      return Map.of("clientId", clientId.toString(), "blocked", true, "connectionsRevoked", revoked,
          "rowVersion", command.expectedVersion() + 1);
    }
  }

  static final class UnblockClientHandler extends ClientHandler {
    UnblockClientHandler(Database database) {
      super(database, UNBLOCK);
    }

    @Override
    public Object handle(Actor actor, Command command) {
      UUID clientId = CommandPayload.of(command).uuid("clientId");
      Map<String, Object> row = current(clientId, command.expectedVersion());
      if (row.get("blocked_at") == null) {
        throw new DomainException(ErrorCode.CONFLICT, "MCP client " + clientId + " is not blocked");
      }
      database.updateExpectingOneRow(
          "UPDATE iam.oauth_clients SET blocked_at = NULL, blocked_by = NULL, block_reason = NULL,"
              + " row_version = row_version + 1 WHERE client_id = ? AND row_version = ?",
          clientId, command.expectedVersion());
      return Map.of("clientId", clientId.toString(), "blocked", false, "rowVersion", command.expectedVersion() + 1);
    }
  }

  /**
   * Ends every MCP connection one person holds, now (R-IAM-38). Their browser
   * sessions are untouched. There is no aggregate to version: ending what is
   * already gone ends nothing and succeeds, so a retry is harmless. To keep
   * them from reconnecting, an administrator also attaches WaypointMcpBlocked.
   */
  static final class RevokeUserConnectionsHandler implements CommandHandler {
    private final Database database;
    private final SessionRegistry sessions;

    RevokeUserConnectionsHandler(Database database, SessionRegistry sessions) {
      this.database = database;
      this.sessions = sessions;
    }

    @Override
    public String kind() {
      return REVOKE_USER;
    }

    @Override
    public String action() {
      return REVOKE_USER;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.IAM;
    }

    @Override
    public String resource(Command command) {
      UUID user = CommandPayload.of(command).optionalUuid("userId");
      return user == null ? null : "wpt:mcp:user:" + user;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      CommandPayload payload = CommandPayload.of(command);
      UUID userId = payload.uuid("userId");
      String reason = payload.requiredText("reason").trim();
      if (reason.isEmpty() || reason.length() > 500) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "reason must be 1 to 500 characters");
      }
      if (database.queryOne("SELECT 1 AS found FROM iam.users WHERE user_id = ?", userId) == null) {
        throw new DomainException(ErrorCode.NOT_FOUND, "No user " + userId);
      }
      int revoked = 0;
      for (Map<String, Object> session : database.query(
          "SELECT token_hash FROM iam.sessions WHERE user_id = ? AND mcp_read_only FOR UPDATE", userId)) {
        sessions.revokeByKeyInTransaction((String) session.get("token_hash"), "admin_revoked");
        revoked++;
      }
      return Map.of("userId", userId.toString(), "connectionsRevoked", revoked, "reason", reason);
    }
  }
}
