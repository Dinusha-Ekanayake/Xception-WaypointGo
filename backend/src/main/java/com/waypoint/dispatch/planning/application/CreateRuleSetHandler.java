package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Schedule one changed threshold as a complete, immutable successor rule set. */
@Component
public class CreateRuleSetHandler implements CommandHandler {
  public static final String KIND = "planning:CreateRuleSet";

  private final Database database;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public CreateRuleSetHandler(Database database, Clock clock) {
    this.database = database;
    this.clock = clock;
  }

  @Override public String kind() { return KIND; }
  @Override public String action() { return KIND; }
  @Override public ModuleRole moduleRole() { return ModuleRole.PLANNING; }
  @Override public String resource(Command command) { return "wpt:planning:rules:*"; }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    String key = payload.requiredText("key");
    BigDecimal value = PlanningRuleCatalogue.validatedValue(key, payload.requiredText("value"));
    String reason = payload.requiredText("reason");
    if (reason.length() < 10 || reason.length() > 500)
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Reason must contain 10 to 500 characters");
    LocalDate effectiveFrom = payload.date("effectiveFrom");
    Instant now = clock.now();
    LocalDate today = LocalDate.ofInstant(now, Clock.OPERATING_ZONE);
    if (effectiveFrom.isBefore(today))
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Effective date cannot be in the past");
    if (command.expectedVersion() == null)
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required");

    Map<String, Object> latest = database.queryOne("""
        SELECT rule_set_id, row_version, effective_from FROM planning.rule_sets
         WHERE effective_to IS NULL ORDER BY effective_from DESC LIMIT 1
        """);
    if (latest == null) throw new DomainException(ErrorCode.CONSTRAINT_VIOLATED, "No planning rule set exists");
    UUID previousId = (UUID) latest.get("rule_set_id");
    long version = ((Number) latest.get("row_version")).longValue();
    if (version != command.expectedVersion())
      throw new DomainException(ErrorCode.VERSION_CONFLICT, "Planning rules changed; reload before saving");
    if (!effectiveFrom.isAfter(((Date) latest.get("effective_from")).toLocalDate()))
      throw new DomainException(ErrorCode.VALIDATION_FAILED,
          "Effective date must follow the latest scheduled rule set");
    Map<String, Object> before = database.queryOne("""
        SELECT parameter_value FROM planning.rule_parameters
         WHERE rule_set_id = ? AND parameter_key = ?
        """, previousId, key);
    if (before == null) throw new DomainException(ErrorCode.CONSTRAINT_VIOLATED,
        "Rule set is missing " + key);
    if (((BigDecimal) before.get("parameter_value")).compareTo(value) == 0)
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "The planning value is unchanged");

    database.updateExpectingOneRow("""
        UPDATE planning.rule_sets SET effective_to = ?, row_version = row_version + 1
         WHERE rule_set_id = ? AND row_version = ? AND effective_to IS NULL
        """, Date.valueOf(effectiveFrom), previousId, version);
    UUID nextId = UuidV7.generate(now, random);
    database.update("""
        INSERT INTO planning.rule_sets
          (rule_set_id, effective_from, note, created_at, created_by, row_version)
        VALUES (?, ?, ?, ?, ?, ?)
        """, nextId, Date.valueOf(effectiveFrom), reason, java.sql.Timestamp.from(now), actor.userId(), version + 1);
    database.update("""
        INSERT INTO planning.rule_parameters (rule_set_id, parameter_key, parameter_value, unit)
        SELECT ?, parameter_key,
               CASE WHEN parameter_key = ? THEN ? ELSE parameter_value END, unit
          FROM planning.rule_parameters WHERE rule_set_id = ?
        """, nextId, key, value, previousId);
    return Map.of("ruleSetId", nextId, "rowVersion", version + 1, "effectiveFrom", effectiveFrom);
  }
}
