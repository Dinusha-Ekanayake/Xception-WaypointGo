package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Who is served first when capacity runs out (R-PLN-21). A versioned decision
 * table, not a hardcoded comparator: the keys are data in
 * {@code planning.policy_versions}, so reordering them is a new version, not a
 * release, and every plan stamps the version that produced it.
 *
 * <p>Lexicographic on purpose. "Deferred because it ranked 61st and no reefer
 * had room" can be explained to a store manager; a weighted score cannot. The
 * order id is always the final tie-break, so the same inputs give the same plan.
 */
public record PriorityPolicy(UUID id, List<Key> keys) {

  public enum Key {
    /** R-PLN-20: skipped on at least P-12 prior runs. */
    PRIOR_SKIP,
    FRESH,
    CHILLED,
    /** Mall dock, or an effective window shorter than P-17. */
    STRICT_WINDOW,
    /** Days until the brand's next run (P-18): a weekly order deferred waits a week. */
    CADENCE,
    EARLIEST_CLOSE,
    /** C-5, as a tie-break only: windows already won inside the constraints (R-PLN-25). */
    LONGEST_DISTANCE,
    LARGEST_VOLUME,
    LONGEST_UNSERVED
  }

  /** The first policy, decision 1 of the issue #9 plan. */
  public static final List<Key> DEFAULT_KEYS =
      List.of(
          Key.PRIOR_SKIP,
          Key.FRESH,
          Key.CHILLED,
          Key.STRICT_WINDOW,
          Key.CADENCE,
          Key.EARLIEST_CLOSE,
          Key.LONGEST_DISTANCE,
          Key.LARGEST_VOLUME,
          Key.LONGEST_UNSERVED);

  public PriorityPolicy {
    keys = List.copyOf(keys);
    if (keys.stream().distinct().count() != keys.size()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "a priority key appears twice", List.of("R-PLN-21"));
    }
  }

  public static PriorityPolicy parse(UUID id, List<String> names) {
    List<Key> keys = new ArrayList<>();
    for (String name : names) {
      try {
        keys.add(Key.valueOf(name.toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException e) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "unknown priority key " + name, List.of("R-PLN-21"));
      }
    }
    return new PriorityPolicy(id, keys);
  }

  /** Highest priority first. */
  public Comparator<PlanOrder> comparator(PlanContext context) {
    Comparator<PlanOrder> c = (a, b) -> 0;
    for (Key key : keys) {
      c = c.thenComparing(keyComparator(key, context));
    }
    return c.thenComparing(PlanOrder::orderRef).thenComparing(PlanOrder::orderId);
  }

  public List<PlanOrder> rank(List<PlanOrder> orders, PlanContext context) {
    return orders.stream().sorted(comparator(context)).toList();
  }

  private static Comparator<PlanOrder> keyComparator(Key key, PlanContext ctx) {
    RuleSet rules = ctx.rules();
    return switch (key) {
      case PRIOR_SKIP -> Comparator.comparing((PlanOrder o) -> o.deferralCount() < rules.escalationSkips());
      case FRESH -> Comparator.comparing((PlanOrder o) -> !o.fresh());
      case CHILLED -> Comparator.comparing((PlanOrder o) -> o.temperatureClass() != TemperatureClass.CHILLED);
      case STRICT_WINDOW ->
          Comparator.comparing((PlanOrder o) -> !(o.mallDock() || o.windowMinutes() < rules.strictWindowMinutes()));
      case CADENCE -> Comparator.comparing((PlanOrder o) -> -rules.cadenceDays(o.brand()));
      case EARLIEST_CLOSE -> Comparator.comparing((PlanOrder o) -> o.windowClose().orElse(LocalTime.MAX));
      case LONGEST_DISTANCE ->
          Comparator.comparing(
              (PlanOrder o) -> {
                DistrictTravel d = ctx.travel().get(o.district());
                return d == null ? BigDecimal.ZERO : d.outboundMinutes();
              },
              Comparator.reverseOrder());
      case LARGEST_VOLUME -> Comparator.comparing(PlanOrder::volumeM3, Comparator.reverseOrder());
      case LONGEST_UNSERVED -> Comparator.comparing((PlanOrder o) -> -o.daysSinceServed());
    };
  }
}
