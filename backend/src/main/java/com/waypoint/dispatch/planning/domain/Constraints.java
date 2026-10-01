package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.domain.Constraint.Candidate;
import com.waypoint.dispatch.planning.domain.TripTimeline.StopTime;
import com.waypoint.dispatch.planning.domain.TripTimeline.TripSchedule;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Every planning rule, once. The engine, the override path, the publication
 * gate and the UI all read these through {@link ConstraintRegistry}; nothing
 * else in the system decides feasibility (architecture rule 5).
 */
public final class Constraints {
  private Constraints() {}

  static String n(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  /** R-FLT-03: a vehicle in the workshop or marked unavailable cannot be allocated. */
  public static final class VehicleAvailable implements Constraint {
    public String ruleId() {
      return "R-FLT-03";
    }

    public String name() {
      return "VehicleAvailable";
    }

    public ConstraintResult check(Candidate c) {
      FleetVehicle v = c.day().vehicle();
      return v.available()
          ? ConstraintResult.pass(ruleId(), name(), v.vehicleId() + " is available", null)
          : ConstraintResult.fail(ruleId(), name(), v.vehicleId() + " is in the workshop or unavailable that day", null);
    }
  }

  /** R-PLN-04: a vehicle serves only its own depot's outlets. */
  public static final class HomeDepot implements Constraint {
    public String ruleId() {
      return "R-PLN-04";
    }

    public String name() {
      return "HomeDepot";
    }

    public ConstraintResult check(Candidate c) {
      String depot = c.day().vehicle().depotCode();
      for (Trip t : c.day().trips()) {
        for (PlanOrder o : t.orders()) {
          if (!depot.equalsIgnoreCase(o.depotCode())) {
            return ConstraintResult.fail(
                ruleId(), name(), c.day().vehicleId() + " is based at " + depot + " but " + o.outletId() + " belongs to " + o.depotCode(), null);
          }
        }
      }
      return ConstraintResult.pass(ruleId(), name(), "every stop belongs to " + depot, null);
    }
  }

  /** R-PLN-01: one brand and one district per trip. */
  public static final class SingleBrandDistrict implements Constraint {
    public String ruleId() {
      return "R-PLN-01";
    }

    public String name() {
      return "SingleBrandDistrict";
    }

    public ConstraintResult check(Candidate c) {
      for (Trip t : c.day().trips()) {
        for (PlanOrder o : t.orders()) {
          if (!t.brand().equals(o.brand()) || !t.district().equals(o.district())) {
            return ConstraintResult.fail(
                ruleId(), name(), "a " + t.brand() + " trip to " + t.district() + " cannot carry a " + o.brand() + " order for " + o.district(), null);
          }
        }
      }
      return ConstraintResult.pass(ruleId(), name(), "each trip is one brand and one district", null);
    }
  }

  /** R-PLN-31: refrigeration is on or off for a whole trip (decision D-J). */
  public static final class SingleTemperaturePerTrip implements Constraint {
    public String ruleId() {
      return "R-PLN-31";
    }

    public String name() {
      return "SingleTemperaturePerTrip";
    }

    public ConstraintResult check(Candidate c) {
      for (Trip t : c.day().trips()) {
        for (PlanOrder o : t.orders()) {
          if (o.temperatureClass() != t.temperature()) {
            return ConstraintResult.fail(
                ruleId(), name(), "a " + t.temperature().code() + " trip cannot carry " + o.temperature() + " order " + o.orderRef(), null);
          }
        }
      }
      return ConstraintResult.pass(ruleId(), name(), "each trip carries one temperature class", null);
    }
  }

  /** R-PLN-02, R-PLN-26: chilled and frozen need a reefer; reefers may carry ambient. */
  public static final class Temperature implements Constraint {
    public String ruleId() {
      return "R-PLN-02";
    }

    public String name() {
      return "Temperature";
    }

    public ConstraintResult check(Candidate c) {
      if (c.day().vehicle().reefer()) {
        return ConstraintResult.pass(ruleId(), name(), c.day().vehicleId() + " is refrigerated", null);
      }
      for (Trip t : c.day().trips()) {
        for (PlanOrder o : t.orders()) {
          if (o.temperatureClass() == TemperatureClass.CHILLED) {
            return ConstraintResult.fail(
                ruleId(), name(), o.orderRef() + " is " + o.temperature() + " and " + c.day().vehicleId() + " is not a reefer", null);
          }
        }
      }
      return ConstraintResult.pass(ruleId(), name(), "no chilled goods on an ambient vehicle", null);
    }
  }

  /** R-PLN-03: a van-only outlet needs a van. */
  public static final class VanOnlyAccess implements Constraint {
    public String ruleId() {
      return "R-PLN-03";
    }

    public String name() {
      return "VanOnlyAccess";
    }

    public ConstraintResult check(Candidate c) {
      if (c.day().vehicle().van()) {
        return ConstraintResult.pass(ruleId(), name(), c.day().vehicleId() + " is a van", null);
      }
      for (Trip t : c.day().trips()) {
        for (PlanOrder o : t.orders()) {
          if (o.vanOnly()) {
            return ConstraintResult.fail(
                ruleId(), name(), o.outletId() + " is van only and " + c.day().vehicleId() + " is a truck", null);
          }
        }
      }
      return ConstraintResult.pass(ruleId(), name(), "no truck at a van-only outlet", null);
    }
  }

  /** R-PLN-05: an order rides on exactly one vehicle and one trip. */
  public static final class WholeOrder implements Constraint {
    public String ruleId() {
      return "R-PLN-05";
    }

    public String name() {
      return "WholeOrder";
    }

    public ConstraintResult check(Candidate c) {
      Set<UUID> seen = new HashSet<>();
      for (Trip t : c.day().trips()) {
        for (PlanOrder o : t.orders()) {
          if (!seen.add(o.orderId()) || c.placedElsewhere().contains(o.orderId())) {
            return ConstraintResult.fail(ruleId(), name(), o.orderRef() + " would be split across trips", null);
          }
        }
      }
      return ConstraintResult.pass(ruleId(), name(), "no order is split", null);
    }
  }

  /** R-PLN-06 on weight: order-level totals, compared with the validator's epsilon. */
  public static final class WeightCapacity implements Constraint {
    public String ruleId() {
      return "R-PLN-06";
    }

    public String name() {
      return "WeightCapacity";
    }

    public ConstraintResult check(Candidate c) {
      return capacity(c, true);
    }
  }

  /** R-PLN-06 on volume. */
  public static final class VolumeCapacity implements Constraint {
    public String ruleId() {
      return "R-PLN-06";
    }

    public String name() {
      return "VolumeCapacity";
    }

    public ConstraintResult check(Candidate c) {
      return capacity(c, false);
    }
  }

  private static ConstraintResult capacity(Candidate c, boolean weight) {
    FleetVehicle v = c.day().vehicle();
    BigDecimal cap = weight ? v.weightCapKg() : v.volumeCapM3();
    String unit = weight ? " kg" : " m3";
    String name = weight ? "WeightCapacity" : "VolumeCapacity";
    BigDecimal epsilon = c.context().rules().epsilon();
    BigDecimal minSlack = null;
    for (int i = 0; i < c.day().trips().size(); i++) {
      Trip t = c.day().trips().get(i);
      BigDecimal load = weight ? t.weightKg() : t.volumeM3();
      BigDecimal slack = cap.subtract(load);
      if (load.compareTo(cap.add(epsilon)) > 0) {
        return ConstraintResult.fail(
            "R-PLN-06", name, "trip " + (i + 1) + " on " + v.vehicleId() + " carries " + n(load) + unit + ", over the " + n(cap) + unit + " cap", slack);
      }
      minSlack = minSlack == null ? slack : minSlack.min(slack);
    }
    return ConstraintResult.pass("R-PLN-06", name, "within " + n(cap) + unit, minSlack == null ? cap : minSlack);
  }

  /** R-PLN-07: at most two trips per vehicle per day (P-03). */
  public static final class TripCount implements Constraint {
    public String ruleId() {
      return "R-PLN-07";
    }

    public String name() {
      return "TripCount";
    }

    public ConstraintResult check(Candidate c) {
      int max = c.context().rules().maxTrips();
      int trips = c.day().trips().size();
      BigDecimal slack = BigDecimal.valueOf((long) max - trips);
      return trips <= max
          ? ConstraintResult.pass(ruleId(), name(), trips + " of " + max + " trips", slack)
          : ConstraintResult.fail(ruleId(), name(), c.day().vehicleId() + " would run " + trips + " trips; the limit is " + max, slack);
    }
  }

  /** R-PLN-09 to R-PLN-11: Fresh trips share 270 min, Style and Tech share 480 min, separately. */
  public static final class TimeBudget implements Constraint {
    public String ruleId() {
      return "R-PLN-09";
    }

    public String name() {
      return "TimeBudget";
    }

    public ConstraintResult check(Candidate c) {
      RuleSet rules = c.context().rules();
      BigDecimal fresh = BigDecimal.ZERO;
      BigDecimal daytime = BigDecimal.ZERO;
      for (Trip t : c.day().trips()) {
        BigDecimal minutes = TripTimeline.formulaMinutes(t, TripTimeline.travelFor(c.context().travel(), t.district()));
        if (t.fresh()) {
          fresh = fresh.add(minutes);
        } else {
          daytime = daytime.add(minutes);
        }
      }
      BigDecimal epsilon = rules.epsilon();
      BigDecimal freshSlack = rules.freshBudgetMinutes().subtract(fresh);
      BigDecimal daytimeSlack = rules.daytimeBudgetMinutes().subtract(daytime);
      if (fresh.compareTo(rules.freshBudgetMinutes().add(epsilon)) > 0) {
        return ConstraintResult.fail(
            "R-PLN-09", name(), "Fresh trips total " + n(fresh) + " min; the pre-dawn budget is " + n(rules.freshBudgetMinutes()), freshSlack);
      }
      if (daytime.compareTo(rules.daytimeBudgetMinutes().add(epsilon)) > 0) {
        return ConstraintResult.fail(
            "R-PLN-10", name(), "Style and Tech trips total " + n(daytime) + " min; the daytime budget is " + n(rules.daytimeBudgetMinutes()), daytimeSlack);
      }
      return ConstraintResult.pass(
          ruleId(), name(), "Fresh " + n(fresh) + " of " + n(rules.freshBudgetMinutes()) + " min, daytime " + n(daytime) + " of " + n(rules.daytimeBudgetMinutes()) + " min", freshSlack.min(daytimeSlack));
    }
  }

  /** R-PLN-13 to R-PLN-15, R-PLN-29, R-PLN-30: arrive inside the effective window; early waits. */
  public static final class DeliveryWindow implements Constraint {
    public String ruleId() {
      return "R-PLN-13";
    }

    public String name() {
      return "DeliveryWindow";
    }

    public ConstraintResult check(Candidate c) {
      for (Trip t : c.day().trips()) {
        for (PlanOrder o : t.orders()) {
          if (!o.hasWindow()) {
            return ConstraintResult.fail(
                "R-PLN-29", name(), o.outletId() + " has no delivery window once its mall window is applied", null);
          }
          if (BigDecimal.valueOf(o.windowMinutes()).compareTo(o.serviceMinutes()) < 0) {
            return ConstraintResult.fail(
                "R-PLN-30", name(), o.outletId() + "'s window is " + o.windowMinutes() + " min, shorter than its " + n(o.serviceMinutes()) + " min service", null);
          }
        }
      }
      List<TripSchedule> schedules = TripTimeline.schedule(c.day(), c.context().travel(), c.context().rules());
      BigDecimal minSlack = null;
      for (TripSchedule s : schedules) {
        for (StopTime stop : s.stops()) {
          BigDecimal slack = stop.minutesToClose();
          if (stop.late()) {
            return ConstraintResult.fail(
                ruleId(), name(), "trip " + s.tripNumber() + " on " + c.day().vehicleId() + " reaches " + stop.order().outletId() + " at " + stop.arrival() + ", after its window closes at " + stop.order().windowClose().get(), slack);
          }
          minSlack = minSlack == null ? slack : minSlack.min(slack);
        }
      }
      return ConstraintResult.pass(ruleId(), name(), "every stop arrives inside its window", minSlack);
    }
  }

  /** R-PLN-16, R-PLN-24: the weekly quota, return legs and other published plans included. */
  public static final class FuelQuota implements Constraint {
    public String ruleId() {
      return "R-PLN-16";
    }

    public String name() {
      return "FuelQuota";
    }

    public ConstraintResult check(Candidate c) {
      BigDecimal remaining = FuelLedger.remainingLitres(c.day(), c.context().travel());
      return remaining.signum() >= 0
          ? ConstraintResult.pass(ruleId(), name(), n(remaining) + " L of the weekly quota left", remaining)
          : ConstraintResult.fail(
              ruleId(), name(), c.day().vehicleId() + " would exceed its weekly fuel quota by " + n(remaining.negate()) + " L", remaining);
    }
  }
}
