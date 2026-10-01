package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.contract.OperatorQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Identity's narrow answer to Sync's queued-loader attribution check. */
@Component
public class OperatorDataQuery implements OperatorQuery {
  private final Database database;

  public OperatorDataQuery(Database database) {
    this.database = database;
  }

  @Override
  public Optional<Actor> operatorAt(String sessionToken, UUID userId, Instant recordedAt) {
    if (sessionToken == null || sessionToken.isBlank() || userId == null || recordedAt == null) {
      return Optional.empty();
    }
    Map<String, Object> row = database.asModule(
        ModuleRole.IAM,
        null,
        () -> database.queryOne(
            """
            SELECT u.user_id, s.device_id
            FROM iam.session_operators o
            JOIN iam.sessions s ON s.session_token = ?
            JOIN iam.users u ON u.user_id = o.user_id AND u.is_active
            JOIN iam.user_roles r ON r.user_id = u.user_id AND r.role_code = ?
            WHERE o.session_key = ? AND o.user_id = ? AND o.started_at <= ?
              AND (o.ended_at IS NULL OR ? < o.ended_at)
            ORDER BY o.started_at DESC
            LIMIT 1
            """,
            sessionToken,
            OperatorRegistry.LOADER_ROLE,
            OperatorRegistry.key(sessionToken),
            userId,
            Timestamp.from(recordedAt),
            Timestamp.from(recordedAt)));
    return row == null
        ? Optional.empty()
        : Optional.of(new Actor((UUID) row.get("user_id"), (UUID) row.get("device_id")));
  }
}
