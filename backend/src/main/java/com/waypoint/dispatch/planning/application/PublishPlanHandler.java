package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.application.PlanningDrafts.Opened;
import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.contract.PlanEvents.OrderDeferred;
import com.waypoint.dispatch.planning.contract.PlanEvents.OrderUnservable;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.PlanVerification.Violation;
import com.waypoint.dispatch.planning.domain.PlanningRun.Stamps;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import com.waypoint.dispatch.planning.domain.PublicationGate;
import com.waypoint.dispatch.planning.domain.PublicationGate.InForce;
import com.waypoint.dispatch.planning.domain.RuleSet;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.AllocationRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.DeferralRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The publication gate. A draft is published only when it was built on the
 * demand, reference data, rules and priority policy in force now (PLN-07,
 * PLN-14, POL-02), and the whole plan passes the same registry the engine used.
 * Every refusal names its rules, all at once.
 *
 * <p>Publishing a draft that revises the day's plan supersedes that plan in the
 * same transaction; any other second plan for the day is refused (R-PLN-28).
 * The published plan travels in {@code plan.published} with its trips and stops,
 * so no other module ever reads Planning's tables.
 */
@Component
public class PublishPlanHandler implements CommandHandler {
  private final PlanningDrafts drafts;
  private final JdbcPlanRepository plans;
  private final ReferenceQuery reference;
  private final ConstraintRegistry registry;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;

  public PublishPlanHandler(
      PlanningDrafts drafts,
      JdbcPlanRepository plans,
      ReferenceQuery reference,
      ConstraintRegistry registry,
      EventPublisher events,
      Metrics metrics,
      Clock clock) {
    this.drafts = drafts;
    this.plans = plans;
    this.reference = reference;
    this.registry = registry;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return PlanCommands.PUBLISH;
  }

  @Override
  public String action() {
    return PlanCommands.PUBLISH;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.PLANNING;
  }

  @Override
  public String resource(Command command) {
    UUID id = CommandPayload.of(command).optionalUuid("planId");
    return id == null ? null : "wpt:plan:plan:" + id;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    long expected = PlanningDrafts.expectedVersion(command);
    UUID planId = CommandPayload.of(command).uuid("planId");
    Instant now = clock.now();

    Opened opened = drafts.open(planId, expected);
    RunRow row = opened.row();
    InForce inForce =
        new InForce(
            opened.built().fingerprint(),
            reference.currentVersionId(),
            plans.effectiveRuleSet(row.serviceDate()).map(RuleSet::id),
            plans.effectivePolicy(row.depotCode(), row.serviceDate()).map(PriorityPolicy::id));
    List<Violation> violations =
        new ArrayList<>(
            PublicationGate.check(
                row.planId(),
                new Stamps(row.referenceVersionId(), row.ruleSetId(), row.priorityPolicyVersionId()),
                row.demandFingerprint(),
                row.stale(),
                inForce));
    if (violations.isEmpty()) {
      violations.addAll(
          drafts.run(opened)
              .verify(opened.built().orders().keySet(), opened.built().problem().context(), registry));
    }
    if (!violations.isEmpty()) {
      violations.stream().map(Violation::ruleId).distinct()
          .forEach(rule -> metrics.increment("waypoint.plan.publication_blocked", "rule", rule));
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "plan " + planId + " cannot be published: "
              + String.join("; ", violations.stream().map(v -> v.ruleId() + " " + v.subject() + " " + v.reason()).toList()),
          violations.stream().map(Violation::ruleId).distinct().toList());
    }

    Optional<RunRow> current = plans.published(row.depotCode(), row.serviceDate());
    if (current.isPresent()) {
      RunRow published = current.get();
      if (!row.supersedes().equals(Optional.of(published.planId()))) {
        throw new DomainException(
            ErrorCode.CONFLICT,
            "plan " + published.planId() + " is already published for " + row.depotCode() + " on "
                + row.serviceDate() + "; revise it instead",
            List.of("R-PLN-28"));
      }
      plans.supersede(published.planId(), published.rowVersion(), now);
    }
    long version = plans.publish(planId, expected, actor.userId(), now);

    List<AllocationRow> allocations = plans.allocations(planId);
    events.publish(
        actor,
        new PlanPublished(
            planId,
            row.depotCode(),
            row.serviceDate(),
            row.planVersion(),
            row.supersedes(),
            PlanRecords.plannedTrips(plans.trips(planId), allocations)));
    for (DeferralRow d : plans.deferrals(planId)) {
      events.publish(
          actor,
          new OrderDeferred(d.orderId(), planId, d.outletId(), d.serviceDate(), d.ruleId(), d.reason(), d.skipCount()));
    }
    for (AllocationRow a : allocations) {
      if (a.decision() == AllocationDecision.UNSERVABLE) {
        events.publish(
            actor, new OrderUnservable(a.orderId(), planId, a.outletId(), a.bindingRule().orElseThrow(), a.reason()));
        metrics.increment("waypoint.plan.unservable");
      }
    }
    metrics.increment("waypoint.plan.published");

    return Map.of(
        "planId", planId.toString(),
        "planVersion", row.planVersion(),
        "status", "PUBLISHED",
        "rowVersion", version);
  }
}
