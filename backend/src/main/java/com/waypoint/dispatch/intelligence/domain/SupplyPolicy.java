package com.waypoint.dispatch.intelligence.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * R-RCP-06: the probability that an order is supplied on its scheduled day,
 * with the basis it rests on, so the store can tell a planned certainty from an
 * estimate.
 *
 * <p>Rates are smoothed with one success and one failure added (a uniform
 * prior), so an outlet with no history reads 0.5, an honest "unknown", rather
 * than a confident 0 or 1.
 */
public final class SupplyPolicy {
  private SupplyPolicy() {}

  public enum State {
    /** Cancelled: it will not be supplied. */
    CANCELLED,
    /** Already delivered or received. */
    DELIVERED,
    /** Waiting for a plan, or in one. */
    OPEN
  }

  /**
   * @param publishedPlan a plan for the order's depot and scheduled day is published
   * @param allocated the order has a stop in that plan
   * @param outletFailures failed or skipped stops at the outlet recently
   * @param outletAttempts stops at the outlet that reached an outcome recently
   * @param recentDeferred orders of the same depot, brand and temperature deferred recently
   * @param recentPlanned orders of the same depot, brand and temperature allocated recently
   */
  public record Inputs(
      State state,
      boolean publishedPlan,
      boolean allocated,
      int outletFailures,
      int outletAttempts,
      int recentDeferred,
      int recentPlanned) {}

  public record Estimate(BigDecimal probability, String basis) {}

  public static Estimate estimate(Inputs in) {
    return switch (in.state()) {
      case CANCELLED -> new Estimate(BigDecimal.ZERO.setScale(4), "cancelled");
      case DELIVERED -> new Estimate(BigDecimal.ONE.setScale(4), "delivered");
      case OPEN -> {
        if (in.publishedPlan()) {
          yield in.allocated()
              ? new Estimate(complement(in.outletFailures(), in.outletAttempts()), "planned")
              : new Estimate(BigDecimal.ZERO.setScale(4), "deferred");
        }
        yield new Estimate(
            complement(in.recentDeferred(), in.recentDeferred() + in.recentPlanned()), "deferral_rate");
      }
    };
  }

  /** 1 - (failures + 1) / (trials + 2). */
  static BigDecimal complement(int failures, int trials) {
    double rate = (failures + 1.0) / (Math.max(trials, failures) + 2.0);
    return BigDecimal.valueOf(1.0 - rate).setScale(4, RoundingMode.HALF_UP);
  }
}
