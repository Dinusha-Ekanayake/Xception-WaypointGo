package com.waypoint.dispatch.support;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import java.util.UUID;

/**
 * MCP is the dispatcher's by role (decision 2026-10-09). A test of the MCP
 * mechanics that connects as another role first grants it to that one person,
 * as an administrator can: WaypointMcpReader and WaypointMcpWriter attached to
 * the user.
 */
public final class McpGrants {
  private McpGrants() {}

  public static void allow(Database database, UUID userId) {
    database.asModule(ModuleRole.IAM, null, () -> {
      database.update(
          "INSERT INTO iam.policy_attachments (policy_id, principal_type, principal_id)"
              + " SELECT policy_id, 'user', ? FROM iam.policies WHERE name IN ('WaypointMcpReader', 'WaypointMcpWriter')"
              + " ON CONFLICT DO NOTHING",
          userId.toString());
      database.update("UPDATE iam.policy_generation SET generation = generation + 1, changed_at = now()");
    });
  }
}
