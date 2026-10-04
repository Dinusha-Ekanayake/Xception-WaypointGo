package com.waypoint.dispatch.receipt.application;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.receipt.contract.ReceiptCommands;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.HandoverConfirmed;
import com.waypoint.dispatch.receipt.domain.Handover;
import com.waypoint.dispatch.receipt.domain.Handover.Outcome;
import com.waypoint.dispatch.receipt.domain.Handover.Verification;
import com.waypoint.dispatch.receipt.infrastructure.JdbcHandoverRepository;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The two commands on the handover PIN (R-RCP-09). The PIN is evidence, never a
 * gate: nothing here touches the receipt, the delivery or the trip.
 */
final class HandoverHandlers {
  private HandoverHandlers() {}

  /**
   * The handover as the actor sees it. Row-level security shows it to the outlet's manager, the
   * depot's dispatcher and the driver of the vehicle on its date, so one the actor cannot see is
   * refused as outside scope, which is audited, and not answered as absent. That also reveals nothing
   * about whether an order id has a PIN. It is one read in the command's own transaction: a second,
   * as the system, would hold a second connection per command and starve the pool under load.
   */
  private static Handover visible(JdbcHandoverRepository handovers, UUID orderId) {
    return handovers
        .findByOrder(orderId)
        .orElseThrow(
            () ->
                new DomainException(
                    ErrorCode.FORBIDDEN,
                    "no handover PIN for order " + orderId + " is within the actor's scope",
                    List.of("R-RCP-09")));
  }

  /**
   * The driver typing the store's PIN. Scope is the vehicle on its date, so a
   * store manager or a dispatcher, who may hold the PIN or see the row, cannot
   * confirm a handover that is the driver's to confirm.
   *
   * <p>A wrong, late or locked entry is returned as an answer, not thrown: a thrown
   * refusal rolls the transaction back and the attempt would never be counted.
   */
  @Component
  static class VerifyHandoverHandler implements CommandHandler {
    private final JdbcHandoverRepository handovers;
    private final EventPublisher events;
    private final Metrics metrics;
    private final Clock clock;

    VerifyHandoverHandler(JdbcHandoverRepository handovers, EventPublisher events, Metrics metrics, Clock clock) {
      this.handovers = handovers;
      this.events = events;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String kind() {
      return ReceiptCommands.VERIFY_HANDOVER;
    }

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
      CommandPayload payload = CommandPayload.of(command);
      UUID orderId = payload.uuid("orderId");
      String pin = payload.requiredText("pin");
      Instant now = clock.now();

      Handover current = visible(handovers, orderId);
      if (!handovers.actorDrives(current.vehicleId(), current.serviceDate())) {
        throw new DomainException(
            ErrorCode.FORBIDDEN,
            "only the driver of " + current.vehicleId() + " on " + current.serviceDate() + " can confirm this handover",
            List.of("R-RCP-09"));
      }

      Verification v = current.verify(pin, actor.userId(), now);
      long version = current.rowVersion();
      if (!v.next().equals(current)) {
        version = handovers.update(v.next(), current.rowVersion(), now);
      }
      switch (v.outcome()) {
        case VERIFIED -> {
          handovers.record(current.receiptId(), "verified", actor.userId(), now);
          events.publish(
              actor,
              new HandoverConfirmed(
                  current.receiptId(), orderId, current.outletId(), current.depotCode(), actor.userId(), now));
        }
        case WRONG -> handovers.record(current.receiptId(), "wrong_pin", actor.userId(), now);
        case LOCKED -> handovers.record(current.receiptId(), "locked", actor.userId(), now);
        case EXPIRED -> handovers.record(current.receiptId(), "expired_entry", actor.userId(), now);
        case ALREADY_CONFIRMED -> {}
      }
      metrics.increment("waypoint.receipt.handover_entry", "outcome", v.outcome().name());

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("orderId", orderId.toString());
      result.put("verified", v.outcome() == Outcome.VERIFIED || v.outcome() == Outcome.ALREADY_CONFIRMED);
      result.put("outcome", v.outcome().name());
      result.put("attemptsLeft", v.attemptsLeft());
      result.put("rowVersion", version);
      return result;
    }
  }

  /**
   * The store asking for a new PIN, because the first was lost, expired or
   * locked. The PIN is returned once, as the answer to this command, and the
   * count of wrong entries starts again.
   */
  @Component
  static class ReissueHandoverPinHandler implements CommandHandler {
    private final JdbcHandoverRepository handovers;
    private final HandoverIssuer issuer;
    private final Metrics metrics;
    private final Clock clock;

    ReissueHandoverPinHandler(
        JdbcHandoverRepository handovers, HandoverIssuer issuer, Metrics metrics, Clock clock) {
      this.handovers = handovers;
      this.issuer = issuer;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String kind() {
      return ReceiptCommands.REISSUE_HANDOVER_PIN;
    }

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
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED, "expectedVersion is required to reissue a handover PIN", List.of("R-RCP-09"));
      }
      long expected = command.expectedVersion();
      UUID orderId = CommandPayload.of(command).uuid("orderId");
      Instant now = clock.now();

      Handover current = visible(handovers, orderId);
      if (!handovers.actorHasOutlet(current.outletId())) {
        throw new DomainException(
            ErrorCode.FORBIDDEN, "outlet " + current.outletId() + " is outside the actor's scope", List.of("R-RCP-09"));
      }
      if (current.rowVersion() != expected) {
        throw new DomainException(
            ErrorCode.VERSION_CONFLICT,
            "handover for order " + orderId + " is at version " + current.rowVersion() + ", not " + expected);
      }

      String pin = issuer.newPin();
      Handover next = current.reissue(pin, issuer.newSalt(), now);
      long version = handovers.update(next, expected, now);
      handovers.record(current.receiptId(), "reissued", actor.userId(), now);
      metrics.increment("waypoint.receipt.handover_reissued");

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("orderId", orderId.toString());
      result.put("handoverPin", pin);
      result.put("handoverExpiresAt", next.expiresAt().toString());
      result.put("rowVersion", version);
      return result;
    }
  }
}
