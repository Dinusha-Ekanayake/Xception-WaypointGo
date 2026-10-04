package com.waypoint.dispatch.warehouse.application;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.warehouse.contract.WarehouseCommands;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcInboundEventRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcInboundEventRepository.InboundEvent;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Administrative commands on the integration. Each records who decided and why (rule 8). */
public final class WarehouseCommandHandlers {
  private WarehouseCommandHandlers() {}

  abstract static class WarehouseHandler implements CommandHandler {
    @Override
    public String action() {
      return kind();
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.WAREHOUSE;
    }
  }

  /**
   * A quarantined event goes back to the queue. Only a verified one: an unverified
   * event is never processed, by an administrator or anyone else (SEC-18).
   */
  @Component
  public static class ReplayInboundHandler extends WarehouseHandler {
    private final JdbcInboundEventRepository inbox;
    private final Clock clock;

    public ReplayInboundHandler(JdbcInboundEventRepository inbox, Clock clock) {
      this.inbox = inbox;
      this.clock = clock;
    }

    @Override
    public String kind() {
      return WarehouseCommands.REPLAY_INBOUND;
    }

    @Override
    public String resource(Command command) {
      UUID id = CommandPayload.of(command).optionalUuid("inboundEventId");
      return id == null ? null : "wpt:warehouse:inbound:" + id;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      CommandPayload payload = CommandPayload.of(command);
      UUID id = payload.uuid("inboundEventId");
      InboundEvent event = quarantined(inbox, id);
      if (!event.signatureVerified()) {
        throw new DomainException(
            ErrorCode.CONSTRAINT_VIOLATED,
            "Inbound event " + id + " failed signature verification and can never be processed",
            List.of("SEC-18"));
      }
      String reason = Optional.ofNullable(payload.text("reason")).orElse("replayed by an administrator");
      inbox.decide(id, "received", actor.userId(), reason, clock.now());
      return Map.of("inboundEventId", id.toString(), "status", "received");
    }
  }

  @Component
  public static class DiscardInboundHandler extends WarehouseHandler {
    private final JdbcInboundEventRepository inbox;
    private final Clock clock;

    public DiscardInboundHandler(JdbcInboundEventRepository inbox, Clock clock) {
      this.inbox = inbox;
      this.clock = clock;
    }

    @Override
    public String kind() {
      return WarehouseCommands.DISCARD_INBOUND;
    }

    @Override
    public String resource(Command command) {
      UUID id = CommandPayload.of(command).optionalUuid("inboundEventId");
      return id == null ? null : "wpt:warehouse:inbound:" + id;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      CommandPayload payload = CommandPayload.of(command);
      UUID id = payload.uuid("inboundEventId");
      String reason = payload.requiredText("reason");
      quarantined(inbox, id);
      inbox.decide(id, "discarded", actor.userId(), reason, clock.now());
      return Map.of("inboundEventId", id.toString(), "status", "discarded");
    }
  }

  /** Raise-only reconciliation for one service day (open decision 4). */
  @Component
  public static class ReconcileHandler extends WarehouseHandler {
    private final WarehouseReconciler reconciler;
    private final Clock clock;

    public ReconcileHandler(WarehouseReconciler reconciler, Clock clock) {
      this.reconciler = reconciler;
      this.clock = clock;
    }

    @Override
    public String kind() {
      return WarehouseCommands.RECONCILE;
    }

    @Override
    public String resource(Command command) {
      LocalDate date = CommandPayload.of(command).optionalDate("serviceDate");
      return date == null ? null : "wpt:warehouse:day:" + date;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      LocalDate date = CommandPayload.of(command).date("serviceDate");
      int raised = reconciler.reconcileWithin(clock.now(), Optional.of(date), 1000);
      return Map.of("serviceDate", date.toString(), "discrepanciesRaised", raised);
    }
  }

  private static InboundEvent quarantined(JdbcInboundEventRepository inbox, UUID id) {
    InboundEvent event =
        inbox.find(id).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No inbound event " + id));
    if (!"quarantined".equals(event.status())) {
      throw new DomainException(
          ErrorCode.CONFLICT, "Inbound event " + id + " is " + event.status() + ", not quarantined");
    }
    return event;
  }
}
