package com.waypoint.dispatch.issues.application;

import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetStopView;
import com.waypoint.dispatch.intelligence.contract.AttentionEvents.AttentionRaised;
import com.waypoint.dispatch.issues.application.DecisionHandlers.DecisionAsker;
import com.waypoint.dispatch.issues.application.DecisionHandlers.Trip;
import com.waypoint.dispatch.issues.domain.DecisionRules;
import com.waypoint.dispatch.issues.domain.DecisionRules.Kind;
import com.waypoint.dispatch.issues.infrastructure.JdbcDecisionRequests;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * FF-09, R-ISS-15: the system asks the store by itself when the attention watch
 * finds a stop at risk, so nobody waits for a dispatcher with many trips to
 * notice. A window about to be missed at an ordinary store asks whether someone
 * can receive later; a stop not delivered asks the next store on the trip to help
 * at its dock. Once per stop and question, whatever the dispatcher did since; a
 * depot can turn it off ({@code decision.auto_ask} = off). The answer still goes
 * to the dispatcher, who decides.
 */
@Component
class DecisionConsumers implements EventSubscriber<AttentionRaised> {
  static final String AUTO_ASK = "decision.auto_ask";

  private final DecisionAsker asker;
  private final JdbcDecisionRequests decisions;
  private final JdbcIssueRepository issues;
  private final Metrics metrics;
  private final Clock clock;

  DecisionConsumers(
      DecisionAsker asker, JdbcDecisionRequests decisions, JdbcIssueRepository issues, Metrics metrics, Clock clock) {
    this.asker = asker;
    this.decisions = decisions;
    this.issues = issues;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String consumerName() {
    return "issues.on-attention-raised";
  }

  @Override
  public Class<AttentionRaised> eventType() {
    return AttentionRaised.class;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ISSUES;
  }

  @Override
  public void on(EventEnvelope<AttentionRaised> envelope) {
    AttentionRaised e = envelope.payload();
    Instant now = clock.now();
    if ("off".equalsIgnoreCase(issues.parameters(now.atZone(Clock.OPERATING_ZONE).toLocalDate()).getOrDefault(AUTO_ASK, "on"))) {
      return;
    }
    Optional<Trip> trip = asker.trip(e.deliveryId());
    if (trip.isEmpty()) {
      return;
    }
    try {
      switch (e.kind()) {
        case "WINDOW_AT_RISK" -> askToReceiveLate(trip.get(), now, envelope);
        case "FAILED_STOP" -> askForDockHelp(trip.get(), now, envelope);
        default -> { }
      }
    } catch (DomainException refused) {
      // A mall, a stop finished meanwhile, a time too far ahead: the rules say no, so nothing is asked.
      metrics.increment("waypoint.issue.decision_not_asked", "kind", e.kind().toLowerCase(java.util.Locale.ROOT));
    }
  }

  private void askToReceiveLate(Trip trip, Instant now, EventEnvelope<AttentionRaised> envelope) {
    RunSheetStopView stop = trip.stop();
    if (stop.mallOutlet() || decisions.everAsked(stop.deliveryId(), Kind.RECEIVE_LATE.code())) {
      return;
    }
    Optional<Instant> at = DecisionRules.autoReceiveAt(stop.expectedArrival(), now);
    if (at.isPresent()) {
      asker.ask(Actor.SYSTEM, Kind.RECEIVE_LATE, trip, at, Optional.empty(), now, envelope.eventId());
    }
  }

  private void askForDockHelp(Trip blocked, Instant now, EventEnvelope<AttentionRaised> envelope) {
    RunSheetStopView failed = blocked.stop();
    Optional<RunSheetStopView> helper = blocked.stops().stream()
        .filter(s -> s.tripId().equals(failed.tripId()) && s.sequence() > failed.sequence()
            && (s.outcome() == DeliveryOutcome.PENDING || s.outcome() == DeliveryOutcome.ARRIVED))
        .min(Comparator.comparingInt(RunSheetStopView::sequence));
    if (helper.isEmpty() || decisions.everAsked(helper.get().deliveryId(), Kind.DOCK_HELP.code())) {
      return;
    }
    Trip asked = new Trip(blocked.record(), blocked.stops(), helper.get());
    asker.ask(Actor.SYSTEM, Kind.DOCK_HELP, asked, Optional.empty(), Optional.of(failed), now, envelope.eventId());
  }
}
