package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.contract.PlanEvents.StoreContacted;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanStatus;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.AllocationRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A dispatcher asking an outlet's store manager to act on an order the plan
 * did not serve: too big for any vehicle, or deferred. It changes no plan, so it
 * carries no expected version; the command id makes a repeat harmless. The
 * words are the dispatcher's, recorded on the event with their name (rule 8).
 */
@Component
public class ContactStoreHandler implements CommandHandler {
  static final int MIN_LENGTH = 3;
  static final int MAX_LENGTH = 500;

  private final JdbcPlanRepository plans;
  private final EventPublisher events;
  private final Metrics metrics;

  public ContactStoreHandler(JdbcPlanRepository plans, EventPublisher events, Metrics metrics) {
    this.plans = plans;
    this.events = events;
    this.metrics = metrics;
  }

  @Override
  public String kind() {
    return PlanCommands.CONTACT_STORE;
  }

  @Override
  public String action() {
    return PlanCommands.CONTACT_STORE;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.PLANNING;
  }

  @Override
  public String resource(Command command) {
    UUID id = CommandPayload.of(command).optionalUuid("planId");
    return id == null ? null : "wpt:plan:plan:" + id;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    UUID planId = payload.uuid("planId");
    UUID orderId = payload.uuid("orderId");
    String message = payload.requiredText("message").trim();
    if (message.length() < MIN_LENGTH || message.length() > MAX_LENGTH) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "The message must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters");
    }
    RunRow run =
        plans.findRun(planId).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No plan " + planId));
    if (run.status() != PlanStatus.DRAFT && run.status() != PlanStatus.PUBLISHED) {
      throw new DomainException(ErrorCode.CONFLICT, "plan " + planId + " is " + run.status() + "; read the current plan");
    }
    AllocationRow allocation =
        plans.allocations(planId).stream()
            .filter(a -> a.orderId().equals(orderId))
            .findFirst()
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "order " + orderId + " is not in plan " + planId));
    if (allocation.decision() == AllocationDecision.SERVED) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "order " + orderId + " is on a trip; there is nothing to tell the store");
    }
    events.publish(
        actor,
        new StoreContacted(orderId, planId, allocation.outletId(), run.serviceDate(), message, actor.userId()));
    metrics.increment("waypoint.plan.store_contacted");
    return Map.of("orderId", orderId, "outletId", allocation.outletId());
  }
}
