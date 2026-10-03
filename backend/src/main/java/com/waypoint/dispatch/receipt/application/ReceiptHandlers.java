package com.waypoint.dispatch.receipt.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.receipt.contract.ReceiptCommands;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptConfirmed;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptDisputed;
import com.waypoint.dispatch.receipt.domain.Receipt;
import com.waypoint.dispatch.receipt.infrastructure.JdbcReceiptRepository;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The store's three answers to a delivery (R-RCP-01). */
final class ReceiptHandlers {
  private ReceiptHandlers() {}

  /** Everything expected arrived: {@code receipt.confirmed}, not partial. */
  @Component
  static class ConfirmReceiptHandler extends ReceiptAnswerHandler {
    ConfirmReceiptHandler(
        Database database,
        JdbcReceiptRepository receipts,
        EventPublisher events,
        Metrics metrics,
        Clock clock,
        HandoverIssuer handover) {
      super(database, receipts, events, metrics, clock, handover);
    }

    @Override
    public String kind() {
      return ReceiptCommands.CONFIRM;
    }

    @Override
    protected Receipt decide(Receipt current, Command command, Actor actor, Instant now) {
      return current.confirm(actor.userId(), now);
    }

    @Override
    protected DomainEvent announce(Receipt before, Receipt after, Instant now) {
      return new ReceiptConfirmed(after.receiptId(), after.orderId(), after.outletId(), false, now);
    }

    @Override
    protected String reason(Receipt after) {
      return "confirmed by the store";
    }
  }

  /**
   * Some of the order arrived (RCP-01). Before auto-close this is
   * {@code receipt.confirmed} with {@code partial}; Issues opens a shortage
   * investigation from it. After auto-close the order is already UNCONFIRMED, so
   * a late shortage is announced as {@code receipt.disputed} instead (RCP-08).
   */
  @Component
  static class ConfirmPartialReceiptHandler extends ReceiptAnswerHandler {
    ConfirmPartialReceiptHandler(
        Database database,
        JdbcReceiptRepository receipts,
        EventPublisher events,
        Metrics metrics,
        Clock clock,
        HandoverIssuer handover) {
      super(database, receipts, events, metrics, clock, handover);
    }

    @Override
    public String kind() {
      return ReceiptCommands.CONFIRM_PARTIAL;
    }

    @Override
    protected Receipt decide(Receipt current, Command command, Actor actor, Instant now) {
      return current.confirmPartial(
          lines(command), Optional.ofNullable(CommandPayload.of(command).text("note")), actor.userId(), now);
    }

    @Override
    protected DomainEvent announce(Receipt before, Receipt after, Instant now) {
      if (after.late()) {
        return new ReceiptDisputed(
            after.receiptId(), after.orderId(), after.outletId(), after.depotCode(),
            "shortage of " + after.unitsShort() + " units reported after auto-close", now);
      }
      return new ReceiptConfirmed(after.receiptId(), after.orderId(), after.outletId(), true, now);
    }

    @Override
    protected String reason(Receipt after) {
      return (after.late() ? "late partial receipt, " : "partial receipt, ") + after.unitsShort() + " units short";
    }
  }

  /** The store disagrees with what was delivered: {@code receipt.disputed}, never auto-closed. */
  @Component
  static class DisputeReceiptHandler extends ReceiptAnswerHandler {
    DisputeReceiptHandler(
        Database database,
        JdbcReceiptRepository receipts,
        EventPublisher events,
        Metrics metrics,
        Clock clock,
        HandoverIssuer handover) {
      super(database, receipts, events, metrics, clock, handover);
    }

    @Override
    public String kind() {
      return ReceiptCommands.DISPUTE;
    }

    @Override
    protected Receipt decide(Receipt current, Command command, Actor actor, Instant now) {
      return current.dispute(
          CommandPayload.of(command).requiredText("reason"), lines(command), actor.userId(), now);
    }

    @Override
    protected DomainEvent announce(Receipt before, Receipt after, Instant now) {
      return new ReceiptDisputed(
          after.receiptId(), after.orderId(), after.outletId(), after.depotCode(), after.note().orElseThrow(), now);
    }

    @Override
    protected String reason(Receipt after) {
      return after.late() ? "disputed after auto-close" : "disputed by the store";
    }
  }
}
