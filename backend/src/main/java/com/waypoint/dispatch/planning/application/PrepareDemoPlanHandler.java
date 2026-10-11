package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.demo.contract.DemoRuntime;
import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanStatus;
import com.waypoint.dispatch.planning.infrastructure.JdbcGenerationJobs;
import com.waypoint.dispatch.planning.infrastructure.JdbcGenerationJobs.JobRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The last step of preparing a demo day: plans the seeded orders and publishes
 * the plan, so the loaders' dock board has trips to load and each trip's driver
 * hears the plan, as on a working day. The engine runs in the generation queue
 * like any other (R-PLN-41), so the step is sent again until it answers
 * {@code PUBLISHED}: the first send queues the generation, a send while it runs
 * answers {@code GENERATING}, and the first send after it finished publishes its
 * draft through the same gate as a dispatcher's. Administrator only, Peliyagoda
 * only, and only in demo mode. A day already published is left as it is.
 */
@Component
public class PrepareDemoPlanHandler implements CommandHandler {
  static final String DEPOT = "Peliyagoda";

  private final Database database;
  private final GeneratePlanHandler generator;
  private final PlanPublication publication;
  private final JdbcPlanRepository plans;
  private final JdbcGenerationJobs jobs;
  private final PlanningDrafts drafts;
  private final GenerationSignal signal;
  private final DemoRuntime demo;
  private final IdentityQuery identity;
  private final Clock clock;

  PrepareDemoPlanHandler(Database database, GeneratePlanHandler generator, PlanPublication publication,
      JdbcPlanRepository plans, JdbcGenerationJobs jobs, PlanningDrafts drafts, GenerationSignal signal,
      DemoRuntime demo, IdentityQuery identity, Clock clock) {
    this.database = database;
    this.generator = generator;
    this.publication = publication;
    this.plans = plans;
    this.jobs = jobs;
    this.drafts = drafts;
    this.signal = signal;
    this.demo = demo;
    this.identity = identity;
    this.clock = clock;
  }

  @Override public String kind() { return "plan:PrepareDemoDay"; }
  @Override public String action() { return kind(); }
  @Override public ModuleRole moduleRole() { return ModuleRole.PLANNING; }
  @Override public String resource(Command command) { return "wpt:plan:depot:" + DEPOT; }

  @Override
  public Object handle(Actor actor, Command command) {
    if (!demo.view().enabled() || !identity.scopeOf(actor.userId()).roles().contains("admin"))
      throw new DomainException(ErrorCode.FORBIDDEN, "Demo preparation requires an administrator and enabled demo mode");
    if (command.expectedVersion() == null || command.expectedVersion() != 0)
      throw new DomainException(ErrorCode.VERSION_CONFLICT, "A new demo plan starts at version zero");
    LocalDate day = CommandPayload.of(command).date("serviceDate");
    if (!Boolean.TRUE.equals(database.queryOne("SELECT app.actor_has_depot(?) AS ok", DEPOT).get("ok")))
      throw new DomainException(ErrorCode.FORBIDDEN, DEPOT + " is outside your scope");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("serviceDate", day.toString());
    body.put("depotCode", DEPOT);
    var published = plans.published(DEPOT, day);
    if (published.isPresent()) {
      body.put("planId", published.get().planId().toString());
      body.put("status", "PUBLISHED");
      return body;
    }
    Instant now = clock.now();
    if (jobs.active(DEPOT, day).isPresent()) {
      body.put("status", "GENERATING");
      return body;
    }
    Optional<JobRow> last = jobs.latest(DEPOT, day);
    Optional<RunRow> draft = last.filter(j -> "done".equals(j.status())).flatMap(JobRow::planId)
        .flatMap(plans::findRun).filter(r -> r.status() == PlanStatus.DRAFT);
    if (draft.isPresent()) {
      body.putAll(publication.publish(actor, draft.get().planId(), draft.get().rowVersion(), now));
      body.put("status", "PUBLISHED");
      return body;
    }
    if (last.isPresent() && "failed".equals(last.get().status()))
      throw new DomainException(ErrorCode.CONFLICT,
          "Planning the demo day failed: " + last.get().error().orElse("no reason recorded"));
    generator.refuseClosedDay(DEPOT, day);
    jobs.enqueue(drafts.newId(now), DEPOT, day, false, actor.userId(), command.commandId(), now);
    database.afterCommit(signal::wake);
    body.put("status", "GENERATING");
    return body;
  }
}
