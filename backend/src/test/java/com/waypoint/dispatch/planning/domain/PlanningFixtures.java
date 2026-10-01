package com.waypoint.dispatch.planning.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Small builders over the real reference values (district_travel.csv, service_allowance.csv). */
final class PlanningFixtures {
  private PlanningFixtures() {}

  static final AtomicInteger SEQ = new AtomicInteger();

  static final Map<String, DistrictTravel> TRAVEL =
      Map.of(
          "Colombo", travel("Colombo", 24, 8, 12, 4),
          "Gampaha", travel("Gampaha", 37, 9, 28, 7),
          "Puttalam", travel("Puttalam", 173, 24, 130, 18),
          "Kurunegala", travel("Kurunegala", 127, 19, 95, 14));

  static final RuleSet RULES = new RuleSet(UUID.randomUUID(), RuleSet.bookletParameters());

  static final PlanContext CONTEXT = new PlanContext("Peliyagoda", TRAVEL, RULES);

  static DistrictTravel travel(String district, int outMin, int interMin, int outKm, int interKm) {
    return new DistrictTravel(
        district, BigDecimal.valueOf(outMin), BigDecimal.valueOf(interMin), BigDecimal.valueOf(outKm), BigDecimal.valueOf(interKm));
  }

  static Builder order() {
    return new Builder();
  }

  static final class Builder {
    String brand = "Fresh";
    String district = "Colombo";
    String temperature = "ambient";
    String depot = "Peliyagoda";
    BigDecimal weight = new BigDecimal("100");
    BigDecimal volume = new BigDecimal("1");
    String dock = "street";
    boolean vanOnly;
    boolean mall;
    LocalTime open = LocalTime.of(3, 0);
    LocalTime close = LocalTime.of(8, 0);
    boolean noWindow;
    BigDecimal service;
    int deferrals;
    int unserved = 1;
    String ref;

    Builder brand(String v) {
      brand = v;
      if (!"Fresh".equals(v)) {
        open = LocalTime.of(9, 0);
        close = LocalTime.of(17, 0);
      }
      return this;
    }

    Builder district(String v) {
      district = v;
      return this;
    }

    Builder chilled() {
      temperature = "chilled";
      return this;
    }

    Builder temperature(String v) {
      temperature = v;
      return this;
    }

    Builder depot(String v) {
      depot = v;
      return this;
    }

    Builder weight(String v) {
      weight = new BigDecimal(v);
      return this;
    }

    Builder volume(String v) {
      volume = new BigDecimal(v);
      return this;
    }

    Builder dock(String v) {
      dock = v;
      return this;
    }

    Builder vanOnly() {
      vanOnly = true;
      return this;
    }

    Builder mall() {
      mall = true;
      return this;
    }

    Builder window(String from, String to) {
      open = LocalTime.parse(from);
      close = LocalTime.parse(to);
      return this;
    }

    Builder noWindow() {
      noWindow = true;
      return this;
    }

    Builder service(int minutes) {
      service = BigDecimal.valueOf(minutes);
      return this;
    }

    Builder deferrals(int v) {
      deferrals = v;
      return this;
    }

    Builder unservedDays(int v) {
      unserved = v;
      return this;
    }

    Builder ref(String v) {
      ref = v;
      return this;
    }

    PlanOrder build() {
      int n = SEQ.incrementAndGet();
      BigDecimal minutes = service != null ? service : BigDecimal.valueOf(allowance(brand, dock));
      return new PlanOrder(
          UUID.nameUUIDFromBytes(("order-" + n).getBytes()),
          ref != null ? ref : String.format("T-%04d", n),
          "OUT" + n,
          depot,
          brand,
          district,
          temperature,
          weight,
          volume,
          dock,
          vanOnly,
          mall,
          noWindow ? Optional.empty() : Optional.of(open),
          noWindow ? Optional.empty() : Optional.of(close),
          minutes,
          deferrals,
          unserved,
          LocalDate.of(2026, 10, 2));
    }
  }

  static int allowance(String brand, String dock) {
    return switch (brand + "/" + dock) {
      case "Fresh/rear_dock" -> 15;
      case "Fresh/street" -> 16;
      case "Fresh/mall_bay" -> 18;
      case "Style/rear_dock" -> 38;
      case "Style/street" -> 46;
      case "Style/mall_bay" -> 59;
      case "Tech/rear_dock" -> 43;
      default -> 55;
    };
  }

  static FleetVehicle truck(String id) {
    return vehicle(id, false, false, "5000", "30", true);
  }

  static FleetVehicle reefer(String id) {
    return vehicle(id, false, true, "5000", "30", true);
  }

  static FleetVehicle van(String id) {
    return vehicle(id, true, false, "1100", "8", true);
  }

  static FleetVehicle vehicle(String id, boolean van, boolean reefer, String kg, String m3, boolean available) {
    return new FleetVehicle(
        id, "Peliyagoda", van, reefer, new BigDecimal(kg), new BigDecimal(m3), new BigDecimal("5"), new BigDecimal("400"), available, BigDecimal.ZERO);
  }

  static Constraint.Candidate candidate(VehicleDay day) {
    return new Constraint.Candidate(day, CONTEXT, Set.of());
  }

  static VehicleDay oneTrip(FleetVehicle v, PlanOrder... orders) {
    VehicleDay day = VehicleDay.idle(v).withNewTrip(orders[0]);
    for (int i = 1; i < orders.length; i++) {
      day = day.withJoined(1, orders[i]);
    }
    return day;
  }
}
