package com.waypoint.dispatch.receipt.application;

import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryRecordView;
import com.waypoint.dispatch.loading.contract.LoadingQuery;
import com.waypoint.dispatch.loading.contract.LoadingViews.ManifestLineView;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.receipt.contract.ReceiptQuery;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.CustodyChainView;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.DeliveryFacts;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.PendingReceiptView;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptLineView;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptView;
import com.waypoint.dispatch.receipt.domain.Receipt;
import com.waypoint.dispatch.receipt.infrastructure.JdbcReceiptRepository;
import com.waypoint.dispatch.receipt.infrastructure.JdbcReceiptRepository.Stored;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Reads receipts, for other modules through {@link ReceiptQuery} and for the
 * store, the dispatcher and the auditor through the web layer.
 *
 * <p>Every read runs as {@code waypoint_receipt} in a read-only transaction of
 * its own ({@link Database#readAs}), so row-level security narrows it to the
 * actor's outlets and depots in SQL (rule 7). One receipt outside scope is
 * {@code 404}; a list asked for outside scope is {@code 403} plus an audit row,
 * never an empty list that reads as "nothing to confirm" (RCP-05).
 *
 * <p>The custody chain borrows the loading check and the proof from their
 * modules when those are deployed. Until then each is named as unavailable,
 * never shown as missing evidence (rule 9).
 */
@Component
public class ReceiptDataQuery implements ReceiptQuery {
  public static final String READ = "receipt:Read";

  private final Database database;
  private final JdbcReceiptRepository receipts;
  private final ReferenceQuery reference;
  private final AuditLog audit;
  private final ObjectProvider<LoadingQuery> loading;
  private final ObjectProvider<ExecutionQuery> execution;

  public ReceiptDataQuery(
      Database database,
      JdbcReceiptRepository receipts,
      ReferenceQuery reference,
      AuditLog audit,
      ObjectProvider<LoadingQuery> loading,
      ObjectProvider<ExecutionQuery> execution) {
    this.database = database;
    this.receipts = receipts;
    this.reference = reference;
    this.audit = audit;
    this.loading = loading;
    this.execution = execution;
  }

  // ---- contract: as the ambient actor --------------------------------------

  @Override
  public Optional<ReceiptView> receiptFor(UUID orderId) {
    return read(ambient(), () -> receipts.findByOrder(orderId).map(ReceiptDataQuery::toView));
  }

  @Override
  public List<PendingReceiptView> pendingConfirmations(String outletId) {
    return read(ambient(), () -> pending(outletId));
  }

  @Override
  public Optional<CustodyChainView> custodyChain(UUID orderId) {
    return read(ambient(), () -> receipts.findByOrder(orderId)).map(this::custody);
  }

  // ---- web: as the authenticated actor -------------------------------------

  public ReceiptView receipt(Actor actor, UUID orderId) {
    return read(actor.userId(), () -> receipts.findByOrder(orderId).map(ReceiptDataQuery::toView))
        .orElseThrow(() -> notFound(orderId));
  }

  public List<PendingReceiptView> pendingConfirmations(Actor actor, String outletId) {
    requireOutlet(actor, outletId);
    return read(actor.userId(), () -> pending(outletId));
  }

  public CustodyChainView custodyChain(Actor actor, UUID orderId) {
    return read(actor.userId(), () -> receipts.findByOrder(orderId)).map(this::custody)
        .orElseThrow(() -> notFound(orderId));
  }

  // ---- internals -----------------------------------------------------------

  private UUID ambient() {
    return database.ambientActor().orElse(null);
  }

  private <T> T read(UUID actorId, Supplier<T> work) {
    return database.readAs(ModuleRole.RECEIPT, actorId, work);
  }

  private static DomainException notFound(UUID orderId) {
    return new DomainException(ErrorCode.NOT_FOUND, "No receipt for order " + orderId);
  }

  private List<PendingReceiptView> pending(String outletId) {
    return receipts.pendingForOutlet(outletId).stream()
        .map(r -> new PendingReceiptView(r.orderId(), r.deliveryId(), r.outletId(), r.deliveredAt()))
        .toList();
  }

  /**
   * Policy allowed {@code receipt:Read}; this is the scope half. The outlet's own
   * manager, or a dispatcher of its depot. Audited after the read ends, because
   * the audit write cannot join a read-only transaction.
   */
  private void requireOutlet(Actor actor, String outletId) {
    String depot = reference.outlet(outletId, null).map(o -> o.depotCode()).orElse("");
    boolean inScope =
        read(
            actor.userId(),
            () ->
                Boolean.TRUE.equals(
                    database
                        .queryOne("SELECT app.actor_has_outlet(?) OR app.actor_has_depot(?) AS ok", outletId, depot)
                        .get("ok")));
    if (!inScope) {
      String resource = "wpt:receipt:outlet:" + outletId;
      String reason = "outside the actor's scope";
      audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), READ, resource, reason));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason);
    }
  }

  /** The three records side by side; each read in its own module, none amended (R-RCP-07). */
  private CustodyChainView custody(Stored stored) {
    Receipt r = stored.receipt();
    List<String> unavailable = new ArrayList<>();

    Optional<ManifestLineView> check = Optional.empty();
    LoadingQuery loadingQuery = loading.getIfAvailable();
    if (loadingQuery == null) {
      unavailable.add("loading check: the Loading module is not deployed");
    } else if (r.tripId() == null) {
      unavailable.add("loading check: the delivery named no trip");
    } else {
      try {
        check =
            loadingQuery.manifest(r.tripId())
                .flatMap(m -> m.lines().stream().filter(l -> l.orderId().equals(r.orderId())).findFirst());
        if (check.isEmpty()) {
          unavailable.add("loading check: no matching order in the trip manifest");
        }
      } catch (DomainException e) {
        unavailable.add("loading check: " + e.getMessage());
      }
    }

    Optional<DeliveryRecordView> proof = Optional.empty();
    ExecutionQuery executionQuery = execution.getIfAvailable();
    if (executionQuery == null) {
      unavailable.add("proof of delivery: the Execution module is not deployed");
    } else {
      try {
        proof = executionQuery.deliveryRecord(r.deliveryId());
      } catch (DomainException e) {
        unavailable.add("proof of delivery: " + e.getMessage());
      }
    }

    return new CustodyChainView(
        r.orderId(),
        toView(stored),
        new DeliveryFacts(
            r.deliveryId(), Optional.ofNullable(r.tripId()), r.deliveredAt(), stored.deliveredUnits(),
            stored.deliveredBy()),
        check,
        proof,
        unavailable);
  }

  static ReceiptView toView(Stored stored) {
    Receipt r = stored.receipt();
    return new ReceiptView(
        r.receiptId(),
        r.orderId(),
        r.deliveryId(),
        r.outletId(),
        r.status(),
        r.lines().stream()
            .map(l -> new ReceiptLineView(l.productId(), l.expectedQuantity(), l.receivedQuantity()))
            .toList(),
        r.note(),
        r.confirmedBy(),
        r.confirmedAt(),
        r.rowVersion(),
        Optional.ofNullable(r.tripId()),
        r.depotCode(),
        r.deliveredAt(),
        r.closesAt(),
        r.late());
  }
}
