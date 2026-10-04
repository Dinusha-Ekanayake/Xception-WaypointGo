package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.contract.PlanningRuleViews;
import com.waypoint.dispatch.planning.domain.RuleSet;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Planning's one catalogue of editable values and their validation ranges. */
@Component
public class PlanningRuleCatalogue {
  public record Definition(String key, String category, String label, String unit,
                           String control, long minimum, long maximum, boolean editable) {}

  private static final List<Definition> DEFINITIONS = List.of(
      new Definition(RuleSet.MAX_TRIPS, "Trips and vehicle use", "Trips per vehicle per day", "trips", "number", 1, 2, true),
      new Definition(RuleSet.FRESH_BUDGET_MIN, "Trips and vehicle use", "Fresh time budget", "min", "number", 1, 270, true),
      new Definition(RuleSet.DAYTIME_BUDGET_MIN, "Trips and vehicle use", "Daytime budget", "min", "number", 1, 480, true),
      new Definition(RuleSet.FRESH_DEPARTURE_MIN, "Departure times", "Fresh departure", "min", "time", 210, 1439, true),
      new Definition(RuleSet.DAYTIME_DEPARTURE_MIN, "Departure times", "Daytime departure", "min", "time", 480, 1439, true),
      new Definition(RuleSet.ESCALATION_SKIPS, "Priority", "Skips before priority", "runs", "number", 0, 100, true),
      new Definition(RuleSet.STRICT_WINDOW_MIN, "Priority", "Short window threshold", "min", "number", 1, 1440, true),
      new Definition(RuleSet.CADENCE_DAYS_PREFIX + "Fresh", "Priority", "Fresh cadence", "days", "number", 1, 365, true),
      new Definition(RuleSet.CADENCE_DAYS_PREFIX + "Style", "Priority", "Style cadence", "days", "number", 1, 365, true),
      new Definition(RuleSet.CADENCE_DAYS_PREFIX + "Tech", "Priority", "Tech cadence", "days", "number", 1, 365, true),
      new Definition(RuleSet.ENGINE_BUDGET_MS, "Planning engine", "Allocation time budget", "ms", "number", 100, 600000, true),
      new Definition(RuleSet.CAPACITY_EPSILON, "Fixed safety rules", "Capacity tolerance", "ratio", "locked", 0, 0, false));

  private final Database database;
  private final Clock clock;

  public PlanningRuleCatalogue(Database database, Clock clock) {
    this.database = database;
    this.clock = clock;
  }

  public PlanningRuleViews.Catalogue read(Actor actor) {
    return database.readAs(ModuleRole.PLANNING, actor.userId(), () -> {
      LocalDate today = LocalDate.ofInstant(clock.now(), Clock.OPERATING_ZONE);
      Map<String, Object> active = database.queryOne("""
          SELECT rule_set_id, row_version, effective_from, effective_to, note
            FROM planning.rule_sets
           WHERE effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
          """, Date.valueOf(today), Date.valueOf(today));
      Map<String, Object> scheduled = database.queryOne("""
          SELECT rule_set_id, row_version, effective_from, effective_to, note
            FROM planning.rule_sets WHERE effective_from > ?
           ORDER BY effective_from DESC LIMIT 1
          """, Date.valueOf(today));
      return new PlanningRuleViews.Catalogue(view(active), view(scheduled));
    });
  }

  private PlanningRuleViews.RuleSetView view(Map<String, Object> row) {
    if (row == null) return null;
    UUID id = (UUID) row.get("rule_set_id");
    Map<String, BigDecimal> values = new LinkedHashMap<>();
    for (Map<String, Object> item : database.query(
        "SELECT parameter_key, parameter_value FROM planning.rule_parameters WHERE rule_set_id = ?", id)) {
      values.put((String) item.get("parameter_key"), (BigDecimal) item.get("parameter_value"));
    }
    List<PlanningRuleViews.Parameter> parameters = new ArrayList<>();
    for (Definition definition : DEFINITIONS) {
      BigDecimal value = values.get(definition.key());
      if (value == null) throw new DomainException(ErrorCode.CONSTRAINT_VIOLATED,
          "Rule set is missing " + definition.key(), List.of("POL-10"));
      parameters.add(new PlanningRuleViews.Parameter(definition.key(), definition.category(),
          definition.label(), definition.unit(), definition.control(), definition.editable(),
          definition.minimum(), definition.maximum(), value));
    }
    return new PlanningRuleViews.RuleSetView(id, ((Number) row.get("row_version")).longValue(),
        ((Date) row.get("effective_from")).toLocalDate(),
        row.get("effective_to") == null ? null : ((Date) row.get("effective_to")).toLocalDate(),
        (String) row.get("note"), parameters);
  }

  public static BigDecimal validatedValue(String key, String raw) {
    Definition definition = DEFINITIONS.stream().filter(item -> item.key().equals(key))
        .findFirst().orElseThrow(() -> new DomainException(ErrorCode.VALIDATION_FAILED, "Unknown planning parameter"));
    if (!definition.editable()) throw new DomainException(ErrorCode.FORBIDDEN, "This safety value is locked");
    try {
      BigDecimal value = new BigDecimal(raw).stripTrailingZeros();
      long integer = value.longValueExact();
      if (integer < definition.minimum() || integer > definition.maximum())
        throw new ArithmeticException("outside allowed range");
      return BigDecimal.valueOf(integer);
    } catch (NumberFormatException | ArithmeticException e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED,
          definition.label() + " must be a whole number from " + definition.minimum() + " to " + definition.maximum());
    }
  }
}
