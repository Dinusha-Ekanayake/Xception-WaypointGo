package com.waypoint.dispatch.intelligence.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * How likely a delivery day is to be kept, for a store choosing one ahead
 * (issue #224, R-ML-07). Advice only: the plan made the day before decides.
 *
 * <p>The day's expected load is what is already booked for it, or the
 * forecast's share of the week when that is larger, because most orders for a
 * day weeks away are not placed yet (A-44). It is set against what the
 * vehicles available that day can carry in two trips (R-PLN-07), so a vehicle
 * booked into the workshop lowers it; chilled load is set against reefers only
 * (D-J), and only for a store whose brand sends chilled goods.
 */
public final class DateOutlookPolicy {
  /** P-35: from this share of the day's room the day is busy. */
  public static final BigDecimal BUSY_AT = new BigDecimal("0.80");
  /** P-35: past this share more is expected than the vehicles can carry. */
  public static final BigDecimal AT_RISK_ABOVE = new BigDecimal("1.00");

  private DateOutlookPolicy() {}

  public enum Status {
    ON_TRACK,
    BUSY,
    AT_RISK,
    /** Little is booked and no forecast reaches the day. */
    TOO_EARLY,
    /** The network does not run that day. */
    CLOSED
  }

  /**
   * @param forecastM3 the day's share of the depot's weekly forecast, empty when none reaches it
   * @param capacityM3 every vehicle available that day, two trips each
   * @param reeferCapacityM3 the same for refrigerated vehicles
   * @param chilled whether the store's brand sends chilled goods
   */
  public record Inputs(
      boolean operating,
      BigDecimal bookedM3,
      BigDecimal bookedChilledM3,
      Optional<BigDecimal> forecastM3,
      Optional<BigDecimal> forecastChilledM3,
      BigDecimal capacityM3,
      BigDecimal reeferCapacityM3,
      boolean chilled) {}

  /** {@code load} is expected volume over room, 0.0000 to whatever it is; empty when closed. */
  public record Outlook(Status status, Optional<BigDecimal> load, String reason) {}

  public static Outlook assess(Inputs in) {
    if (!in.operating()) {
      return new Outlook(Status.CLOSED, Optional.empty(), "The network does not run that day");
    }
    BigDecimal expected = in.forecastM3().map(f -> f.max(in.bookedM3())).orElse(in.bookedM3());
    BigDecimal load = ratio(expected, in.capacityM3());
    boolean coldIsTighter = false;
    if (in.chilled()) {
      BigDecimal expectedCold =
          in.forecastChilledM3().map(f -> f.max(in.bookedChilledM3())).orElse(in.bookedChilledM3());
      BigDecimal cold = ratio(expectedCold, in.reeferCapacityM3());
      if (cold.compareTo(load) > 0) {
        load = cold;
        coldIsTighter = true;
      }
    }
    if (in.capacityM3().signum() == 0) {
      return new Outlook(Status.AT_RISK, Optional.of(load), "No vehicle is available that day");
    }
    if (load.compareTo(AT_RISK_ABOVE) > 0) {
      return new Outlook(Status.AT_RISK, Optional.of(load), coldIsTighter
          ? "More chilled goods are expected than the refrigerated vehicles can carry"
          : "More is expected than the vehicles can carry");
    }
    if (load.compareTo(BUSY_AT) >= 0) {
      return new Outlook(Status.BUSY, Optional.of(load), coldIsTighter
          ? "Most of the refrigerated room is expected to be taken"
          : "Most of the room on the vehicles is expected to be taken");
    }
    if (in.forecastM3().isEmpty()) {
      return new Outlook(Status.TOO_EARLY, Optional.of(load), "Little is booked yet and no forecast reaches that day");
    }
    return new Outlook(Status.ON_TRACK, Optional.of(load), "There is room on the vehicles that day");
  }

  /** Expected over room; any load on no room is past any threshold. */
  static BigDecimal ratio(BigDecimal expected, BigDecimal room) {
    if (room.signum() == 0) {
      return expected.signum() == 0 ? BigDecimal.ZERO.setScale(4) : new BigDecimal("9.9999");
    }
    return expected.divide(room, 4, RoundingMode.HALF_UP);
  }
}
