package com.waypoint.dispatch.notification.application;

import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryCompleted;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryFailed;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryStarted;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.EtaChanged;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.RoadDisruptionReported;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.VehicleFaultReported;
import com.waypoint.dispatch.issues.contract.IssueEvents.IssueEscalated;
import com.waypoint.dispatch.issues.contract.IssueEvents.IssueRaised;
import com.waypoint.dispatch.messaging.contract.MessagingEvents.MessagePosted;
import com.waypoint.dispatch.loading.contract.LoadingEvents.LoadingShortfall;
import com.waypoint.dispatch.loading.contract.LoadingEvents.ReleasedStop;
import com.waypoint.dispatch.loading.contract.LoadingEvents.TripReleased;
import com.waypoint.dispatch.notification.domain.RoutedEvent;
import com.waypoint.dispatch.notification.domain.RoutedEvent.Subject;
import com.waypoint.dispatch.notification.domain.RoutedEvent.Target;
import com.waypoint.dispatch.notification.domain.ScopeKind;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrderAutoDeferred;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.planning.contract.PlanEvents.OrderDeferred;
import com.waypoint.dispatch.planning.contract.PlanEvents.OrderUnservable;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanRevised;
import com.waypoint.dispatch.planning.contract.PlanEvents.StoreContacted;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedTrip;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptDisputed;
import com.waypoint.dispatch.referencedata.contract.ReferenceEvents.VehicleStatusChanged;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.warehouse.contract.WarehouseEvents.WarehouseOrderStatusChanged;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The events a person needs to hear about, each turned into a {@link RoutedEvent}:
 * its facts for the templates, and every outlet, depot or vehicle it concerns.
 * Who hears about it is the routing table's decision, not these classes': they
 * name every scope the event knows, and a rule picks the ones it routes to.
 *
 * <p>Where an event lacks a scope a rule needs, it is looked up through the
 * owning module's contract: an order's outlet from Ordering, an outlet's or a
 * vehicle's depot from Reference.
 *
 * <p>Not consumed, because the person acted or nobody needs to: order placed,
 * amended or cancelled, loading started, receipt confirmed or auto-closed, issue
 * resolved, a reference version or a calendar override.
 */
final class NotificationConsumers {
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

  private NotificationConsumers() {}

  /** Runs as {@code waypoint_notification} for the system actor. */
  abstract static class NotificationConsumer<E extends DomainEvent> implements EventSubscriber<E> {
    protected final Notifier notifier;

    NotificationConsumer(Notifier notifier) {
      this.notifier = notifier;
    }

    @Override
    public final ModuleRole moduleRole() {
      return ModuleRole.NOTIFICATION;
    }

    @Override
    public final void on(EventEnvelope<E> envelope) {
      Routed r = route(envelope.payload());
      notifier.notify(
          new RoutedEvent(
              envelope.eventId(), envelope.payload().type(), envelope.actorId(), r.serviceDate, r.facts, r.targets));
    }

    abstract Routed route(E event);
  }

  /** The event-specific half of a {@link RoutedEvent}. */
  static final class Routed {
    final Map<String, String> facts = new HashMap<>();
    final List<Target> targets = new ArrayList<>();
    Optional<LocalDate> serviceDate = Optional.empty();

    Routed fact(String name, Object value) {
      if (value instanceof Optional<?> o) {
        value = o.orElse(null);
      }
      if (value != null) {
        facts.put(name, String.valueOf(value));
      }
      return this;
    }

    Routed on(LocalDate date) {
      serviceDate = Optional.ofNullable(date);
      return this;
    }

    Routed to(ScopeKind scope, Optional<String> scopeId, String subjectType, Object subjectId) {
      scopeId.ifPresent(id -> targets.add(Target.of(scope, id, subject(subjectType, subjectId))));
      return this;
    }

    Routed to(ScopeKind scope, String scopeId, String subjectType, Object subjectId) {
      return to(scope, Optional.ofNullable(scopeId), subjectType, subjectId);
    }

    Routed to(Target target) {
      targets.add(target);
      return this;
    }
  }

  private static Optional<Subject> subject(String type, Object id) {
    return id == null ? Optional.empty() : Optional.of(new Subject(type, String.valueOf(id)));
  }

  /** {@code VEHICLE_FAULT} reads as "vehicle fault". */
  private static String words(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private static String time(Instant at) {
    return CLOCK.format(at.atZone(Clock.OPERATING_ZONE));
  }

  private static String time(LocalTime at) {
    return at == null ? null : CLOCK.format(at);
  }

  /** Lookups for events that do not carry the scope a rule routes by. */
  @Component
  static class Scopes {
    private final OrderQuery orders;
    private final ReferenceQuery reference;

    Scopes(OrderQuery orders, ReferenceQuery reference) {
      this.orders = orders;
      this.reference = reference;
    }

    Optional<OrderView> order(UUID orderId) {
      return orders.order(orderId);
    }

    Optional<String> depotOfOutlet(String outletId) {
      return reference.outlet(outletId, null).map(OutletView::depotCode);
    }

    Optional<String> depotOfVehicle(String vehicleId) {
      return reference.vehicle(vehicleId, null).map(VehicleView::depotCode);
    }
  }

  // ---- Ordering and Planning ----------------------------------------------------

  /** R-RCP-03, R-NOT-04: the store hears that its order moved, with the binding reason. */
  @Component
  static class OnOrderDeferred extends NotificationConsumer<OrderDeferred> {
    OnOrderDeferred(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-order-deferred";
    }

    @Override
    public Class<OrderDeferred> eventType() {
      return OrderDeferred.class;
    }

    @Override
    Routed route(OrderDeferred e) {
      return new Routed()
          .fact("serviceDate", e.serviceDate()).fact("reason", e.reason()).fact("ruleId", e.ruleId())
          .fact("skipCount", e.skipCount()).fact("outletId", e.outletId())
          .to(ScopeKind.OUTLET, e.outletId(), "order", e.orderId());
    }
  }

  /** STK-03: the warehouse never confirmed stock before the cutoff. */
  @Component
  static class OnOrderAutoDeferred extends NotificationConsumer<OrderAutoDeferred> {
    OnOrderAutoDeferred(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-order-auto-deferred";
    }

    @Override
    public Class<OrderAutoDeferred> eventType() {
      return OrderAutoDeferred.class;
    }

    @Override
    Routed route(OrderAutoDeferred e) {
      return new Routed()
          .fact("fromDate", e.fromDate()).fact("toDate", e.toDate()).fact("reason", e.reason())
          .fact("outletId", e.outletId()).fact("depotCode", e.depotCode())
          .to(ScopeKind.OUTLET, e.outletId(), "order", e.orderId())
          .to(ScopeKind.DEPOT, e.depotCode(), "order", e.orderId());
    }
  }

  /** No vehicle can take the order: the store and its dispatcher both need to decide. */
  @Component
  static class OnOrderUnservable extends NotificationConsumer<OrderUnservable> {
    private final Scopes scopes;

    OnOrderUnservable(Notifier notifier, Scopes scopes) {
      super(notifier);
      this.scopes = scopes;
    }

    @Override
    public String consumerName() {
      return "notification.on-order-unservable";
    }

    @Override
    public Class<OrderUnservable> eventType() {
      return OrderUnservable.class;
    }

    @Override
    Routed route(OrderUnservable e) {
      return new Routed()
          .fact("reason", e.reason()).fact("ruleId", e.ruleId()).fact("outletId", e.outletId())
          .to(ScopeKind.OUTLET, e.outletId(), "order", e.orderId())
          .to(ScopeKind.DEPOT, scopes.depotOfOutlet(e.outletId()), "order", e.orderId());
    }
  }

  /** R-NOT-01: a retried placement found stock short, or a partial reservation expired. */
  @Component
  static class OnWarehouseOrderStatusChanged extends NotificationConsumer<WarehouseOrderStatusChanged> {
    private final Scopes scopes;

    OnWarehouseOrderStatusChanged(Notifier notifier, Scopes scopes) {
      super(notifier);
      this.scopes = scopes;
    }

    @Override
    public String consumerName() {
      return "notification.on-warehouse-order-status-changed";
    }

    @Override
    public Class<WarehouseOrderStatusChanged> eventType() {
      return WarehouseOrderStatusChanged.class;
    }

    @Override
    Routed route(WarehouseOrderStatusChanged e) {
      Optional<OrderView> order = scopes.order(e.orderId());
      return new Routed()
          .fact("status", e.status()).fact("orderRef", order.map(OrderView::orderRef))
          .to(ScopeKind.OUTLET, order.map(OrderView::outletId), "order", e.orderId())
          .to(ScopeKind.DEPOT, order.map(OrderView::depotCode), "order", e.orderId());
    }
  }

  /** Work is available: the depot's loaders, and each trip's driver on the service date. */
  @Component
  static class OnPlanPublished extends NotificationConsumer<PlanPublished> {
    OnPlanPublished(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-plan-published";
    }

    @Override
    public Class<PlanPublished> eventType() {
      return PlanPublished.class;
    }

    @Override
    Routed route(PlanPublished e) {
      return plan(e.planId(), e.depotCode(), e.serviceDate(), e.planVersion(), e.trips(), null);
    }
  }

  /** A revision supersedes the plan the loaders and drivers were working from. */
  @Component
  static class OnPlanRevised extends NotificationConsumer<PlanRevised> {
    OnPlanRevised(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-plan-revised";
    }

    @Override
    public Class<PlanRevised> eventType() {
      return PlanRevised.class;
    }

    /**
     * Only what a revision changed is news (R-NOT-12): the drivers of the trips that differ, the
     * outlets reached on another trip or at another time, and the depot when any trip differs. An
     * event written before the revision recorded what changed tells every trip, as it always did.
     */
    @Override
    Routed route(PlanRevised e) {
      List<PlannedTrip> told =
          e.changedTripIds()
              .map(ids -> e.trips().stream().filter(t -> ids.contains(t.tripId())).toList())
              .orElse(e.trips());
      boolean depotHears = e.changedTripIds().isEmpty() || !told.isEmpty();
      Routed r = plan(e.planId(), e.depotCode(), e.serviceDate(), e.planVersion(), told, e.reason(), depotHears);
      e.affectedOutletIds()
          .ifPresent(
              outlets ->
                  outlets.forEach(
                      outlet -> r.to(Target.of(ScopeKind.OUTLET, outlet, subject("plan", e.planId())))));
      return r;
    }
  }

  /** One target per trip, so a vehicle driven twice in a plan is told about both trips. */
  private static Routed plan(
      UUID planId, String depotCode, LocalDate serviceDate, int planVersion, List<PlannedTrip> trips, String reason) {
    return plan(planId, depotCode, serviceDate, planVersion, trips, reason, true);
  }

  private static Routed plan(
      UUID planId,
      String depotCode,
      LocalDate serviceDate,
      int planVersion,
      List<PlannedTrip> trips,
      String reason,
      boolean depotHears) {
    Routed r =
        new Routed()
            .on(serviceDate)
            .fact("serviceDate", serviceDate).fact("planVersion", planVersion).fact("tripCount", trips.size())
            .fact("depotCode", depotCode).fact("reason", reason);
    if (depotHears) {
      r.to(ScopeKind.DEPOT, depotCode, "plan", planId);
    }
    for (PlannedTrip trip : trips) {
      r.to(
          new Target(
              ScopeKind.VEHICLE,
              trip.vehicleId(),
              "trip:" + trip.tripId(),
              Map.of(
                  "tripNumber", String.valueOf(trip.tripNumber()),
                  "vehicleId", trip.vehicleId(),
                  "plannedDeparture", trip.plannedDeparture() == null ? "-" : time(trip.plannedDeparture()),
                  "stopCount", String.valueOf(trip.stops().size())),
              subject("trip", trip.tripId())));
    }
    return r;
  }

  /** A dispatcher's own words to the store that owns an order the plan could not serve. */
  @Component
  static class OnStoreContacted extends NotificationConsumer<StoreContacted> {
    private final Scopes scopes;

    OnStoreContacted(Notifier notifier, Scopes scopes) {
      super(notifier);
      this.scopes = scopes;
    }

    @Override
    public String consumerName() {
      return "notification.on-store-contacted";
    }

    @Override
    public Class<StoreContacted> eventType() {
      return StoreContacted.class;
    }

    @Override
    Routed route(StoreContacted e) {
      Optional<OrderView> order = scopes.order(e.orderId());
      return new Routed()
          .on(e.serviceDate())
          .fact("message", e.message())
          .fact("orderRef", order.map(OrderView::orderRef))
          .fact("outletId", e.outletId())
          .to(ScopeKind.OUTLET, e.outletId(), "order", e.orderId());
    }
  }

  // ---- Loading -------------------------------------------------------------------

  /**
   * R-EXE-08: the driver's vehicle is ready. With no driver, LOD-05 tells the dispatcher. The
   * depot's other loaders hear the trip left (R-NOT-10; the one who released it is the actor and is
   * not told), and each outlet on it hears its stop number and expected arrival (R-NOT-11, R-RCP-02).
   */
  @Component
  static class OnTripReleased extends NotificationConsumer<TripReleased> {
    OnTripReleased(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-trip-released";
    }

    @Override
    public Class<TripReleased> eventType() {
      return TripReleased.class;
    }

    @Override
    Routed route(TripReleased e) {
      Routed r = new Routed()
          .on(e.serviceDate())
          .fact("serviceDate", e.serviceDate()).fact("vehicleId", e.vehicleId())
          .fact("stopCount", e.stops().size()).fact("depotCode", e.depotCode())
          .to(new Target(ScopeKind.VEHICLE, e.vehicleId(), "trip:" + e.tripId(), Map.of(), subject("trip", e.tripId())))
          .to(ScopeKind.DEPOT, e.depotCode(), "trip", e.tripId());
      // One target per stop, so an outlet visited twice is told about each visit.
      for (ReleasedStop stop : e.stops()) {
        if (stop.outletId() == null) {
          continue;
        }
        r.to(
            new Target(
                ScopeKind.OUTLET,
                stop.outletId(),
                "stop:" + stop.sequence() + ":" + stop.outletId(),
                Map.of(
                    "stopNumber", String.valueOf(stop.sequence()),
                    "plannedArrival", stop.plannedArrival() == null ? "-" : time(stop.plannedArrival())),
                subject("trip", e.tripId())));
      }
      return r;
    }
  }

  /** R-NOT-02: departure is blocked until the dispatcher records a replacement. */
  @Component
  static class OnLoadingShortfall extends NotificationConsumer<LoadingShortfall> {
    OnLoadingShortfall(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-loading-shortfall";
    }

    @Override
    public Class<LoadingShortfall> eventType() {
      return LoadingShortfall.class;
    }

    @Override
    Routed route(LoadingShortfall e) {
      return new Routed()
          .fact("kind", e.kind() == null ? null : words(e.kind())).fact("missingUnits", e.missingUnits())
          .fact("reason", e.reason()).fact("depotCode", e.depotCode())
          .to(ScopeKind.DEPOT, e.depotCode(), "trip", e.tripId());
    }
  }

  // ---- Execution -----------------------------------------------------------------

  @Component
  static class OnDeliveryStarted extends NotificationConsumer<DeliveryStarted> {
    OnDeliveryStarted(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-delivery-started";
    }

    @Override
    public Class<DeliveryStarted> eventType() {
      return DeliveryStarted.class;
    }

    @Override
    Routed route(DeliveryStarted e) {
      return new Routed().fact("outletId", e.outletId()).to(ScopeKind.OUTLET, e.outletId(), "delivery", e.deliveryId());
    }
  }

  /** Proof is ready for the store to review. */
  @Component
  static class OnDeliveryCompleted extends NotificationConsumer<DeliveryCompleted> {
    OnDeliveryCompleted(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-delivery-completed";
    }

    @Override
    public Class<DeliveryCompleted> eventType() {
      return DeliveryCompleted.class;
    }

    @Override
    Routed route(DeliveryCompleted e) {
      return new Routed()
          .fact("outcome", e.outcome() == null ? null : words(e.outcome())).fact("outletId", e.outletId())
          .fact("lateMinutes", e.lateMinutes())
          .to(ScopeKind.OUTLET, e.outletId(), "delivery", e.deliveryId());
    }
  }

  /** Requires a decision from the dispatcher, and tells the store it is not coming. */
  @Component
  static class OnDeliveryFailed extends NotificationConsumer<DeliveryFailed> {
    OnDeliveryFailed(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-delivery-failed";
    }

    @Override
    public Class<DeliveryFailed> eventType() {
      return DeliveryFailed.class;
    }

    @Override
    Routed route(DeliveryFailed e) {
      return new Routed()
          .fact("reason", e.reason()).fact("outletId", e.outletId()).fact("depotCode", e.depotCode())
          .to(ScopeKind.OUTLET, e.outletId(), "delivery", e.deliveryId())
          .to(ScopeKind.DEPOT, e.depotCode(), "delivery", e.deliveryId());
    }
  }

  /** R-RCP-02, R-EXE-15: the store schedules staff by it, and the dispatcher sees lateness. */
  @Component
  static class OnEtaChanged extends NotificationConsumer<EtaChanged> {
    private final Scopes scopes;

    OnEtaChanged(Notifier notifier, Scopes scopes) {
      super(notifier);
      this.scopes = scopes;
    }

    @Override
    public String consumerName() {
      return "notification.on-eta-changed";
    }

    @Override
    public Class<EtaChanged> eventType() {
      return EtaChanged.class;
    }

    @Override
    Routed route(EtaChanged e) {
      return new Routed()
          .fact("expectedArrival", e.expectedArrival() == null ? null : time(e.expectedArrival()))
          .fact("delayMinutes", e.delayMinutes()).fact("outletId", e.outletId())
          .to(ScopeKind.OUTLET, e.outletId(), "delivery", e.deliveryId())
          .to(ScopeKind.DEPOT, scopes.depotOfOutlet(e.outletId()), "delivery", e.deliveryId());
    }
  }

  @Component
  static class OnVehicleFaultReported extends NotificationConsumer<VehicleFaultReported> {
    OnVehicleFaultReported(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-vehicle-fault-reported";
    }

    @Override
    public Class<VehicleFaultReported> eventType() {
      return VehicleFaultReported.class;
    }

    @Override
    Routed route(VehicleFaultReported e) {
      return new Routed()
          .on(e.serviceDate())
          .fact("vehicleId", e.vehicleId()).fact("description", e.description())
          .fact("serviceDate", e.serviceDate()).fact("depotCode", e.depotCode())
          .to(ScopeKind.DEPOT, e.depotCode(), "vehicle", e.vehicleId());
    }
  }

  @Component
  static class OnRoadDisruptionReported extends NotificationConsumer<RoadDisruptionReported> {
    OnRoadDisruptionReported(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-road-disruption-reported";
    }

    @Override
    public Class<RoadDisruptionReported> eventType() {
      return RoadDisruptionReported.class;
    }

    @Override
    Routed route(RoadDisruptionReported e) {
      return new Routed()
          .fact("vehicleId", e.vehicleId()).fact("districtName", e.districtName())
          .fact("description", e.description()).fact("depotCode", e.depotCode())
          .to(ScopeKind.DEPOT, e.depotCode(), "vehicle", e.vehicleId());
    }
  }

  // ---- Receipt, Issues, Reference -----------------------------------------------------

  /** The store disagrees with what arrived. */
  @Component
  static class OnReceiptDisputed extends NotificationConsumer<ReceiptDisputed> {
    OnReceiptDisputed(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-receipt-disputed";
    }

    @Override
    public Class<ReceiptDisputed> eventType() {
      return ReceiptDisputed.class;
    }

    @Override
    Routed route(ReceiptDisputed e) {
      return new Routed()
          .fact("reason", e.reason()).fact("outletId", e.outletId()).fact("depotCode", e.depotCode())
          .to(ScopeKind.DEPOT, e.depotCode(), "receipt", e.receiptId())
          .to(ScopeKind.OUTLET, e.outletId(), "receipt", e.receiptId());
    }
  }

  /** R-NOT-03: a reported fault, delay, damage or access problem reaches the dispatcher. */
  @Component
  static class OnIssueRaised extends NotificationConsumer<IssueRaised> {
    OnIssueRaised(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-issue-raised";
    }

    @Override
    public Class<IssueRaised> eventType() {
      return IssueRaised.class;
    }

    @Override
    Routed route(IssueRaised e) {
      return new Routed()
          .fact("issueType", words(e.issueType())).fact("severity", words(e.severity()))
          .fact("depotCode", e.depotCode()).fact("outletId", e.outletId())
          .to(ScopeKind.DEPOT, e.depotCode(), "issue", e.issueId())
          .to(ScopeKind.OUTLET, e.outletId(), "issue", e.issueId());
    }
  }

  /**
   * R-NOT-14: a message on a thread (issue #136). Names the depot (the dispatcher,
   * who hears of every message, and the loaders), the vehicle (its driver on the
   * service date) and the outlets the message reaches; each routing rule fires
   * only for the audience the fact names. The author is not told (R-NOT-07).
   */
  @Component
  static class OnMessagePosted extends NotificationConsumer<MessagePosted> {
    OnMessagePosted(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-message-posted";
    }

    @Override
    public Class<MessagePosted> eventType() {
      return MessagePosted.class;
    }

    @Override
    Routed route(MessagePosted e) {
      String where = e.vehicleId().map(v -> " · " + v).orElse("");
      String heading =
          "report".equals(e.kind())
              ? roleWords(e.authorRole()) + " report" + where
              : e.authorName() + where;
      Routed r =
          new Routed()
              .fact("audience", e.audience()).fact("heading", heading).fact("excerpt", e.excerpt())
              .fact("vehicleId", e.vehicleId()).fact("depotCode", e.depotCode())
              .on(e.serviceDate().orElse(null))
              .to(ScopeKind.DEPOT, e.depotCode(), "thread", e.threadId());
      e.vehicleId().ifPresent(v -> r.to(ScopeKind.VEHICLE, v, "thread", e.threadId()));
      e.outletIds().forEach(o -> r.to(ScopeKind.OUTLET, o, "thread", e.threadId()));
      return r;
    }

    private static String roleWords(String role) {
      return switch (role) {
        case "loader" -> "Loader";
        case "driver" -> "Driver";
        case "store_manager" -> "Store";
        default -> "Dispatcher";
      };
    }
  }

  /** R-ISS-06: an issue waited unassigned past its severity's deadline. */
  @Component
  static class OnIssueEscalated extends NotificationConsumer<IssueEscalated> {
    OnIssueEscalated(Notifier notifier) {
      super(notifier);
    }

    @Override
    public String consumerName() {
      return "notification.on-issue-escalated";
    }

    @Override
    public Class<IssueEscalated> eventType() {
      return IssueEscalated.class;
    }

    @Override
    Routed route(IssueEscalated e) {
      return new Routed()
          .fact("issueType", words(e.issueType())).fact("severity", words(e.severity()))
          .fact("waitedMinutes", e.waitedMinutes()).fact("depotCode", e.depotCode())
          .to(ScopeKind.DEPOT, e.depotCode(), "issue", e.issueId())
          .to(ScopeKind.OUTLET, e.outletId(), "issue", e.issueId());
    }
  }

  /** Fleet availability changed. Reference does not publish this yet; the consumer is ready for it. */
  @Component
  static class OnVehicleStatusChanged extends NotificationConsumer<VehicleStatusChanged> {
    private final Scopes scopes;

    OnVehicleStatusChanged(Notifier notifier, Scopes scopes) {
      super(notifier);
      this.scopes = scopes;
    }

    @Override
    public String consumerName() {
      return "notification.on-vehicle-status-changed";
    }

    @Override
    public Class<VehicleStatusChanged> eventType() {
      return VehicleStatusChanged.class;
    }

    @Override
    Routed route(VehicleStatusChanged e) {
      return new Routed()
          .on(e.serviceDate())
          .fact("vehicleId", e.vehicleId()).fact("status", e.status() == null ? null : e.status().replace('_', ' '))
          .fact("serviceDate", e.serviceDate()).fact("reason", e.reason())
          .to(ScopeKind.DEPOT, scopes.depotOfVehicle(e.vehicleId()), "vehicle", e.vehicleId());
    }
  }
}
