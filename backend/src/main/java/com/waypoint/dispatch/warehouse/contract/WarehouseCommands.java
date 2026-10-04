package com.waypoint.dispatch.warehouse.contract;

import java.time.LocalDate;
import java.util.UUID;

/** Administrative commands on the warehouse integration. */
public final class WarehouseCommands {
  private WarehouseCommands() {}

  public static final String REPLAY_INBOUND = "warehouse:ReplayInbound";
  public static final String DISCARD_INBOUND = "warehouse:DiscardInbound";
  public static final String RECONCILE = "warehouse:Reconcile";

  /** Process a quarantined inbound event again. An unverified event is never processed. */
  public record ReplayInbound(UUID inboundEventId) {}

  public record DiscardInbound(UUID inboundEventId, String reason) {}

  /** Compare Waypoint orders for a day with warehouse order status; raise, never auto-correct. */
  public record Reconcile(LocalDate serviceDate) {}
}
