package com.waypoint.dispatch.identity.infrastructure;

import com.waypoint.dispatch.identity.domain.policy.Statement;
import com.waypoint.dispatch.platform.db.Database;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Loads the statements that apply to one actor.
 *
 * <p>Statements come from policies attached directly to the user and from
 * policies attached to any role the user holds, always from each policy's
 * default version. One query rather than several, because this runs before every
 * command and a chatty authorization path is a slow system.
 */
@Component
public class JdbcPolicyRepository {
  private final Database database;
  private final PolicyDocumentParser parser;

  public JdbcPolicyRepository(Database database, PolicyDocumentParser parser) {
    this.database = database;
    this.parser = parser;
  }

  private static final String STATEMENTS_FOR_ACTOR =
      """
      SELECT v.document
      FROM iam.policy_attachments a
      JOIN iam.policy_versions v
        ON v.policy_id = a.policy_id AND v.is_default
      WHERE (a.principal_type = 'user' AND a.principal_id = ?::text)
         OR (a.principal_type = 'role'
             AND a.principal_id IN (SELECT role_code FROM iam.user_roles WHERE user_id = ?))
      """;

  /** Every statement in force for this actor, from user and role attachments alike. */
  public List<Statement> statementsFor(UUID userId) {
    List<Map<String, Object>> rows =
        database.query(STATEMENTS_FOR_ACTOR, userId.toString(), userId);
    List<Statement> statements = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      statements.addAll(parser.parse(String.valueOf(row.get("document"))).statements());
    }
    return statements;
  }

  /** Roles held by the actor, used for context and for reporting. */
  public List<String> rolesOf(UUID userId) {
    return database.query("SELECT role_code FROM iam.user_roles WHERE user_id = ?", userId).stream()
        .map(r -> (String) r.get("role_code"))
        .toList();
  }

  /**
   * R-IAM: an action a policy names must exist in the catalogue, so a typo is
   * rejected when the policy is written rather than denying silently forever.
   */
  public boolean actionExists(String action) {
    return database.queryOne("SELECT action FROM iam.action_catalogue WHERE action = ?", action)
        != null;
  }

  public List<String> catalogueActions() {
    return database.query("SELECT action FROM iam.action_catalogue ORDER BY action").stream()
        .map(r -> (String) r.get("action"))
        .toList();
  }
}
