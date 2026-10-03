package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.application.PlanningDrafts.Opened;
import com.waypoint.dispatch.planning.contract.PlanEvents.OrderDeferred;
import com.waypoint.dispatch.planning.contract.PlanEvents.OrderUnservable;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanRevised;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedTrip;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.PlanVerification.Violation;
import com.waypoint.dispatch.planning.domain.PlanDiff;
import com.waypoint.dispatch.planning.domain.PlanningRun.Stamps;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import com.waypoint.dispatch.planning.domain.PublicationGate;
import com.waypoint.dispatch.planning.domain.PublicationGate.InForce;
import com.waypoint.dispatch.planning.domain.RuleSet;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.AllocationRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.DeferralRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The publication gate. A draft is published only when it was built on the
 * demand, reference data, rules and priority policy in force now (PLN-07,
 * PLN-14, POL-02), and the whole plan passes the same registry the engine used.
 * Every refusal names its rules, all at once, and nothing is written before it.
 *
 * <p>A draft that revises the day's plan supersedes it in the same transaction
 * and travels as {@code plan.revised}; any other second plan for the day is
 * refused (R-PLN-28). Deferrals and unservable orders are announced only when
 * this version made them, so a revision never makes Ordering roll an order twice.
 */
@Component
class PlanPublication {
  private final PlanningDrafts drafts;
  private final JdbcPlanRepository plans;
  private final ReferenceQuery reference;
  private final ConstraintRegistry registry;
  private final EventPublisher events;
  private final Metrics metrics;
  private final PlanDataQuery view;

  PlanPublication(
      PlanningDrafts drafts,
      JdbcPlanRepository plans,
      ReferenceQuery reference,
      ConstraintRegistry registry,
      EventPublisher events,
      Metrics metrics,
      PlanDataQuery view) {
    this.drafts = drafts;
    this.plans = plans;
    this.reference = reference;
    this.registry = registry;
    this.events = events;
    this.metrics = metrics;
    this.view = view;
  }

  Map<String, Object> publish(Actor actor, UUID planId, long expected, Instant now) {
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
    } else if (row.supersedes().isPresent()) {
      throw new DomainException(
          ErrorCode.CONFLICT,
          "plan " + row.supersedes().get() + " this draft revises is no longer the published plan; revise again",
          List.of("R-PLN-28"));
    }
    long version = plans.publish(planId, expected, actor.userId(), now);
    announce(actor, row);
    metrics.increment(row.supersedes().isPresent() ? "waypoint.plan.revised" : "waypoint.plan.published");

    return Map.of(
        "planId", planId.toString(),
        "planVersion", row.planVersion(),
        "status", "PUBLISHED",
        "rowVersion", version);
  }

  private void announce(Actor actor, RunRow row) {
    UUID planId = row.planId();
    List<AllocationRow> allocations = plans.allocations(planId);
    List<PlannedTrip> trips = PlanRecords.plannedTrips(plans.trips(planId), allocations);
    Set<UUID> deferredBefore = Set.of();
    Set<UUID> unservableBefore = Set.of();
    if (row.supersedes().isPresent()) {
      UUID previous = row.supersedes().get();
      deferredBefore = plans.deferrals(previous).stream().map(DeferralRow::orderId).collect(Collectors.toSet());
      unservableBefore =
          plans.allocations(previous).stream()
              .filter(a -> a.decision() == AllocationDecision.UNSERVABLE)
              .map(AllocationRow::orderId)
              .collect(Collectors.toSet());
      // What a driver or a store would see differently, so only they are told (R-NOT-12).
      PlanView before = view.draftView(plans.findRun(previous).orElseThrow());
      PlanView after = view.draftView(plans.findRun(planId).orElseThrow());
      events.publish(
          actor,
          new PlanRevised(
              planId, row.depotCode(), row.serviceDate(), row.planVersion(), previous,
              row.revisionReason().orElse("revised"), trips,
              Optional.of(PlanDiff.changedTrips(before, after)),
              Optional.of(PlanDiff.affectedOutlets(before, after))));
    } else {
      events.publish(
          actor,
          new PlanPublished(planId, row.depotCode(), row.serviceDate(), row.planVersion(), Optional.empty(), trips));
    }
    for (DeferralRow d : plans.deferrals(planId)) {
      if (!deferredBefore.contains(d.orderId())) {
        events.publish(
            actor,
            new OrderDeferred(d.orderId(), planId, d.outletId(), d.serviceDate(), d.ruleId(), d.reason(), d.skipCount()));
      }
    }
    for (AllocationRow a : allocations) {
      if (a.decision() == AllocationDecision.UNSERVABLE && !unservableBefore.contains(a.orderId())) {
        events.publish(
            actor, new OrderUnservable(a.orderId(), planId, a.outletId(), a.bindingRule().orElseThrow(), a.reason()));
        metrics.increment("waypoint.plan.unservable");
      }
    }
  }
}
