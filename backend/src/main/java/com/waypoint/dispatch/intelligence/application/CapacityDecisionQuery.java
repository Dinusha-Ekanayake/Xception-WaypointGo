package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.CapacityViews.CapacityDecisionView;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcCapacityRepository;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.List;
import org.springframework.stereotype.Component;

/** The capacity decisions for a depot from this week on (roadmap T2-01), newest first, within the actor's scope. */
@Component
public class CapacityDecisionQuery {
  private final Database database;
  private final JdbcCapacityRepository repository;
  private final AuditLog audit;
  private final Clock clock;

  public CapacityDecisionQuery(Database database, JdbcCapacityRepository repository, AuditLog audit, Clock clock) {
    this.database = database;
    this.repository = repository;
    this.audit = audit;
    this.clock = clock;
  }

  public List<CapacityDecisionView> fromThisWeek(Actor actor, String depotCode) {
    // Deny by default: a depot outside the actor's scope is 403 and an audit
    // entry, never an empty list that looks like no decisions were made.
    boolean inScope = database.readAs(ModuleRole.ML, actor.userId(), () -> Boolean.TRUE.equals(
        database.queryOne("SELECT app.actor_has_depot(?) AS ok", depotCode).get("ok")));
    if (!inScope) {
      String resource = "wpt:ml:forecast:" + depotCode;
      String reason = "outside the actor's scope";
      audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), IntelligenceDataQuery.READ, resource, reason));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason);
    }
    LocalDate today = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate();
    int year = today.get(IsoFields.WEEK_BASED_YEAR);
    int week = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    return database.readAs(ModuleRole.ML, actor.userId(), () -> repository.fromWeek(depotCode, year, week));
  }
}
