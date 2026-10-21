package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryRecordView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetStopView;
import com.waypoint.dispatch.intelligence.contract.PlaybookViews.MessageView;
import com.waypoint.dispatch.intelligence.contract.PlaybookViews.PlaybookView;
import com.waypoint.dispatch.intelligence.contract.PlaybookViews.SuggestionView;
import com.waypoint.dispatch.intelligence.contract.PlaybookViews.SuggestionsView;
import com.waypoint.dispatch.intelligence.domain.Playbooks;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Facts;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Situation;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Window;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The playbooks and the decisions to suggest for one stop (issue #269). The
 * facts come from the contracts of Execution (the stop and its trip), Ordering
 * (chilled, deferred before) and Reference (the window, a mall); the rules and
 * chances are {@link Playbooks}'. A stop outside the dispatcher's depots is
 * refused, never answered with nothing.
 */
@Component
public class PlaybookQuery {
  /** Reference's dock type of an outlet that takes goods only inside its mall's window (R-PLN-14). */
  private static final String MALL_DOCK = "mall_dock";

  private final ExecutionQuery execution;
  private final OrderQuery orders;
  private final ReferenceQuery reference;
  private final Database database;
  private final Clock clock;

  public PlaybookQuery(
      ExecutionQuery execution, OrderQuery orders, ReferenceQuery reference, Database database, Clock clock) {
    this.execution = execution;
    this.orders = orders;
    this.reference = reference;
    this.database = database;
    this.clock = clock;
  }

  public List<PlaybookView> catalogue() {
    return Playbooks.all().stream().map(PlaybookQuery::view).toList();
  }

  /** @param situation a situation's name, or a report type of the trip's thread */
  public SuggestionsView forStop(Actor actor, UUID deliveryId, String situation) {
    Situation s = Playbooks.ofReport(situation).orElseGet(() -> Situation.parse(situation));
    // One read as the dispatcher, so every contract read below sees them as the ambient actor and
    // row-level security answers for their depots only.
    return database.readAs(ModuleRole.ML, actor.userId(), () -> suggest(deliveryId, s));
  }

  private SuggestionsView suggest(UUID deliveryId, Situation s) {
    DeliveryRecordView stop = execution.deliveryRecord(deliveryId).orElseThrow(() -> notYours(deliveryId));
    OutletView outlet = reference.outlet(stop.outletId(), null).orElseThrow(() -> notYours(deliveryId));
    boolean inScope =
        Boolean.TRUE.equals(database.queryOne("SELECT app.actor_has_depot(?) AS ok", outlet.depotCode()).get("ok"));
    if (!inScope) {
      throw notYours(deliveryId);
    }
    Optional<OrderView> order = orders.order(stop.orderId());
    boolean open = stop.outcome() == DeliveryOutcome.PENDING || stop.outcome() == DeliveryOutcome.ARRIVED;
    LocalTime close = outlet.effectiveWindowClose().orElse(outlet.windowClose());
    Window window = Window.at(
        Optional.of(stop.serviceDate().atTime(close).atZone(Clock.OPERATING_ZONE).toInstant()), clock.now());
    int left = execution.runSheet(stop.vehicleId(), stop.serviceDate())
        .map(sheet -> (int) sheet.stops().stream()
            .filter(x -> x.tripId().equals(stop.tripId()))
            .map(RunSheetStopView::outcome)
            .filter(o -> o == DeliveryOutcome.PENDING || o == DeliveryOutcome.ARRIVED)
            .count())
        .orElse(open ? 1 : 0);
    Facts facts = new Facts(
        open, stop.outcome() == DeliveryOutcome.ARRIVED, window, MALL_DOCK.equals(outlet.dockType()),
        order.map(o -> "chilled".equals(o.temperature()) || "frozen".equals(o.temperature())).orElse(false),
        order.map(o -> o.deferralCount() > 0).orElse(false), left);
    List<SuggestionView> suggestions = Playbooks.suggest(s, facts).stream()
        .map(x -> new SuggestionView(x.action().name(), x.chance(), x.warning()))
        .toList();
    return new SuggestionsView(s.name(), deliveryId.toString(), "rules", Playbooks.VERSION, suggestions,
        view(Playbooks.playbook(s)));
  }

  private static PlaybookView view(Playbooks.Playbook p) {
    return new PlaybookView(p.situation().name(), p.steps(), p.message().map(m -> new MessageView(m.to(), m.template())));
  }

  private static DomainException notYours(UUID deliveryId) {
    return new DomainException(ErrorCode.FORBIDDEN, "wpt:ml:delivery:" + deliveryId + " is outside the actor's scope");
  }
}
