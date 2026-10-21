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

  public TripOutlookQuery(
      TripReportQuery reports, ExecutionQuery execution, OrderQuery orders, Database database, Clock clock,
      JdbcDecisionSuggestions log) {
    this.reports = reports;
    this.execution = execution;
    this.orders = orders;
    this.database = database;
    this.clock = clock;
    this.log = log;
  }

  public TripOutlookView forTrip(Actor actor, String tripId) {
    // One read as the dispatcher, so every contract read below sees them as the ambient actor and
    // row-level security answers for their depots only.
    return database.readAs(ModuleRole.ML, actor.userId(), () -> outlook(tripId));
  }

  private TripOutlookView outlook(String tripId) {
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
            .map(s -> new SignalView(s.messageId().toString(), s.situation().name(), s.source().name().toLowerCase(java.util.Locale.ROOT), s.outletId()))
            .toList(),
        o.stops().stream()
            .map(s -> new StopOutlookView(
                s.deliveryId().toString(), s.outletId(), byDelivery.get(s.deliveryId().toString()).sequence(),
                byDelivery.get(s.deliveryId().toString()).rowVersion(), s.situation().name(),
                s.chances().stream().map(c -> new ChanceView(c.action().name(), c.chance(), c.warning())).toList(),
                s.learned(), s.because().stream().map(Object::toString).toList()))
            .toList());
  }

  private static boolean open(RunSheetStopView s) {
    return s.outcome() == DeliveryOutcome.PENDING || s.outcome() == DeliveryOutcome.ARRIVED;
  }

  private static DomainException notYours(String tripId) {
    return new DomainException(ErrorCode.FORBIDDEN, "wpt:ml:trip:" + tripId + " is outside the actor's scope");
  }
}
