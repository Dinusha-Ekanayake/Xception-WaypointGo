package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.loading.contract.LoadingEvents.InterchangeRequested;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrderAmended;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrderCancelled;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrderPlaced;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrdersClosed;
import com.waypoint.dispatch.planning.application.PlanningRevisions.Place;
import com.waypoint.dispatch.planning.application.PlanningRevisions.Revision;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanStatus;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.planning.domain.PlanningRun.Replanned;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.AllocationRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.TripRow;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceEvents.CalendarOverridden;
import com.waypoint.dispatch.referencedata.contract.ReferenceEvents.ReferenceVersionPublished;
import com.waypoint.dispatch.referencedata.contract.ReferenceEvents.VehicleStatusChanged;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What Planning does when other modules move (decision 7 of the issue #9 plan).
 * Consumers never publish a plan, with one exception: an interchange at the dock
 * that changes exactly the requested trip and passes the whole registry. A
 * loader cannot wait for a dispatcher (R-LOD-06); everything else waits for one.
 *
 * <p>Each consumer runs as the system actor in a {@code waypoint_planning}
 * transaction the relay opens; the relay's inbox makes redelivery a no-op, and
 * each is harmless applied twice anyway.
 */
final class PlanningConsumers {
  private static final Logger log = LoggerFactory.getLogger(PlanningConsumers.class);

  private PlanningConsumers() {}

  abstract static class PlanningConsumer<E extends DomainEvent> implements EventSubscriber<E> {
    @Override
    public final ModuleRole moduleRole() {
      return ModuleRole.PLANNING;
    }
  }

  /** Marks one draft stale, once. Publication refuses a stale draft (PLN-07). */
  @Component
  static class StaleDrafts {
    private final JdbcPlanRepository plans;
    private final Metrics metrics;
    private final Clock clock;

    StaleDrafts(JdbcPlanRepository plans, Metrics metrics, Clock clock) {
      this.plans = plans;
      this.metrics = metrics;
      this.clock = clock;
    }

    void mark(RunRow draft, String because) {
      if (draft.status() == PlanStatus.DRAFT && !draft.stale()) {
        plans.markStale(draft.planId(), draft.rowVersion(), clock.now());
        metrics.increment("waypoint.plan.draft_stale", "because", because);
      }
    }

    void markDay(String depot, java.time.LocalDate date, String because) {
      plans.latestDraft(depot, date).ifPresent(d -> mark(d, because));
    }
  }

  /** The day's demand is final: build the draft the dispatcher will open. Never publishes. */
  @Component
  static class OnOrdersClosed extends PlanningConsumer<OrdersClosed> {
    private final GeneratePlanHandler generator;
    private final JdbcPlanRepository plans;
    private final Metrics metrics;
    private final Clock clock;

    OnOrdersClosed(GeneratePlanHandler generator, JdbcPlanRepository plans, Metrics metrics, Clock clock) {
      this.generator = generator;
      this.plans = plans;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "planning.on-orders-closed";
    }

    @Override
    public Class<OrdersClosed> eventType() {
      return OrdersClosed.class;
    }

    @Override
    public void on(EventEnvelope<OrdersClosed> envelope) {
      OrdersClosed closed = envelope.payload();
      if (plans.published(closed.depotCode(), closed.serviceDate()).isPresent()) {
        return;
      }
      try {
        generator.generate(Actor.SYSTEM, closed.depotCode(), closed.serviceDate(), clock.now(), envelope.eventId());
        metrics.increment("waypoint.plan.auto_generated");
      } catch (DomainException e) {
        // Degrade visibly: the dispatcher generates by hand and sees the same refusal.
        metrics.increment("waypoint.plan.auto_generate_failed", "code", e.code().name());
        log.warn("No draft generated for {} on {}: {}", closed.depotCode(), closed.serviceDate(), e.getMessage());
      }
    }
  }

  @Component
  static class OnOrderPlaced extends PlanningConsumer<OrderPlaced> {
    private final StaleDrafts stale;

    OnOrderPlaced(StaleDrafts stale) {
      this.stale = stale;
    }

    @Override
    public String consumerName() {
      return "planning.on-order-placed";
    }

    @Override
    public Class<OrderPlaced> eventType() {
      return OrderPlaced.class;
    }

    @Override
    public void on(EventEnvelope<OrderPlaced> envelope) {
      stale.markDay(envelope.payload().depotCode(), envelope.payload().deliveryDate(), "order.placed");
    }
  }

  @Component
  static class OnOrderAmended extends PlanningConsumer<OrderAmended> {
    private final StaleDrafts stale;

    OnOrderAmended(StaleDrafts stale) {
      this.stale = stale;
    }

    @Override
    public String consumerName() {
      return "planning.on-order-amended";
    }

    @Override
    public Class<OrderAmended> eventType() {
      return OrderAmended.class;
    }

    @Override
    public void on(EventEnvelope<OrderAmended> envelope) {
      stale.markDay(envelope.payload().depotCode(), envelope.payload().deliveryDate(), "order.amended");
    }
  }

  /** {@code order.cancelled} carries no date, so the drafts are found through Planning's own allocations. */
  @Component
  static class OnOrderCancelled extends PlanningConsumer<OrderCancelled> {
    private final JdbcPlanRepository plans;
    private final StaleDrafts stale;

    OnOrderCancelled(JdbcPlanRepository plans, StaleDrafts stale) {
      this.plans = plans;
      this.stale = stale;
    }

    @Override
    public String consumerName() {
      return "planning.on-order-cancelled";
    }

    @Override
    public Class<OrderCancelled> eventType() {
      return OrderCancelled.class;
    }

    @Override
    public void on(EventEnvelope<OrderCancelled> envelope) {
      plans.openDraftsWithOrder(envelope.payload().orderId()).forEach(d -> stale.mark(d, "order.cancelled"));
    }
  }

  /** A draft built on an older reference version can no longer publish (PLN-14); say so now. */
  @Component
  static class OnReferenceVersionPublished extends PlanningConsumer<ReferenceVersionPublished> {
    private final JdbcPlanRepository plans;
    private final StaleDrafts stale;

    OnReferenceVersionPublished(JdbcPlanRepository plans, StaleDrafts stale) {
      this.plans = plans;
      this.stale = stale;
    }

    @Override
    public String consumerName() {
      return "planning.on-reference-version-published";
    }

    @Override
    public Class<ReferenceVersionPublished> eventType() {
      return ReferenceVersionPublished.class;
    }

    @Override
    public void on(EventEnvelope<ReferenceVersionPublished> envelope) {
      UUID version = envelope.payload().versionId();
      plans.openDrafts().stream()
          .filter(d -> !d.referenceVersionId().equals(version))
          .forEach(d -> stale.mark(d, "reference.version_published"));
    }
  }

  /** A day that stops operating with a plan already published is the dispatcher's call; raise it. */
  @Component
  static class OnCalendarOverridden extends PlanningConsumer<CalendarOverridden> {
    private final JdbcPlanRepository plans;
    private final StaleDrafts stale;
    private final Metrics metrics;

    OnCalendarOverridden(JdbcPlanRepository plans, StaleDrafts stale, Metrics metrics) {
      this.plans = plans;
      this.stale = stale;
      this.metrics = metrics;
    }

    @Override
    public String consumerName() {
      return "planning.on-calendar-overridden";
    }

    @Override
    public Class<CalendarOverridden> eventType() {
      return CalendarOverridden.class;
    }

    @Override
    public void on(EventEnvelope<CalendarOverridden> envelope) {
      CalendarOverridden day = envelope.payload();
      plans.openDraftsOn(day.date()).forEach(d -> stale.mark(d, "calendar.overridden"));
      if (!day.operating()) {
        for (RunRow published : plans.publishedOn(day.date())) {
          metrics.increment("waypoint.plan.published_on_closed_day", "depot", published.depotCode());
          log.warn("Plan {} is published for {} on {}, which no longer operates", published.planId(),
              published.depotCode(), day.date());
        }
      }
    }
  }

  /**
   * A vehicle out of service after publication: a revision draft that replans
   * only its trips, onto other vehicles or deferred as units (PLN-04, FLT-01),
   * and an alert. The dispatcher publishes it.
   */
  @Component
  static class OnVehicleStatusChanged extends PlanningConsumer<VehicleStatusChanged> {
    private final JdbcPlanRepository plans;
    private final PlanningRevisions revisions;
    private final PlanningDrafts drafts;
    private final Metrics metrics;
    private final Clock clock;

    OnVehicleStatusChanged(
        JdbcPlanRepository plans, PlanningRevisions revisions, PlanningDrafts drafts, Metrics metrics, Clock clock) {
      this.plans = plans;
      this.revisions = revisions;
      this.drafts = drafts;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "planning.on-vehicle-status-changed";
    }

    @Override
    public Class<VehicleStatusChanged> eventType() {
      return VehicleStatusChanged.class;
    }

    @Override
    public void on(EventEnvelope<VehicleStatusChanged> envelope) {
      VehicleStatusChanged change = envelope.payload();
      if ("available".equals(change.status())) {
        return;
      }
      Instant now = clock.now();
      String reason = change.vehicleId() + " is " + change.status() + change.reason().map(r -> ": " + r).orElse("");
      for (RunRow published : plans.publishedUsing(change.vehicleId(), change.serviceDate())) {
        Revision revision =
            revisions.revise(
                published, reason, Actor.SYSTEM_ID, drafts.newId(now),
                drafts.nextVersion(published.depotCode(), published.serviceDate()));
        PlanningRun run = revisions.replanVehicle(revision, change.vehicleId(), reason, Actor.SYSTEM_ID);
        revisions.store(published, run, revision.built(), Actor.SYSTEM_ID, now, envelope.eventId());
        metrics.increment("waypoint.plan.vehicle_lost", "depot", published.depotCode());
        log.warn("Plan {} uses {}; revision draft {} awaits the dispatcher", published.planId(), reason, run.planId());
      }
    }
  }

  /**
   * A loader asking for a substitute vehicle. Planning replans that trip as the
   * system, the requested vehicle first, and publishes the revision itself only
   * when exactly that trip moved and the whole plan passes; otherwise the
   * revision waits for the dispatcher and an alert says why (R-LOD-06, R-LOD-09,
   * LOD-03).
   */
  @Component
  static class OnInterchangeRequested extends PlanningConsumer<InterchangeRequested> {
    private final JdbcPlanRepository plans;
    private final PlanningRevisions revisions;
    private final PlanningDrafts drafts;
    private final PlanPublication publication;
    private final Metrics metrics;
    private final Clock clock;

    OnInterchangeRequested(
        JdbcPlanRepository plans,
        PlanningRevisions revisions,
        PlanningDrafts drafts,
        PlanPublication publication,
        Metrics metrics,
        Clock clock) {
      this.plans = plans;
      this.revisions = revisions;
      this.drafts = drafts;
      this.publication = publication;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "planning.on-interchange-requested";
    }

    @Override
    public Class<InterchangeRequested> eventType() {
      return InterchangeRequested.class;
    }

    @Override
    public void on(EventEnvelope<InterchangeRequested> envelope) {
      InterchangeRequested request = envelope.payload();
      Optional<RunRow> live =
          plans.runsWithTrip(request.tripId()).stream().filter(r -> r.status() == PlanStatus.PUBLISHED).findFirst();
      if (live.isEmpty()) {
        metrics.increment("waypoint.plan.interchange_outcome", "outcome", "no_published_plan");
        log.warn("Interchange for trip {} names no published plan", request.tripId());
        return;
      }
      RunRow published = live.get();
      Instant now = clock.now();
      String reason = "interchange " + request.currentVehicleId() + " -> " + request.replacementVehicleId() + ": "
          + request.reason();
      Revision revision =
          revisions.revise(
              published, reason, Actor.SYSTEM_ID, drafts.newId(now),
              drafts.nextVersion(published.depotCode(), published.serviceDate()));
      Place place = revisions.locate(published.planId(), request.tripId(), revision.run());
      Replanned replanned =
          revisions.replanTrip(revision, place, Optional.of(request.replacementVehicleId()), reason, Actor.SYSTEM_ID);
      revisions.store(published, replanned.run(), revision.built(), Actor.SYSTEM_ID, now, envelope.eventId());

      String outcome;
      if (replanned.movedTo().isEmpty()) {
        outcome = "deferred_as_unit";
      } else if (!onlyTripChanged(published, request.tripId(), replanned.run())) {
        outcome = "needs_dispatcher";
      } else {
        try {
          publication.publish(Actor.SYSTEM, replanned.run().planId(), replanned.run().rowVersion(), now);
          outcome = "published";
        } catch (DomainException e) {
          outcome = "needs_dispatcher";
          log.warn("Interchange revision {} not published: {}", replanned.run().planId(), e.getMessage());
        }
      }
      metrics.increment("waypoint.plan.interchange_outcome", "outcome", outcome);
    }

    /** Every order off the trip keeps its decision, vehicle and trip number. */
    private boolean onlyTripChanged(RunRow published, UUID tripId, PlanningRun revision) {
      Map<UUID, TripRow> trips =
          plans.trips(published.planId()).stream().collect(Collectors.toMap(TripRow::tripId, Function.identity()));
      Map<UUID, String> before = new HashMap<>();
      Set<UUID> onTrip = new java.util.HashSet<>();
      for (AllocationRow a : plans.allocations(published.planId())) {
        if (a.tripId().equals(Optional.of(tripId))) {
          onTrip.add(a.orderId());
        }
        Optional<TripRow> t = a.tripId().map(trips::get);
        before.put(a.orderId(), a.decision() + t.map(r -> "/" + r.vehicleId() + "/" + r.tripNumber()).orElse(""));
      }
      List<OrderDecision> after = revision.decisions();
      if (after.size() != before.size()) {
        return false;
      }
      for (OrderDecision d : after) {
        if (onTrip.contains(d.orderId())) {
          continue;
        }
        String now = d.decision() + d.vehicleId().map(v -> "/" + v + "/" + d.tripNumber().orElseThrow()).orElse("");
        if (!now.equals(before.get(d.orderId()))) {
          return false;
        }
      }
      return true;
    }
  }
}
