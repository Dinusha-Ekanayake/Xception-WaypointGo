package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliverySkipped;
import com.waypoint.dispatch.intelligence.contract.AttentionViews.AttentionKind;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcDecisionSuggestions;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcAttentionRepository;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository;
import com.waypoint.dispatch.issues.contract.IssueEvents.DecisionAnswered;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanRevised;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import org.springframework.stereotype.Component;

/**
 * A published plan is queued for scoring, nothing more: the model is called
 * later by {@link PlanScoringJob}, outside any transaction (R-ML-01). A
 * redelivered event queues nothing new, because a plan is scored once.
 */
final class IntelligenceConsumers {
  private IntelligenceConsumers() {}

  /** Runs as {@code waypoint_ml} for the system actor. */
  @Component
  static class Queue {
    private final JdbcIntelligenceRepository repository;
    private final Metrics metrics;
    private final Clock clock;

    Queue(JdbcIntelligenceRepository repository, Metrics metrics, Clock clock) {
      this.repository = repository;
      this.metrics = metrics;
      this.clock = clock;
    }

    void request(java.util.UUID planId, String depot, java.time.LocalDate date, int version) {
      if (repository.requestScoring(planId, depot, date, version, clock.now())) {
        metrics.increment("waypoint.ml.scoring_requested");
      }
    }
  }

  @Component
  static class OnPlanPublished implements EventSubscriber<PlanPublished> {
    private final Queue queue;

    OnPlanPublished(Queue queue) {
      this.queue = queue;
    }

    @Override
    public String consumerName() {
      return "ml.on-plan-published";
    }

    @Override
    public Class<PlanPublished> eventType() {
      return PlanPublished.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.ML;
    }

    @Override
    public void on(EventEnvelope<PlanPublished> envelope) {
      PlanPublished e = envelope.payload();
      queue.request(e.planId(), e.depotCode(), e.serviceDate(), e.planVersion());
    }
  }

  /** A revision is a new published plan with its own stops; the old one keeps its scoring. */
  @Component
  static class OnPlanRevised implements EventSubscriber<PlanRevised> {
    private final Queue queue;

    OnPlanRevised(Queue queue) {
      this.queue = queue;
    }

    @Override
    public String consumerName() {
      return "ml.on-plan-revised";
    }

    @Override
    public Class<PlanRevised> eventType() {
      return PlanRevised.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.ML;
    }

    @Override
    public void on(EventEnvelope<PlanRevised> envelope) {
      PlanRevised e = envelope.payload();
      queue.request(e.planId(), e.depotCode(), e.serviceDate(), e.planVersion());
    }
  }

  /**
   * FF-09: a store said Yes to receiving late. The window-at-risk item on that
   * stop is settled with the store's answer as the reason, so it leaves the
   * queue and is not reminded of. Applying the event twice changes nothing.
   */
  @Component
  static class OnDecisionAnswered implements EventSubscriber<DecisionAnswered> {
    private final JdbcAttentionRepository attention;
    private final Clock clock;

    OnDecisionAnswered(JdbcAttentionRepository attention, Clock clock) {
      this.attention = attention;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "ml.on-decision-answered";
    }

    @Override
    public Class<DecisionAnswered> eventType() {
      return DecisionAnswered.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.ML;
    }

    @Override
    public void on(EventEnvelope<DecisionAnswered> envelope) {
      DecisionAnswered e = envelope.payload();
      if (!"receive_late".equals(e.kind()) || !"yes".equals(e.answer())) {
        return;
      }
      attention.state(e.depotCode(), e.deliveryId(), AttentionKind.WINDOW_AT_RISK)
          .filter(s -> !s.acknowledged())
          .ifPresent(s -> attention.acknowledge(
              e.deliveryId(), AttentionKind.WINDOW_AT_RISK, Actor.SYSTEM_ID,
              e.outletId() + " can receive at about "
                  + java.time.LocalTime.ofInstant(e.receiveAt().orElseThrow(), Clock.OPERATING_ZONE)
                      .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                  + ("phone".equals(e.via()) ? " (by phone)" : "chat".equals(e.via()) ? " (in the chat)" : ""),
              clock.now(), s.rowVersion()));
    }
  }

  /**
   * A decision taken without a chip (docs/issues/decision-suggestions/PLAN.md):
   * the dispatcher skipped the store or ended the trip from Live or a button,
   * with the chips beside a report on screen in the last three hours. The
   * decision row is written from that impression, with what was taken and that
   * no chip took it; a decision a chip already logged is not written again.
   */
  @Component
  static class OnDeliverySkippedWithoutAChip implements EventSubscriber<DeliverySkipped> {
    static final java.time.Duration RECENT = java.time.Duration.ofHours(3);

    private final JdbcDecisionSuggestions log;
    private final Metrics metrics;
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    OnDeliverySkippedWithoutAChip(JdbcDecisionSuggestions log, Metrics metrics) {
      this.log = log;
      this.metrics = metrics;
    }

    @Override
    public String consumerName() {
      return "ml.on-delivery-skipped-suggestions";
    }

    @Override
    public Class<DeliverySkipped> eventType() {
      return DeliverySkipped.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.ML;
    }

    @Override
    public void on(EventEnvelope<DeliverySkipped> envelope) {
      DeliverySkipped e = envelope.payload();
      String taken = e.wholeTrip() ? "END_TRIP" : "CANCEL".equals(e.then()) ? "SKIP_CANCEL" : "SKIP_NEXT_DAY";
      log.undecidedImpression(e.deliveryId(), e.skippedAt().minus(RECENT)).ifPresent(row -> {
        java.util.List<String> shown = java.util.List.of(((String) row.get("shown")).split(","));
        String chosen = shown.contains(taken) ? taken : null;
        log.insertTaken(
            com.waypoint.dispatch.shared.util.UuidV7.generate(e.skippedAt(), random), e.deliveryId(),
            (String) row.get("depot_code"), (String) row.get("situation"),
            ((Number) row.get("catalogue_version")).intValue(), shown, chosen, taken,
            envelope.actorId().orElse(Actor.SYSTEM_ID), e.skippedAt());
        metrics.increment("waypoint.ml.suggestion_without_chip", "taken", taken.toLowerCase(java.util.Locale.ROOT),
            "was_suggested", String.valueOf(chosen != null));
      });
    }
  }
}
