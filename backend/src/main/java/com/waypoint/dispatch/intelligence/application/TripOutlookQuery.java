package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetStopView;
import com.waypoint.dispatch.intelligence.contract.PlaybookViews.ChanceView;
import com.waypoint.dispatch.intelligence.contract.PlaybookViews.SignalView;
import com.waypoint.dispatch.intelligence.contract.PlaybookViews.StopOutlookView;
import com.waypoint.dispatch.intelligence.contract.PlaybookViews.TripOutlookView;
import com.waypoint.dispatch.intelligence.domain.Playbooks;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Facts;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Situation;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Window;
import com.waypoint.dispatch.intelligence.domain.TripOutlook;
import com.waypoint.dispatch.intelligence.domain.TripOutlook.Signal;
import com.waypoint.dispatch.intelligence.domain.TripOutlook.Source;
import com.waypoint.dispatch.intelligence.domain.TripOutlook.Stop;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcDecisionSuggestions;
import com.waypoint.dispatch.messaging.contract.TripReportQuery;
import com.waypoint.dispatch.messaging.contract.TripReportQuery.TripReports;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * One trip's decision outlook (decision suggestions part 2, R-ML-34). The
 * signals are the trip's reports not yet resolved, from Messaging's contract;
 * the facts come from Execution's run sheet and Ordering, as
 * {@link PlaybookQuery} reads them for one stop; the chances are
 * {@link TripOutlook}'s. A trip outside the dispatcher's depots is refused,
 * never answered with nothing.
 */
@Component
public class TripOutlookQuery {
  private final TripReportQuery reports;
  private final ExecutionQuery execution;
  private final OrderQuery orders;
  private final Database database;
  private final Clock clock;
  private final JdbcDecisionSuggestions log;
  private final com.waypoint.dispatch.intelligence.infrastructure.JdbcMessageSituations read;
  private final com.waypoint.dispatch.platform.config.AiProperties ai;
  private final com.waypoint.dispatch.issues.contract.IssueQuery issues;

  public TripOutlookQuery(
      TripReportQuery reports, ExecutionQuery execution, OrderQuery orders, Database database, Clock clock,
      JdbcDecisionSuggestions log, com.waypoint.dispatch.intelligence.infrastructure.JdbcMessageSituations read,
      com.waypoint.dispatch.platform.config.AiProperties ai, com.waypoint.dispatch.issues.contract.IssueQuery issues) {
    this.issues = issues;
    this.read = read;
    this.ai = ai;
    this.reports = reports;
    this.execution = execution;
    this.orders = orders;
    this.database = database;
    this.clock = clock;
    this.log = log;
  }

  public TripOutlookView forTrip(Actor actor, String tripId) {
    return forTrip(actor, tripId, Optional.empty());
  }

  /** @param extraMinutes the extra time the dispatcher picked for the room's ask, on a breakdown or a blocked road */
  public TripOutlookView forTrip(Actor actor, String tripId, Optional<Integer> extraMinutes) {
    // One read as the dispatcher, so every contract read below sees them as the ambient actor and
    // row-level security answers for their depots only.
    return database.readAs(ModuleRole.ML, actor.userId(), () -> outlook(tripId, extraMinutes));
  }

  private TripOutlookView outlook(String tripId, Optional<Integer> extraMinutes) {
    TripReports trip = reports.openReports(tripId).orElseThrow(() -> notYours(tripId));
    boolean inScope =
        Boolean.TRUE.equals(database.queryOne("SELECT app.actor_has_depot(?) AS ok", trip.depotCode()).get("ok"));
    if (!inScope) {
      throw notYours(tripId);
    }
    Instant now = clock.now();
    List<RunSheetStopView> onTrip = trip.vehicleId().isEmpty() || trip.serviceDate().isEmpty()
        ? List.of()
        : execution.runSheet(trip.vehicleId().get(), trip.serviceDate().get())
            .map(sheet -> sheet.stops().stream().filter(s -> s.tripId().toString().equals(tripId)).toList())
            .orElse(List.of());
    int left = (int) onTrip.stream().filter(TripOutlookQuery::open).count();
    List<Stop> stops = new ArrayList<>();
    Map<String, RunSheetStopView> byDelivery = new HashMap<>();
    for (RunSheetStopView s : onTrip) {
      byDelivery.put(s.deliveryId().toString(), s);
      Optional<OrderView> order = orders.order(s.orderId());
      // Where the window will stand when the van gets there, not where it stands now.
      Instant arrives = s.expectedArrival().filter(a -> a.isAfter(now)).orElse(now);
      Window window = Window.at(
          Optional.of(trip.serviceDate().orElseThrow().atTime(s.windowClose()).atZone(Clock.OPERATING_ZONE).toInstant()),
          arrives);
      stops.add(new Stop(s.deliveryId(), s.outletId(), s.sequence(), new Facts(
          open(s), s.outcome() == DeliveryOutcome.ARRIVED, window, s.mallOutlet(),
          order.map(o -> "chilled".equals(o.temperature()) || "frozen".equals(o.temperature())).orElse(false),
          order.map(o -> o.deferralCount() > 0).orElse(false), left)));
    }
    boolean noneInWindow = stops.stream().filter(s -> s.facts().stopOpen()).allMatch(s -> s.facts().window() == Window.MISSED);
    List<Signal> signals = new ArrayList<>();
    for (TripReportQuery.OpenReport r : trip.reports()) {
      Playbooks.ofReport(r.reportType())
          .ifPresent(s -> signals.add(new Signal(r.messageId(), s, r.aboutOutletId(), r.at(), Source.REPORT)));
    }
    // R-ML-35: what a driver or a store wrote, read by Jev at or above the live confidence; nothing while it is off.
    Map<java.util.UUID, String> authors = new HashMap<>();
    int unread = ai.jevLive() ? read.unread(tripId, now.minusSeconds(60)) : 0;
    if (ai.jevLive()) {
      for (var m : read.forTrip(tripId)) {
        com.waypoint.dispatch.intelligence.domain.MessageTriage.signal(m.situation(), m.confidence(), ai.jevLiveMinConfidence())
            .ifPresent(s -> {
              signals.add(new Signal(m.messageId(), s, m.outletId(), m.at(), Source.MESSAGE));
              authors.put(m.messageId(), m.authorRole());
            });
      }
    }
    Map<Situation, Playbooks.Tally> tallies = new HashMap<>();
    TripOutlook.Outlook o = TripOutlook.of(
        signals, new TripOutlook.Trip(left, noneInWindow), stops,
        s -> tallies.computeIfAbsent(s, x -> PlaybookQuery.tally(log.tally(x.name(), now.minus(PlaybookQuery.COUNTED)))));
    return new TripOutlookView(
        tripId,
        TripOutlook.VERSION,
        o.tripSituation().map(Situation::name),
        o.trip().stream().map(c -> new ChanceView(c.action().name(), c.chance(), c.warning())).toList(),
        signals.stream()
            .map(s -> new SignalView(s.messageId().toString(), s.situation().name(), s.source().name().toLowerCase(java.util.Locale.ROOT),
                s.outletId(), Optional.ofNullable(authors.get(s.messageId()))))
            .toList(),
        o.stops().stream()
            .map(s -> new StopOutlookView(
                s.deliveryId().toString(), s.outletId(), byDelivery.get(s.deliveryId().toString()).sequence(),
                byDelivery.get(s.deliveryId().toString()).rowVersion(), s.situation().name(),
                s.chances().stream().map(c -> new ChanceView(c.action().name(), c.chance(), c.warning())).toList(),
                s.learned(), s.because().stream().map(Object::toString).toList()))
            .toList(),
        ai.jevLive(),
        unread,
        room(tripId, trip, onTrip, signals, now, extraMinutes));
  }

  /**
   * R-ML-36: the problem the room is about, the most serious on the road first (a
   * breakdown, a blocked road, running late), else a store found closed or unable
   * to unload; a goods problem waits for the day's end and opens none.
   */
  private Optional<com.waypoint.dispatch.intelligence.contract.PlaybookViews.RoomView> room(
      String tripId, TripReports trip, List<RunSheetStopView> onTrip, List<Signal> signals, Instant now,
      Optional<Integer> extraMinutes) {
    if (onTrip.isEmpty() || trip.serviceDate().isEmpty()) {
      return Optional.empty();
    }
    java.util.Comparator<Signal> first = java.util.Comparator.comparing(Signal::at);
    Optional<Signal> problem = java.util.stream.Stream.of(Situation.BREAKDOWN, Situation.ROAD_BLOCKED, Situation.RUNNING_LATE)
        .flatMap(s -> signals.stream().filter(x -> x.situation() == s).sorted(first).limit(1))
        .findFirst()
        .or(() -> signals.stream()
            .filter(x -> x.situation() == Situation.STORE_CLOSED || x.situation() == Situation.CANNOT_UNLOAD)
            .min(first));
    if (problem.isEmpty()) {
      return Optional.empty();
    }
    java.time.LocalDate day = trip.serviceDate().get();
    List<com.waypoint.dispatch.intelligence.domain.ProblemRoom.Stop> stops = onTrip.stream()
        .map(s -> new com.waypoint.dispatch.intelligence.domain.ProblemRoom.Stop(
            s.deliveryId(), s.outletId(), s.sequence(), outcome(s.outcome()), s.mallOutlet(), false,
            day.atTime(s.windowClose()).atZone(Clock.OPERATING_ZONE).toInstant(),
            // The van is not there yet: an arrival already past stands at now.
            max(s.expectedArrival().orElse(day.atTime(s.plannedArrival()).atZone(Clock.OPERATING_ZONE).toInstant()), now),
            s.rowVersion()))
        .toList();
    List<com.waypoint.dispatch.intelligence.domain.ProblemRoom.Ask> asks =
        issues.decisionsOfTrip(java.util.UUID.fromString(tripId), day).stream()
            .map(d -> new com.waypoint.dispatch.intelligence.domain.ProblemRoom.Ask(
                d.requestId(), d.kind(), d.deliveryId(), d.aboutDeliveryId(), d.receiveAt(), d.deadline(), d.state(),
                d.answer(), d.askedAt()))
            .toList();
    var r = com.waypoint.dispatch.intelligence.domain.ProblemRoom.of(
        new com.waypoint.dispatch.intelligence.domain.ProblemRoom.Problem(
            problem.get().situation(), problem.get().outletId(), problem.get().at()),
        stops, asks, now, extraMinutes.map(java.time.Duration::ofMinutes));
    var n = r.next();
    return Optional.of(new com.waypoint.dispatch.intelligence.contract.PlaybookViews.RoomView(
        r.situation().name(),
        r.rows().stream().map(x -> new com.waypoint.dispatch.intelligence.contract.PlaybookViews.RowView(
            x.deliveryId().toString(), x.outletId(), x.sequence(), x.state().name(), x.at(), x.because())).toList(),
        new com.waypoint.dispatch.intelligence.contract.PlaybookViews.StepView(
            n.kind().name(), n.stops().stream().map(TripOutlookQuery::target).toList(), n.about().map(TripOutlookQuery::target),
            n.why(), n.extra().map(d -> (int) d.toMinutes()),
            n.extra().isPresent()
                ? com.waypoint.dispatch.intelligence.domain.ProblemRoom.EXTRAS.stream().map(d -> (int) d.toMinutes()).toList()
                : List.of(),
            n.endTripToo())));
  }

  private static com.waypoint.dispatch.intelligence.contract.PlaybookViews.TargetView target(
      com.waypoint.dispatch.intelligence.domain.ProblemRoom.Target t) {
    return new com.waypoint.dispatch.intelligence.contract.PlaybookViews.TargetView(
        t.deliveryId().toString(), t.outletId(), t.receiveAt(), t.rowVersion());
  }

  private static com.waypoint.dispatch.intelligence.domain.ProblemRoom.Outcome outcome(DeliveryOutcome o) {
    return switch (o) {
      case PENDING -> com.waypoint.dispatch.intelligence.domain.ProblemRoom.Outcome.WAITING;
      case ARRIVED -> com.waypoint.dispatch.intelligence.domain.ProblemRoom.Outcome.REACHED;
      case DELIVERED -> com.waypoint.dispatch.intelligence.domain.ProblemRoom.Outcome.DELIVERED;
      case SKIPPED -> com.waypoint.dispatch.intelligence.domain.ProblemRoom.Outcome.SKIPPED;
      default -> com.waypoint.dispatch.intelligence.domain.ProblemRoom.Outcome.OTHER;
    };
  }

  private static Instant max(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  private static boolean open(RunSheetStopView s) {
    return s.outcome() == DeliveryOutcome.PENDING || s.outcome() == DeliveryOutcome.ARRIVED;
  }

  private static DomainException notYours(String tripId) {
    return new DomainException(ErrorCode.FORBIDDEN, "wpt:ml:trip:" + tripId + " is outside the actor's scope");
  }
}
