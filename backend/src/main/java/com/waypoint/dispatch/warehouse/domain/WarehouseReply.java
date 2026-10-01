package com.waypoint.dispatch.warehouse.domain;

import com.waypoint.dispatch.warehouse.domain.WarehouseOrder.Shortfall;
import java.util.List;

/**
 * What one warehouse call came back with, already parsed, before any decision.
 * The HTTP client produces it; {@link PlacementDecision} and the jobs decide.
 */
public sealed interface WarehouseReply {

  /** {@code 2xx} with an order body; {@code shortfall} is non-empty only on a {@code 202}. */
  record Answered(int status, WarehouseOrder order, List<Shortfall> shortfall)
      implements WarehouseReply {

    public Answered {
      shortfall = List.copyOf(shortfall);
    }
  }

  /**
   * A {@code 4xx} the warehouse chose: {@code 409} insufficient stock or invalid
   * transition, {@code 404} unknown product or order, {@code 422} validation.
   */
  record Refused(int status, String code, String message, List<Shortfall> shortfall)
      implements WarehouseReply {

    public Refused {
      shortfall = List.copyOf(shortfall);
    }
  }

  /**
   * No usable answer: a timeout, a connection failure, a {@code 5xx}, a
   * {@code 401} for a revoked key, a body that does not parse, or an open
   * circuit.
   *
   * @param sent false only when the request certainly never left (open circuit);
   *     otherwise the outcome of a write is unknown
   */
  record Failed(String reason, boolean sent) implements WarehouseReply {}
}
