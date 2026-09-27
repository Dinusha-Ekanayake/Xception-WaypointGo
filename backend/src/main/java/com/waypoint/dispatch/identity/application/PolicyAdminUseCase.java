package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.domain.policy.Pattern;
import com.waypoint.dispatch.identity.domain.policy.PolicyDocument;
import com.waypoint.dispatch.identity.domain.policy.Statement;
import com.waypoint.dispatch.identity.infrastructure.JdbcPolicyRepository;
import com.waypoint.dispatch.identity.infrastructure.PolicyCache;
import com.waypoint.dispatch.identity.infrastructure.PolicyDocumentParser;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Authoring and attaching policy at runtime.
 *
 * <p>This is what makes authorization data rather than code: an administrator
 * changes what a role may do and the next command sees it, with no deployment.
 *
 * <p>Two invariants run through every method here. A version is never edited,
 * only superseded, so a decision taken under an older policy stays explainable.
 * And any change clears the whole policy cache, because a cache that is clever
 * about which actors were affected is a cache that eventually serves a revoked
 * permission.
 */
@Component
public class PolicyAdminUseCase {
  private final Database database;
  private final JdbcPolicyRepository policies;
  private final PolicyDocumentParser parser;
  private final PolicyCache cache;
  private final AuditLog audit;

  public PolicyAdminUseCase(
      Database database,
      JdbcPolicyRepository policies,
      PolicyDocumentParser parser,
      PolicyCache cache,
      AuditLog audit) {
    this.database = database;
    this.policies = policies;
    this.parser = parser;
    this.cache = cache;
    this.audit = audit;
  }

  public record PolicySummary(UUID policyId, String name, int defaultVersion) {}

  public UUID createPolicy(Actor actor, String name, String description, String documentJson) {
    return database.asModule(
        ModuleRole.IAM,
        actor.userId(),
        () -> {
          validate(documentJson);
          if (database.queryOne("SELECT policy_id FROM iam.policies WHERE name = ?", name) != null) {
            throw new DomainException(ErrorCode.CONFLICT, "Policy " + name + " already exists");
          }
          UUID policyId = UUID.randomUUID();
          database.update(
              "INSERT INTO iam.policies (policy_id, name, description, created_by)"
                  + " VALUES (?, ?, ?, ?)",
              policyId,
              name,
              description,
              actor.userId());
          database.update(
              "INSERT INTO iam.policy_versions"
                  + " (policy_id, version_number, document, is_default, created_by)"
                  + " VALUES (?, 1, ?::jsonb, true, ?)",
              policyId,
              documentJson,
              actor.userId());
          audit.record(
              AuditEntry.allowed(
                  actor.userId(),
                  actor.deviceId(),
                  "iam:CreatePolicy",
                  "wpt:iam:policy:" + name,
                  "version 1 created and made default"));
          cache.invalidateAll();
          return policyId;
        });
  }

  /** Adds a version without changing which one is in force. */
  public int createVersion(Actor actor, String name, String documentJson) {
    return database.asModule(
        ModuleRole.IAM,
        actor.userId(),
        () -> {
          validate(documentJson);
          UUID policyId = policyIdOf(name);
          Map<String, Object> latest =
              database.queryOne(
                  "SELECT max(version_number) AS latest FROM iam.policy_versions WHERE policy_id = ?",
                  policyId);
          int next = ((Number) latest.get("latest")).intValue() + 1;
          database.update(
              "INSERT INTO iam.policy_versions"
                  + " (policy_id, version_number, document, is_default, created_by)"
                  + " VALUES (?, ?, ?::jsonb, false, ?)",
              policyId,
              next,
              documentJson,
              actor.userId());
          audit.record(
              AuditEntry.allowed(
                  actor.userId(),
                  actor.deviceId(),
                  "iam:CreatePolicy",
                  "wpt:iam:policy:" + name,
                  "version " + next + " created, not yet default"));
          return next;
        });
  }

  /** Moves which version is in force. This is the moment permissions actually change. */
  public void setDefaultVersion(Actor actor, String name, int versionNumber) {
    database.asModule(
        ModuleRole.IAM,
        actor.userId(),
        () -> {
          UUID policyId = policyIdOf(name);
          if (database.queryOne(
                  "SELECT policy_version_id FROM iam.policy_versions"
                      + " WHERE policy_id = ? AND version_number = ?",
                  policyId,
                  versionNumber)
              == null) {
            throw new DomainException(
                ErrorCode.NOT_FOUND, "Policy " + name + " has no version " + versionNumber);
          }
          // Clear first: the partial unique index allows only one default.
          database.update(
              "UPDATE iam.policy_versions SET is_default = false WHERE policy_id = ? AND is_default",
              policyId);
          database.update(
              "UPDATE iam.policy_versions SET is_default = true"
                  + " WHERE policy_id = ? AND version_number = ?",
              policyId,
              versionNumber);
          audit.record(
              AuditEntry.allowed(
                  actor.userId(),
                  actor.deviceId(),
                  "iam:CreatePolicy",
                  "wpt:iam:policy:" + name,
                  "version " + versionNumber + " is now in force"));
          cache.invalidateAll();
        });
  }

  public void attach(Actor actor, String name, String principalType, String principalId) {
    changeAttachment(actor, name, principalType, principalId, true);
  }

  public void detach(Actor actor, String name, String principalType, String principalId) {
    changeAttachment(actor, name, principalType, principalId, false);
  }

  private void changeAttachment(
      Actor actor, String name, String principalType, String principalId, boolean attaching) {
    if (!List.of("user", "role").contains(principalType)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "A principal is a user or a role, not " + principalType);
    }
    database.asModule(
        ModuleRole.IAM,
        actor.userId(),
        () -> {
          UUID policyId = policyIdOf(name);
          assertPrincipalExists(principalType, principalId);
          if (attaching) {
            database.update(
                "INSERT INTO iam.policy_attachments"
                    + " (policy_id, principal_type, principal_id, attached_by)"
                    + " VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                policyId,
                principalType,
                principalId,
                actor.userId());
          } else {
            database.update(
                "DELETE FROM iam.policy_attachments"
                    + " WHERE policy_id = ? AND principal_type = ? AND principal_id = ?",
                policyId,
                principalType,
                principalId);
          }
          audit.record(
              AuditEntry.allowed(
                  actor.userId(),
                  actor.deviceId(),
                  "iam:AttachPolicy",
                  "wpt:iam:policy:" + name,
                  (attaching ? "attached to " : "detached from ") + principalType + " " + principalId));
          cache.invalidateAll();
        });
  }

  public List<PolicySummary> list() {
    return database
        .asModule(
            ModuleRole.IAM,
            null,
            () ->
                database.query(
                    """
                    SELECT p.policy_id, p.name, v.version_number
                    FROM iam.policies p
                    JOIN iam.policy_versions v ON v.policy_id = p.policy_id AND v.is_default
                    ORDER BY p.name
                    """))
        .stream()
        .map(
            r ->
                new PolicySummary(
                    (UUID) r.get("policy_id"),
                    (String) r.get("name"),
                    ((Number) r.get("version_number")).intValue()))
        .toList();
  }

  /**
   * R-IAM-03. Every action a document names must exist in the catalogue.
   *
   * <p>Without this a typo denies silently forever and looks exactly like a
   * permissions bug nobody can find. Wildcards are checked by prefix, so
   * {@code order:*} is accepted when the catalogue has any order action.
   */
  private void validate(String documentJson) {
    PolicyDocument document = parser.parse(documentJson);
    List<String> catalogue = policies.catalogueActions();
    List<String> unknown = new ArrayList<>();

    for (Statement statement : document.statements()) {
      for (Pattern action : statement.actions()) {
        boolean known = catalogue.stream().anyMatch(action::matches);
        if (!known) {
          unknown.add(action.raw());
        }
      }
    }
    if (!unknown.isEmpty()) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "Policy names " + unknown.size() + " action(s) that do not exist",
          unknown);
    }
  }

  private UUID policyIdOf(String name) {
    Map<String, Object> row =
        database.queryOne("SELECT policy_id FROM iam.policies WHERE name = ?", name);
    if (row == null) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No policy named " + name);
    }
    return (UUID) row.get("policy_id");
  }

  private void assertPrincipalExists(String principalType, String principalId) {
    boolean exists =
        "role".equals(principalType)
            ? database.queryOne("SELECT role_code FROM iam.roles WHERE role_code = ?", principalId)
                != null
            : database.queryOne(
                    "SELECT user_id FROM iam.users WHERE user_id = ?::uuid", principalId)
                != null;
    if (!exists) {
      throw new DomainException(
          ErrorCode.NOT_FOUND, "No " + principalType + " called " + principalId);
    }
  }
}
