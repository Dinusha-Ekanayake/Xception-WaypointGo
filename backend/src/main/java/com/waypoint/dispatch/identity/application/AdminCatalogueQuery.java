package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Cursor;
import com.waypoint.dispatch.shared.domain.Page;
import java.util.List;
import org.springframework.stereotype.Component;

/** The role and action catalogues shown by the administrator workspace. */
@Component
public class AdminCatalogueQuery {
  private final Database database;

  public AdminCatalogueQuery(Database database) {
    this.database = database;
  }

  public record RoleView(String roleCode, String description, long memberCount) {}
  public record ActionView(String action, String module, String description, boolean implemented) {}

  public Page<RoleView> roles(String after, Integer limit) {
    int size = Page.limit(limit);
    List<String> cursor = Cursor.decode(after, 1);
    String key = cursor.isEmpty() ? null : cursor.get(0);
    List<RoleView> rows = database.readAs(ModuleRole.IAM, null, () -> database.query(
        """
        SELECT r.role_code, r.description, count(ur.user_id) AS member_count
          FROM iam.roles r LEFT JOIN iam.user_roles ur ON ur.role_code = r.role_code
         WHERE (?::text IS NULL OR r.role_code > ?::text)
         GROUP BY r.role_code, r.description ORDER BY r.role_code LIMIT ?
        """, key, key, size + 1).stream().map(r -> new RoleView(
            (String) r.get("role_code"), (String) r.get("description"),
            ((Number) r.get("member_count")).longValue())).toList());
    return Page.fromOverfetch(rows, size, r -> Cursor.encode(r.roleCode()));
  }

  public Page<ActionView> actions(String after, Integer limit) {
    int size = Page.limit(limit);
    List<String> cursor = Cursor.decode(after, 1);
    String key = cursor.isEmpty() ? null : cursor.get(0);
    List<ActionView> rows = database.readAs(ModuleRole.IAM, null, () -> database.query(
        """
        SELECT action, module, description, implemented FROM iam.action_catalogue
         WHERE (?::text IS NULL OR action > ?::text) ORDER BY action LIMIT ?
        """, key, key, size + 1).stream().map(r -> new ActionView(
            (String) r.get("action"), (String) r.get("module"),
            (String) r.get("description"), (Boolean) r.get("implemented"))).toList());
    return Page.fromOverfetch(rows, size, r -> Cursor.encode(r.action()));
  }
}
