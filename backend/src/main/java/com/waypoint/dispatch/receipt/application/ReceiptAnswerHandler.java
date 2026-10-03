package com.waypoint.dispatch.receipt.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.receipt.contract.ReceiptCommands.ReceivedLine;
import com.waypoint.dispatch.receipt.domain.Receipt;
import com.waypoint.dispatch.receipt.infrastructure.JdbcReceiptRepository;
import com.waypoint.dispatch.receipt.infrastructure.JdbcReceiptRepository.Stored;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The store answering a receipt: the same steps for a confirmation, a partial
 * receipt and a dispute, with only the decision and the announcement differing.
 *
 * <ol>
 *   <li>Find the receipt for the order. None means no delivery was recorded, and
 *       nothing can be confirmed that did not happen (RCP-04).
 *   <li>Scope: the outlet's own manager, or {@code 403} plus the bus's audit row
 *       (RCP-05). The outlet is read as the system in a read-only transaction of
 *       its own, so an out-of-scope request is refused as forbidden rather than
 *       answered as absent.
 *   <li>The version the store saw, or {@code 409}: never last-writer-wins.
 *   <li>The domain decides; the receipt, its history and the event commit together.
 * </ol>
 */
abstract class ReceiptAnswerHandler implements CommandHandler {
  protected final Database database;
  protected final JdbcReceiptRepository receipts;
  protected final EventPublisher events;
  protected final Metrics metrics;
  protected final Clock clock;
  private final HandoverIssuer handover;

  ReceiptAnswerHandler(
      Database database,
      JdbcReceiptRepository receipts,
      EventPublisher events,
      Metrics metrics,
      Clock clock,
      HandoverIssuer handover) {
    this.database = database;
    this.receipts = receipts;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
    this.handover = handover;
  }

  /** The store's decision on the receipt as it stands. */
  protected abstract Receipt decide(Receipt current, Command command, Actor actor, Instant now);

  /** What other modules hear about the decision. */
  protected abstract DomainEvent announce(Receipt before, Receipt after, Instant now);

  /** The history reason, never personal data. */
  protected abstract String reason(Receipt after);

  @Override
  public String action() {
    return kind();
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.RECEIPT;
  }

  @Override
  public String resource(Command command) {
    UUID id = CommandPayload.of(command).optionalUuid("orderId");
    return id == null ? null : "wpt:receipt:order:" + id;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    if (command.expectedVersion() == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required to answer a receipt");
    }
    long expected = command.expectedVersion();
    CommandPayload payload = CommandPayload.of(command);
    UUID orderId = payload.uuid("orderId");
    Instant now = clock.now();

    requireOutletScope(orderId);
    Stored stored =
        receipts.findByOrder(orderId)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No receipt for order " + orderId));
    Receipt current = stored.receipt();
    if (current.rowVersion() != expected) {
      throw new DomainException(
          ErrorCode.VERSION_CONFLICT,
          "receipt for order " + orderId + " is at version " + current.rowVersion() + ", not " + expected);
    }

    Receipt next = decide(current, command, actor, now);
    long version = receipts.update(next, expected, now);
    receipts.recordStatus(
        next.receiptId(), Optional.of(current.status()), next.status(), reason(next), actor.userId(),
        Optional.empty(), now);
    events.publish(actor, announce(current, next, now));
    metrics.increment("waypoint.receipt.answered", "status", next.status().name(), "late", Boolean.toString(next.late()));

    // The handover PIN (R-RCP-09): returned once, here, and never stored. Absent when none could be issued.
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("receiptId", next.receiptId().toString());
    result.put("orderId", orderId.toString());
    result.put("status", next.status().name());
    result.put("late", next.late());
    result.put("rowVersion", version);
    handover
        .issue(next, actor.userId(), now)
        .ifPresent(
            issued -> {
              result.put("handoverPin", issued.pin());
              result.put("handoverExpiresAt", issued.expiresAt().toString());
            });
    return result;
  }

  /**
   * The receipt's outlet, read as the system so a receipt the actor cannot see
   * is told apart from one that does not exist. Only the existence of a random
   * order id is revealed, and only to someone policy already lets answer receipts.
   */
  private void requireOutletScope(UUID orderId) {
    String outletId =
        database.readAs(
                ModuleRole.RECEIPT,
                Actor.SYSTEM_ID,
                () -> receipts.findByOrder(orderId).map(s -> s.receipt().outletId()))
            .orElseThrow(
                () ->
                    new DomainException(
                        ErrorCode.NOT_FOUND,
                        "no delivery has been recorded for order " + orderId + "; there is nothing to confirm",
                        List.of("RCP-04")));
    Map<String, Object> scope =
        database.queryOne("SELECT app.actor_is_system() OR app.actor_has_outlet(?) AS ok", outletId);
    if (!Boolean.TRUE.equals(scope.get("ok"))) {
      throw new DomainException(
          ErrorCode.FORBIDDEN, "outlet " + outletId + " is outside the actor's scope", List.of("RCP-05"));
    }
  }

  /** {@code lines: [{productId, receivedQuantity}]}, optional; each named field is checked. */
  static List<ReceivedLine> lines(Command command) {
    JsonNode node = command.payload() == null ? null : command.payload().get("lines");
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "lines must be a list");
    }
    List<ReceivedLine> out = new ArrayList<>();
    for (JsonNode line : node) {
      JsonNode product = line.get("productId");
      JsonNode quantity = line.get("receivedQuantity");
      if (product == null || !product.isTextual() || product.asText().isBlank()
          || quantity == null || !quantity.canConvertToInt() || quantity.asInt() < 0) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED,
            "each line needs a productId and a receivedQuantity of zero or more",
            List.of("R-RCP-01"));
      }
      out.add(new ReceivedLine(product.asText(), quantity.asInt()));
    }
    return out;
  }
}
