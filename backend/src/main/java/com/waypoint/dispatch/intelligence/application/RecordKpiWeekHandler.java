package com.waypoint.dispatch.intelligence.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.intelligence.contract.KpiViews;
import com.waypoint.dispatch.intelligence.domain.KpiBaseline;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcKpiRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A depot's weekly business KPI figures for the pilot baseline (roadmap B-05),
 * as the depot measured them on paper, in spreadsheets and phone logs. Every
 * figure carries who entered it and when (rule 8) and is only ever added; a
 * correction is a newer figure for the same week, so no expectedVersion is
 * needed and the command id makes a retry harmless. A depot outside the actor's
 * scope is refused before anything is written.
 */
@Component
class RecordKpiWeekHandler implements CommandHandler {
  private final Database database;
  private final JdbcKpiRepository repository;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  RecordKpiWeekHandler(Database database, JdbcKpiRepository repository, Metrics metrics, Clock clock) {
    this.database = database;
    this.repository = repository;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return KpiViews.RECORD;
  }

  @Override
  public String action() {
    return kind();
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ML;
  }

  @Override
  public String resource(Command command) {
    return "wpt:ml:kpi:" + CommandPayload.of(command).text("depotCode");
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload p = CommandPayload.of(command);
    String depot = p.requiredText("depotCode");
    LocalDate week = p.date("weekStart");
    String note = p.text("note");
    if (note != null && note.length() > 500) throw new DomainException(ErrorCode.VALIDATION_FAILED, "note is longer than 500 characters");
    JsonNode values = command.payload() == null ? null : command.payload().get("values");
    if (values == null || !values.isObject() || values.isEmpty()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "values needs at least one KPI and its figure");
    }
    Map<String, BigDecimal> figures = new LinkedHashMap<>();
    values.fields().forEachRemaining(e -> {
      if (!e.getValue().isNumber()) throw new DomainException(ErrorCode.VALIDATION_FAILED, e.getKey() + " must be a number");
      BigDecimal value = e.getValue().decimalValue().setScale(2, java.math.RoundingMode.HALF_UP);
      KpiBaseline.check(e.getKey(), week, value);
      figures.put(e.getKey(), value);
    });
    boolean inScope = Boolean.TRUE.equals(database.queryOne("SELECT app.actor_has_depot(?) AS ok", depot).get("ok"));
    if (!inScope) throw new DomainException(ErrorCode.FORBIDDEN, "wpt:ml:kpi:" + depot + " is outside the actor's scope");
    Instant now = clock.now();
    List<String> recorded = new ArrayList<>();
    figures.forEach((kpi, value) -> {
      repository.insert(UuidV7.generate(now, random), depot, week, kpi, value, note, actor.userId(), now);
      recorded.add(kpi);
    });
    metrics.count("waypoint.ml.kpi_recorded", recorded.size());
    return Map.of("depotCode", depot, "weekStart", week.toString(), "recorded", recorded);
  }
}
