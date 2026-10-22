package com.waypoint.dispatch.issues.application;

import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryRecordView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetStopView;
import com.waypoint.dispatch.issues.contract.IssueCommands;
import com.waypoint.dispatch.issues.contract.IssueEvents.DecisionAnswered;
import com.waypoint.dispatch.issues.contract.IssueEvents.DecisionRequested;
import com.waypoint.dispatch.issues.contract.IssueViews.DecisionView;
import com.waypoint.dispatch.issues.domain.DecisionRules;
import com.waypoint.dispatch.issues.domain.DecisionRules.Answer;
import com.waypoint.dispatch.issues.domain.DecisionRules.Kind;
import com.waypoint.dispatch.issues.domain.DecisionRules.State;
import com.waypoint.dispatch.issues.domain.DecisionRules.Stop;
import com.waypoint.dispatch.issues.domain.DecisionRules.Via;
import com.waypoint.dispatch.issues.infrastructure.JdbcDecisionRequests;
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
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Live decisions (roadmap FF-09): the dispatcher asks one store one question on
 * a live trip, and the store answers with a tap. Every name and number is read
 * from the run sheet here, never taken from the caller. A decision carries who,
 * when and why (rule 8); an answer changes the question at the version the
 * caller saw (rule 6).
 */
final class DecisionHandlers {
  private DecisionHandlers() {}

  private static boolean finished(DeliveryOutcome outcome) {
    return outcome != DeliveryOutcome.PENDING && outcome != DeliveryOutcome.ARRIVED;
  }

  private static Stop stop(RunSheetStopView s) {
    return new Stop(s.outletId(), s.mallOutlet(), finished(s.outcome()), s.expectedArrival());
  }

  private static int units(RunSheetStopView s) {
    int lines = s.lines().stream().mapToInt(l -> l.orderedUnits()).sum();
    return lines > 0 ? lines : s.itemCount();
  }

  /**
   * Asks one store one question (R-ISS-12, R-ISS-13): the one definition, used by
   * the dispatcher's command and by the system when the attention watch finds a
   * stop at risk. Every name and number is read from the run sheet.
   */
  @Component
  static class DecisionAsker {
    private final JdbcDecisionRequests decisions;
    private final ExecutionQuery execution;
    private final ReferenceQuery reference;
    private final EventPublisher events;
    private final Metrics metrics;
    private final SecureRandom random = new SecureRandom();

    DecisionAsker(
        JdbcDecisionRequests decisions, ExecutionQuery execution, ReferenceQuery reference, EventPublisher events,
        Metrics metrics) {
      this.decisions = decisions;
      this.execution = execution;
      this.reference = reference;
      this.events = events;
      this.metrics = metrics;
    }

    /** The stops of the vehicle the delivery is on, and that delivery's stop; empty when the asker cannot see it. */
    Optional<Trip> trip(UUID deliveryId) {
      return execution.deliveryRecord(deliveryId).flatMap(record -> {
        List<RunSheetStopView> stops =
            execution.runSheet(record.vehicleId(), record.serviceDate()).map(r -> r.stops()).orElse(List.of());
        return find(stops, deliveryId).map(stop -> new Trip(record, stops, stop));
      });
    }

    String depotOf(String outletId) {
      return reference.outlet(outletId, null).map(o -> o.depotCode()).orElseThrow();
    }

    /** Asks, replacing any open question of the kind about the stop. */
    Map<String, Object> ask(
        Actor actor, Kind kind, Trip trip, Optional<Instant> receiveAt, Optional<RunSheetStopView> about, Instant now,
        UUID sourceId) {
      RunSheetStopView asked = trip.stop();
      String depot = depotOf(asked.outletId());
      switch (kind) {
        case RECEIVE_LATE -> DecisionRules.receiveLate(stop(asked), receiveAt.orElseThrow(), now);
        case DOCK_HELP -> {
          RunSheetStopView blocked = about.orElseThrow();
          DecisionRules.dockHelp(
              stop(asked), asked.sequence(), stop(blocked), blocked.sequence(), blocked.tripId().equals(asked.tripId()));
        }
      }
      Instant deadline = DecisionRules.deadline(now, asked.expectedArrival());
      Optional<String> aboutOutlet = about.map(RunSheetStopView::outletId);
      Optional<Integer> aboutUnits = about.map(DecisionHandlers::units);
      decisions.withdrawOpen(asked.deliveryId(), kind.code(), now);
      UUID requestId = UuidV7.generate(now, random);
      decisions.insert(
          requestId, kind.code(), depot, trip.record().serviceDate(), asked.tripId(), trip.record().vehicleId(),
          asked.deliveryId(), asked.outletId(), about.map(RunSheetStopView::deliveryId), aboutOutlet, aboutUnits,
          receiveAt, asked.expectedArrival(), deadline, actor.userId(), now, sourceId);
      String question = DecisionRules.question(kind, receiveAt, aboutOutlet, aboutUnits, Clock.OPERATING_ZONE);
      events.publish(
          actor,
          new DecisionRequested(
              requestId, kind.code(), depot, trip.record().serviceDate(), asked.tripId(), trip.record().vehicleId(),
              asked.deliveryId(), asked.outletId(), aboutOutlet, receiveAt, deadline, question));
      metrics.increment("waypoint.issue.decision_asked", "kind", kind.code(),
          "by", Actor.SYSTEM_ID.equals(actor.userId()) ? "system" : "dispatcher");
      return Map.of("requestId", requestId.toString(), "deadline", deadline.toString(), "question", question);
    }

    static Optional<RunSheetStopView> find(List<RunSheetStopView> stops, UUID deliveryId) {
      return stops.stream().filter(s -> s.deliveryId().equals(deliveryId)).findFirst();
    }
  }

  record Trip(DeliveryRecordView record, List<RunSheetStopView> stops, RunSheetStopView stop) {}

  /** {@code issue:AskDecision}: the dispatcher who oversees the depot asks (R-ISS-12). */
  @Component
  static class AskDecisionHandler implements CommandHandler {
    private final DecisionAsker asker;
    private final JdbcDecisionRequests decisions;
    private final Clock clock;

    AskDecisionHandler(DecisionAsker asker, JdbcDecisionRequests decisions, Clock clock) {
      this.asker = asker;
      this.decisions = decisions;
      this.clock = clock;
    }

    @Override
    public String kind() {
      return IssueCommands.ASK_DECISION;
    }

    @Override
    public String action() {
      return IssueCommands.ASK_DECISION;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.ISSUES;
    }

    @Override
    public String resource(Command command) {
      String delivery = CommandPayload.of(command).text("deliveryId");
      return delivery == null ? null : "wpt:issue:delivery:" + delivery;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      CommandPayload p = CommandPayload.of(command);
      Kind kind = Kind.parse(p.requiredText("kind"));
      UUID deliveryId = p.uuid("deliveryId");
      Instant now = clock.now();
      Trip trip = asker.trip(deliveryId).orElseThrow(() -> unknown(deliveryId));
      String depot = asker.depotOf(trip.stop().outletId());
      if (!decisions.oversees(depot)) {
        throw new DomainException(
            ErrorCode.FORBIDDEN, "depot " + depot + " is outside the actor's scope", List.of("R-ISS-12"));
      }
      Optional<Instant> receiveAt = Optional.empty();
      Optional<RunSheetStopView> about = Optional.empty();
      if (kind == Kind.RECEIVE_LATE) {
        receiveAt = Optional.of(Instant.parse(p.requiredText("receiveAt")));
      } else {
        UUID aboutId = p.uuid("aboutDeliveryId");
        about = Optional.of(DecisionAsker.find(trip.stops(), aboutId).orElseThrow(() -> unknown(aboutId)));
      }
      return asker.ask(actor, kind, trip, receiveAt, about, now, command.commandId());
    }
  }

  /** {@code issue:AnswerDecision}: R-ISS-14. */
  @Component
  static class AnswerDecisionHandler implements CommandHandler {
    private final JdbcDecisionRequests decisions;
    private final EventPublisher events;
    private final Metrics metrics;
    private final Clock clock;

    AnswerDecisionHandler(JdbcDecisionRequests decisions, EventPublisher events, Metrics metrics, Clock clock) {
      this.decisions = decisions;
      this.events = events;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String kind() {
      return IssueCommands.ANSWER_DECISION;
    }

    @Override
    public String action() {
      return IssueCommands.ANSWER_DECISION;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.ISSUES;
    }

    @Override
    public String resource(Command command) {
      String request = CommandPayload.of(command).text("requestId");
      return request == null ? null : "wpt:issue:decision:" + request;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      CommandPayload p = CommandPayload.of(command);
      UUID requestId = p.uuid("requestId");
      Answer answer = Answer.parse(p.requiredText("answer"));
      Optional<String> reason = DecisionRules.reason(p.text("reason"));
      Optional<UUID> voiceNoteId = Optional.ofNullable(p.optionalUuid("voiceNoteId"));
      boolean byPhone = "true".equalsIgnoreCase(String.valueOf(p.text("byPhone")));
      // R-ML-45: what the store wrote in the trip's chat, recorded by the dispatcher with the words as the reason.
      boolean inChat = "true".equalsIgnoreCase(String.valueOf(p.text("inChat")));
      if (byPhone && inChat) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "an answer is given by phone or in the chat, not both");
      }
      if (inChat && reason.isEmpty()) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "an answer from the chat carries the store's words as its reason");
      }
      if (command.expectedVersion() == null) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required");
      }

      DecisionView request = decisions.find(requestId).orElseThrow(() -> outside(requestId));
      // The store answers its own question; the dispatcher only records a phone answer.
      Via via;
      if (byPhone || inChat) {
        if (!decisions.oversees(request.depotCode())) throw outside(requestId);
        via = byPhone ? Via.PHONE : Via.CHAT;
      } else {
        if (!decisions.answersFor(request.outletId())) throw outside(requestId);
        via = Via.APP;
      }
      Instant now = clock.now();
      boolean late = DecisionRules.answerIsLate(State.of(request.state()), request.deadline(), now);
      int changed =
          decisions.answer(
              requestId, command.expectedVersion(), answer.code(), via.code(), actor.userId(), now, late, reason,
              voiceNoteId);
      if (changed == 0) {
        throw new DomainException(
            ErrorCode.VERSION_CONFLICT, "the question changed since it was read (version " + request.rowVersion() + ")");
      }
      Kind kind = Kind.parse(request.kind());
      String question =
          DecisionRules.question(kind, request.receiveAt(), request.aboutOutletId(), request.aboutUnits(),
              Clock.OPERATING_ZONE);
      events.publish(
          actor,
          new DecisionAnswered(
              requestId, request.kind(), request.depotCode(), request.serviceDate(), request.tripId(),
              request.vehicleId(), request.deliveryId(), request.outletId(), request.aboutDeliveryId(),
              request.aboutOutletId(), request.receiveAt(), answer.code(), via.code(), late, reason, now, question,
              DecisionRules.driverNote(kind, request.outletId(), request.receiveAt(), request.aboutOutletId(),
                  Clock.OPERATING_ZONE)));
      metrics.increment("waypoint.issue.decision_answered", "kind", request.kind(), "answer", answer.code(),
          "via", via.code(), "late", String.valueOf(late));
      return Map.of(
          "requestId", requestId.toString(), "answer", answer.code(), "late", late,
          "rowVersion", command.expectedVersion() + 1);
    }

    private static DomainException outside(UUID requestId) {
      return new DomainException(
          ErrorCode.FORBIDDEN, "question " + requestId + " is not one you may answer", List.of("R-ISS-14"));
    }
  }

  /** A stop the actor cannot see is refused, never "not found" (deny by default): 403 plus an audit row. */
  private static DomainException unknown(UUID deliveryId) {
    return new DomainException(
        ErrorCode.FORBIDDEN, "stop " + deliveryId + " is not one you may ask about", List.of("R-ISS-12"));
  }
}
