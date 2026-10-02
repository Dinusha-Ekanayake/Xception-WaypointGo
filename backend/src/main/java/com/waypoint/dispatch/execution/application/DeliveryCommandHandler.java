package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;

/**
 * A command about one stop. The kind is the action, the role is Execution's,
 * and the resource is the stop, so a scoped policy can be evaluated before the
 * transaction and again inside it.
 */
abstract class DeliveryCommandHandler implements CommandHandler {
  private final String kind;

  DeliveryCommandHandler(String kind) {
    this.kind = kind;
  }

  @Override
  public final String kind() {
    return kind;
  }

  @Override
  public final String action() {
    return kind;
  }

  @Override
  public final ModuleRole moduleRole() {
    return ModuleRole.EXECUTION;
  }

  @Override
  public String resource(Command command) {
    return ExecutionMessages.deliveryResource(command);
  }
}
