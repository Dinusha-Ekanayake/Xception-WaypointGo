package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.KpiViews.KpiCountedView;
import com.waypoint.dispatch.intelligence.contract.KpiViews.KpiEntryView;
import com.waypoint.dispatch.intelligence.contract.KpiViews.KpiReportView;
import com.waypoint.dispatch.intelligence.contract.KpiViews.KpiWeekView;
import com.waypoint.dispatch.intelligence.domain.KpiBaseline;
import com.waypoint.dispatch.intelligence.domain.KpiBaseline.StopFact;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcKpiRepository;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * A depot's pilot KPIs for the last weeks (roadmap B-05): what the depot
 * recorded, and beside it what the app counted from its own finished stops.
 * A depot outside the reader's scope is 403 and an audit entry, never an empty
 * report that looks like a quiet depot.
 */
@Component
public class KpiQuery {
  /** At most half a year; the screen asks for eight, four before the pilot and four of it. */
  static final int MAX_WEEKS = 26;

  private final Database database;
  private final JdbcKpiRepository repository;
  private final ReferenceQuery reference;
  private final AuditLog audit;
  private final Clock clock;

  public KpiQuery(Database database, JdbcKpiRepository repository, ReferenceQuery reference, AuditLog audit, Clock clock) {
    this.database = database;
    this.repository = repository;
    this.reference = reference;
    this.audit = audit;
    this.clock = clock;
  }

  public KpiReportView weeks(Actor actor, String depot, int weeks) {
    int span = Math.max(1, Math.min(MAX_WEEKS, weeks));
    boolean inScope = database.readAs(ModuleRole.ML, actor.userId(), () -> Boolean.TRUE.equals(
        database.queryOne("SELECT app.actor_has_depot(?) AS ok", depot).get("ok")));
    if (!inScope) {
      String resource = "wpt:ml:kpi:" + depot;
      audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), IntelligenceDataQuery.READ, resource, "outside the actor's scope"));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + " is outside the actor's scope");
    }
    LocalDate today = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate();
    LocalDate last = KpiBaseline.weekOf(today);
    LocalDate first = last.minusWeeks(span - 1L);
    var recorded = database.readAs(ModuleRole.ML, actor.userId(), () -> repository.current(depot, first, last));
    var stops = database.readAs(ModuleRole.ML, actor.userId(), () -> repository.finishedStops(depot, first, last.plusDays(6)));

    Map<String, Boolean> fresh = new HashMap<>();
    List<StopFact> facts = new ArrayList<>();
    for (Map<String, Object> s : stops) {
      String outlet = (String) s.get("outlet_id");
      boolean isFresh = fresh.computeIfAbsent(outlet, id ->
          reference.outlet(id, null).map(o -> "Fresh".equalsIgnoreCase(o.brandCode())).orElse(false));
      facts.add(new StopFact(
          ((Date) s.get("service_date")).toLocalDate(),
          isFresh,
          (String) s.get("outcome"),
          s.get("late_minutes") == null ? null : ((Number) s.get("late_minutes")).intValue(),
          s.get("arrived_at") == null ? null : ((Timestamp) s.get("arrived_at")).toInstant(),
          "device".equals(s.get("label_source"))));
    }
    var counted = KpiBaseline.count(facts, Clock.OPERATING_ZONE);

    Map<LocalDate, Map<String, KpiEntryView>> byWeek = new TreeMap<>();
    recorded.forEach(r -> byWeek.computeIfAbsent(r.weekStart(), w -> new TreeMap<>()).put(r.kpi(), r.entry()));
    List<KpiWeekView> out = new ArrayList<>();
    for (LocalDate week = last; !week.isBefore(first); week = week.minusWeeks(1)) {
      Map<String, KpiCountedView> app = new TreeMap<>();
      counted.getOrDefault(week, Map.of()).forEach((kpi, c) -> app.put(kpi, new KpiCountedView(c.value(), c.stops())));
      out.add(new KpiWeekView(week, byWeek.getOrDefault(week, Map.of()), app));
    }
    return new KpiReportView(depot, KpiBaseline.KPIS,
        List.of(KpiBaseline.FRESH_ON_TIME, KpiBaseline.FRESH_BEFORE_8, KpiBaseline.ADOPTION), out);
  }
}
