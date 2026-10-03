package com.waypoint.dispatch.issues.application;

import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryFailed;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.RoadDisruptionReported;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.VehicleFaultReported;
import com.waypoint.dispatch.issues.contract.IssueEvents.IssueRaised;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueSeverity;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueStatus;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueType;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.issues.domain.Issue;
import com.waypoint.dispatch.issues.domain.SeverityPolicy;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueAttachments;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository;
import com.waypoint.dispatch.loading.contract.LoadingEvents.LoadingShortfall;
import com.waypoint.dispatch.loading.contract.LoadingQuery;
import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import com.waypoint.dispatch.loading.contract.LoadingViews.ItemView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ManifestLineView;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptConfirmed;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptDisputed;
import com.waypoint.dispatch.receipt.contract.ReceiptQuery;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptLineView;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import com.waypoint.dispatch.warehouse.contract.WarehouseEvents.WarehouseDiscrepancyFound;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Problems other modules report become issues with an owner and a lifecycle.
 * One issue per source event: its key is stored as {@code source_key}, so a
 * redelivered event raises nothing new. Severity is the policy's default for the
 * type (decision 8). Nothing here resolves anything: issues are decided by people.
 */
final class IssuesConsumers {
  private static final Logger log = LoggerFactory.getLogger(IssuesConsumers.class);

  private IssuesConsumers() {}

  /** Runs as {@code waypoint_issues} for the system actor. */
  abstract static class IssuesConsumer<E extends DomainEvent> implements EventSubscriber<E> {
    protected final Raiser raiser;

    IssuesConsumer(Raiser raiser) {
      this.raiser = raiser;
    }

    @Override
    public final ModuleRole moduleRole() {
      return ModuleRole.ISSUES;
    }
  }

  /**
   * What an event-raised issue is about, before the policy gives it a severity.
   *
   * @param photosOf the receipt whose photos belong to this issue, for a shortage investigation
   */
  record Raised(
      IssueType type,
      String depotCode,
      Optional<String> outletId,
      List<SubjectRef> subjects,
      String description,
      boolean investigation,
      String sourceKey,
      Optional<UUID> photosOf) {

    Raised(
        IssueType type,
        String depotCode,
        Optional<String> outletId,
        List<SubjectRef> subjects,
        String description,
        boolean investigation,
        String sourceKey) {
      this(type, depotCode, outletId, subjects, description, investigation, sourceKey, Optional.empty());
    }
  }

  /** Raises an issue from an event as the system actor, once per source key. */
  @Component
  static class Raiser {
    private final JdbcIssueRepository issues;
    private final JdbcIssueAttachments attachments;
    private final EventPublisher events;
    private final Metrics metrics;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    Raiser(JdbcIssueRepository issues, JdbcIssueAttachments attachments, EventPublisher events, Metrics metrics, Clock clock) {
      this.issues = issues;
      this.attachments = attachments;
      this.events = events;
      this.metrics = metrics;
      this.clock = clock;
    }

    void raise(Raised r, EventEnvelope<?> envelope) {
      if (issues.findBySourceKey(r.sourceKey()).isPresent()) {
        metrics.increment("waypoint.issue.event_stale");
        return;
      }
      Instant now = clock.now();
      SeverityPolicy policy =
          SeverityPolicy.from(issues.parameters(now.atZone(Clock.OPERATING_ZONE).toLocalDate()));
      IssueSeverity severity = policy.defaultFor(r.type());
      Issue issue =
          Issue.raise(
              UuidV7.generate(now, random), r.type(), severity, r.depotCode(), r.outletId(), r.subjects(),
              r.description(), r.investigation(), Optional.of(r.sourceKey()), Actor.SYSTEM_ID, now);
      if (!issues.insert(issue, now)) {
        metrics.increment("waypoint.issue.event_stale");
        return;
      }
      issues.record(
          issue.issueId(), Optional.empty(), IssueStatus.OPEN, "raised", "raised from " + envelope.type(),
          Actor.SYSTEM_ID, Optional.of(envelope.eventId()), now);
      // Photos the store took while counting this receipt; one that arrives later links itself.
      r.photosOf().ifPresent(receipt -> attachments.link(issue.issueId(), attachments.ofReceipt(receipt), now));
      events.publish(
          Actor.SYSTEM,
          new IssueRaised(issue.issueId(), r.type(), severity, r.depotCode(), r.outletId(), issue.subjects()));
      metrics.increment("waypoint.issue.raised", "type", r.type().name(), "severity", severity.name(), "by", "event");
    }
  }

  private static SubjectRef subject(String type, Object id) {
    return new SubjectRef(type, String.valueOf(id));
  }

  // ---- Loading ---------------------------------------------------------------

  /** A missing, damaged or short item at the dock (LOD-01). Resolved by a replacement. */
  @Component
  static class OnLoadingShortfall extends IssuesConsumer<LoadingShortfall> {
    private final OrderQuery orders;

    OnLoadingShortfall(Raiser raiser, OrderQuery orders) {
      super(raiser);
      this.orders = orders;
    }

    @Override
    public String consumerName() {
      return "issues.on-loading-shortfall";
    }

    @Override
    public Class<LoadingShortfall> eventType() {
      return LoadingShortfall.class;
    }

    @Override
    public void on(EventEnvelope<LoadingShortfall> envelope) {
      LoadingShortfall e = envelope.payload();
      raiser.raise(
          new Raised(
              IssueType.LOADING_SHORTFALL, e.depotCode(), orders.order(e.orderId()).map(OrderView::outletId),
              List.of(subject("shortfall", e.shortfallId()), subject("trip", e.tripId()), subject("order", e.orderId())),
              e.kind() + " at loading: " + e.missingUnits() + " units. " + e.reason(), false,
              "loading.shortfall:" + e.shortfallId()),
          envelope);
    }
  }

  // ---- Execution -------------------------------------------------------------

  /** The outlet was closed or refused the goods (EXE-07). The dispatcher chooses a redelivery or closure. */
  @Component
  static class OnDeliveryFailed extends IssuesConsumer<DeliveryFailed> {
    OnDeliveryFailed(Raiser raiser) {
      super(raiser);
    }

    @Override
    public String consumerName() {
      return "issues.on-delivery-failed";
    }

    @Override
    public Class<DeliveryFailed> eventType() {
      return DeliveryFailed.class;
    }

    @Override
    public void on(EventEnvelope<DeliveryFailed> envelope) {
      DeliveryFailed e = envelope.payload();
      raiser.raise(
          new Raised(
              IssueType.FAILED_DELIVERY, e.depotCode(), Optional.of(e.outletId()),
              List.of(subject("order", e.orderId()), subject("delivery", e.deliveryId()), subject("trip", e.tripId())),
              "delivery failed: " + e.reason(), false, "delivery.failed:" + e.deliveryId()),
          envelope);
    }
  }

  /** A vehicle fault on the road (EXE-08). The event names no trip, so the vehicle is the subject. */
  @Component
  static class OnVehicleFaultReported extends IssuesConsumer<VehicleFaultReported> {
    OnVehicleFaultReported(Raiser raiser) {
      super(raiser);
    }

    @Override
    public String consumerName() {
      return "issues.on-vehicle-fault-reported";
    }

    @Override
    public Class<VehicleFaultReported> eventType() {
      return VehicleFaultReported.class;
    }

    @Override
    public void on(EventEnvelope<VehicleFaultReported> envelope) {
      VehicleFaultReported e = envelope.payload();
      raiser.raise(
          new Raised(
              IssueType.VEHICLE_FAULT, e.depotCode(), Optional.empty(), List.of(subject("vehicle", e.vehicleId())),
              "vehicle fault on " + e.serviceDate() + ": " + e.description(), false,
              "vehicle.fault_reported:" + e.vehicleId() + ":" + e.at()),
          envelope);
    }
  }

  @Component
  static class OnRoadDisruptionReported extends IssuesConsumer<RoadDisruptionReported> {
    OnRoadDisruptionReported(Raiser raiser) {
      super(raiser);
    }

    @Override
    public String consumerName() {
      return "issues.on-road-disruption-reported";
    }

    @Override
    public Class<RoadDisruptionReported> eventType() {
      return RoadDisruptionReported.class;
    }

    @Override
    public void on(EventEnvelope<RoadDisruptionReported> envelope) {
      RoadDisruptionReported e = envelope.payload();
      raiser.raise(
          new Raised(
              IssueType.ROAD_DISRUPTION, e.depotCode(), Optional.empty(), List.of(subject("vehicle", e.vehicleId())),
              "road disruption" + e.districtName().map(d -> " in " + d).orElse("") + ": " + e.description(), false,
              "road.disruption_reported:" + e.vehicleId() + ":" + e.at()),
          envelope);
    }
  }

  // ---- Receipt: the shortage investigation (R-RCP-07) ------------------------------

  /**
   * Links what the store reported to the delivery and the trip whose loading
   * check it contradicts. One investigation per receipt; never auto-resolved.
   *
   * <p>A short receipt is investigated when it contradicts a passing loading
   * check (R-RCP-07). When every short unit is one the loader already flagged at
   * the dock, and the store added nothing of its own, the shortage is already an
   * issue (LOADING_SHORTFALL) and a second one would report the same goods twice.
   */
  @Component
  static class Investigations {
    /** The checks that mean "not loaded": the goods never left the dock. */
    private static final Set<CheckStatus> FLAGGED =
        EnumSet.of(CheckStatus.SHORT, CheckStatus.MISSING, CheckStatus.DAMAGED, CheckStatus.DOES_NOT_FIT);

    private final ReceiptQuery receipts;
    private final ObjectProvider<LoadingQuery> loading;
    private final JdbcIssueAttachments attachments;
    private final Metrics metrics;

    Investigations(
        ReceiptQuery receipts, ObjectProvider<LoadingQuery> loading, JdbcIssueAttachments attachments, Metrics metrics) {
      this.receipts = receipts;
      this.attachments = attachments;
      this.loading = loading;
      this.metrics = metrics;
    }

    /** A partial receipt (RCP-01): investigated unless the loader's own flags explain all of it. */
    Optional<Raised> ofPartial(UUID receiptId, UUID orderId, String outletId) {
      Optional<ReceiptView> receipt = receipts.receiptFor(orderId);
      if (receipt.isPresent()
          && receipt.get().note().isEmpty()
          && attachments.ofReceipt(receiptId).isEmpty()
          && explainedByLoading(receipt.get())) {
        metrics.increment("waypoint.issues.investigation", "outcome", "explained_by_loading");
        return Optional.empty();
      }
      return raise(receiptId, orderId, outletId, Optional.empty(), "partial receipt", receipt);
    }

    Optional<Raised> of(UUID receiptId, UUID orderId, String outletId, Optional<String> depotHint, String what) {
      return raise(receiptId, orderId, outletId, depotHint, what, receipts.receiptFor(orderId));
    }

    /**
     * True only when the loading check can be read and every short unit on the receipt is matched by
     * a unit the loader flagged for the same product. Anything unknown is investigated.
     */
    private boolean explainedByLoading(ReceiptView receipt) {
      LoadingQuery query = loading.getIfAvailable();
      if (query == null || receipt.tripId().isEmpty()) {
        return false;
      }
      Optional<ManifestLineView> line;
      try {
        line = query.orderLine(receipt.tripId().get(), receipt.orderId());
      } catch (RuntimeException e) {
        log.warn("loading check for order {} could not be read: {}", receipt.orderId(), e.toString());
        return false;
      }
      if (line.isEmpty()) {
        return false;
      }
      Map<String, Integer> flagged = new HashMap<>();
      for (ItemView item : line.get().items()) {
        if (FLAGGED.contains(item.status())) {
          flagged.merge(item.productId(), Math.max(0, item.units() - item.loadedUnits()), Integer::sum);
        }
      }
      boolean anyShort = false;
      for (ReceiptLineView l : receipt.lines()) {
        int shortBy = l.expectedQuantity() - l.receivedQuantity().orElse(l.expectedQuantity());
        if (shortBy <= 0) {
          continue;
        }
        anyShort = true;
        if (shortBy > flagged.getOrDefault(l.productId(), 0)) {
          return false;
        }
      }
      return anyShort;
    }

    private Optional<Raised> raise(
        UUID receiptId,
        UUID orderId,
        String outletId,
        Optional<String> depotHint,
        String what,
        Optional<ReceiptView> receipt) {
      Optional<String> depot = depotHint.or(() -> receipt.map(ReceiptView::depotCode));
      if (depot.isEmpty()) {
        log.warn("receipt {} names order {}, which Receipt does not hold", receiptId, orderId);
        return Optional.empty();
      }
      List<SubjectRef> subjects = new ArrayList<>(List.of(subject("order", orderId), subject("receipt", receiptId)));
      receipt.ifPresent(r -> subjects.add(subject("delivery", r.deliveryId())));
      receipt.flatMap(ReceiptView::tripId).ifPresent(t -> subjects.add(subject("trip", t)));
      // The store's own words, so the dispatcher reads what it saw (a dispute's reason is already "what").
      String note =
          receipt.flatMap(ReceiptView::note).filter(n -> !n.equals(what)).map(n -> ". Store's note: " + n).orElse("");
      metrics.increment("waypoint.issues.investigation", "outcome", "raised");
      return Optional.of(
          new Raised(
              IssueType.RECEIPT_DISPUTE, depot.get(), Optional.of(outletId), subjects,
              "shortage investigation: " + what + note, true, "receipt:" + receiptId, Optional.of(receiptId)));
    }
  }

  @Component
  static class OnReceiptDisputed extends IssuesConsumer<ReceiptDisputed> {
    private final Investigations investigations;

    OnReceiptDisputed(Raiser raiser, Investigations investigations) {
      super(raiser);
      this.investigations = investigations;
    }

    @Override
    public String consumerName() {
      return "issues.on-receipt-disputed";
    }

    @Override
    public Class<ReceiptDisputed> eventType() {
      return ReceiptDisputed.class;
    }

    @Override
    public void on(EventEnvelope<ReceiptDisputed> envelope) {
      ReceiptDisputed e = envelope.payload();
      investigations.of(e.receiptId(), e.orderId(), e.outletId(), Optional.of(e.depotCode()), e.reason())
          .ifPresent(r -> raiser.raise(r, envelope));
    }
  }

  /** A partial receipt is a shortage to investigate (RCP-01); a full one is not. */
  @Component
  static class OnReceiptConfirmed extends IssuesConsumer<ReceiptConfirmed> {
    private final Investigations investigations;

    OnReceiptConfirmed(Raiser raiser, Investigations investigations) {
      super(raiser);
      this.investigations = investigations;
    }

    @Override
    public String consumerName() {
      return "issues.on-receipt-confirmed";
    }

    @Override
    public Class<ReceiptConfirmed> eventType() {
      return ReceiptConfirmed.class;
    }

    @Override
    public void on(EventEnvelope<ReceiptConfirmed> envelope) {
      ReceiptConfirmed e = envelope.payload();
      if (!e.partial()) {
        return;
      }
      investigations.ofPartial(e.receiptId(), e.orderId(), e.outletId()).ifPresent(r -> raiser.raise(r, envelope));
    }
  }

  // ---- Warehouse ---------------------------------------------------------------

  /** The warehouse and Waypoint disagree about an order, for example a cancellation outside Waypoint (STK-11). */
  @Component
  static class OnWarehouseDiscrepancyFound extends IssuesConsumer<WarehouseDiscrepancyFound> {
    private final OrderQuery orders;

    OnWarehouseDiscrepancyFound(Raiser raiser, OrderQuery orders) {
      super(raiser);
      this.orders = orders;
    }

    @Override
    public String consumerName() {
      return "issues.on-warehouse-discrepancy-found";
    }

    @Override
    public Class<WarehouseDiscrepancyFound> eventType() {
      return WarehouseDiscrepancyFound.class;
    }

    @Override
    public void on(EventEnvelope<WarehouseDiscrepancyFound> envelope) {
      WarehouseDiscrepancyFound e = envelope.payload();
      raiser.raise(
          new Raised(
              IssueType.STOCK_DISCREPANCY, e.depotCode(), orders.order(e.orderId()).map(OrderView::outletId),
              List.of(subject("order", e.orderId())),
              "warehouse says " + e.warehouseStatus() + ", Waypoint says " + e.waypointStatus() + ": " + e.detail(),
              false, "warehouse.discrepancy_found:" + e.orderId() + ":" + e.warehouseStatus()),
          envelope);
    }
  }
}
