package com.waypoint.dispatch.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** R-PLN-43: the suggested stop order is never worse than the one asked for, and finds the window fit. */
class StopOrderSearchTest {

  /** Stops on a line, a minute apart per step; each must be reached by its close minute. */
  private static Function<List<String>, StopOrderSearch.Score> line(Map<String, Integer> position, Map<String, Integer> close) {
    return order -> {
      int t = 0;
      int at = 0;
      int late = 0;
      int minutesLate = 0;
      for (String stop : order) {
        t += Math.abs(position.get(stop) - at) * 10;
        at = position.get(stop);
        if (t > close.get(stop)) {
          late++;
          minutesLate += t - close.get(stop);
        }
      }
      return new StopOrderSearch.Score(true, late, BigDecimal.valueOf(minutesLate), BigDecimal.valueOf(t + at * 10));
    };
  }

  @Test
  void shortTripIsSearchedWholeAndFindsTheWindowOrder() {
    var position = Map.of("A", 1, "B", 2, "C", 3, "D", 4);
    var close = Map.of("A", 10, "B", 20, "C", 30, "D", 40);
    var result = StopOrderSearch.best(List.of("D", "C", "B", "A"), List.of(), line(position, close), 1000);
    assertThat(result.order()).containsExactly("A", "B", "C", "D");
    assertThat(result.score().lateStops()).isZero();
    assertThat(result.improves()).isTrue();
    assertThat(result.evaluated()).isEqualTo(25);
  }

  @Test
  void anOrderAlreadyBestIsKept() {
    var position = Map.of("A", 1, "B", 2);
    var close = Map.of("A", 10, "B", 20);
    var result = StopOrderSearch.best(List.of("A", "B"), List.of(), line(position, close), 1000);
    assertThat(result.improves()).isFalse();
    assertThat(result.order()).containsExactly("A", "B");
  }

  @Test
  void longTripImprovesFromItsSeedsWithinTheBudget() {
    var stops = List.of("A", "B", "C", "D", "E", "F", "G", "H");
    var position = new java.util.HashMap<String, Integer>();
    var close = new java.util.HashMap<String, Integer>();
    for (int i = 0; i < stops.size(); i++) {
      position.put(stops.get(i), i + 1);
      close.put(stops.get(i), (i + 1) * 10);
    }
    var reversed = new java.util.ArrayList<>(stops);
    java.util.Collections.reverse(reversed);
    var result = StopOrderSearch.best(reversed, List.of(), line(position, close), 500);
    assertThat(result.score().lateStops()).isLessThan(result.current().lateStops());
    assertThat(result.evaluated()).isLessThanOrEqualTo(500);
  }

  @Test
  void aRuleFailureRanksBehindAnyPassingOrder() {
    var pass = new StopOrderSearch.Score(true, 3, BigDecimal.TEN, BigDecimal.valueOf(400));
    var fail = new StopOrderSearch.Score(false, 0, BigDecimal.ZERO, BigDecimal.valueOf(300));
    assertThat(StopOrderSearch.ORDER.compare(pass, fail)).isNegative();
  }
}
