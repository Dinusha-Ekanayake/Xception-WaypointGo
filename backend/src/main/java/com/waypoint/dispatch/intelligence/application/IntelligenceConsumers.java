package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanRevised;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
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
}
