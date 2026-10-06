package com.waypoint.dispatch.planning.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Finds the stop order that fits a trip's delivery windows best (R-PLN-43).
 *
 * <p>Pure: the caller times each candidate with the same timeline the plan uses
 * and hands back a {@link Score}, so a suggestion can never disagree with how
 * the trip is judged once it is applied. A short trip is searched exhaustively;
 * a longer one starts from the best seed (window orders, the road path, the
 * dispatcher's own order) and improves it by swapping and moving single stops
 * until nothing better is found or the evaluation budget runs out.
 */
public final class StopOrderSearch {
  private StopOrderSearch() {}

  /** Every permutation is tried up to this many stops (6! = 720). */
  public static final int EXHAUSTIVE_UP_TO = 6;

  /** How a candidate order runs, best first by {@link #ORDER}. */
  public record Score(boolean feasible, int lateStops, BigDecimal minutesLate, BigDecimal endMinute) {}

  /** Rules pass first, then fewer late stops, fewer minutes late, then back at the depot sooner. */
  public static final Comparator<Score> ORDER =
      Comparator.comparing((Score s) -> !s.feasible())
          .thenComparingInt(Score::lateStops)
          .thenComparing(Score::minutesLate)
          .thenComparing(Score::endMinute);

  public record Result<T>(List<T> order, Score score, Score current, int evaluated) {

    /** True when the search found an order strictly better than the one the trip has. */
    public boolean improves() {
      return ORDER.compare(score, current) < 0;
    }
  }

  public static <T> Result<T> best(
      List<T> current, List<List<T>> seeds, Function<List<T>, Score> evaluate, int budget) {
    if (current.isEmpty()) {
      throw new IllegalArgumentException("a trip with no stops has no order to suggest");
    }
    Counter counter = new Counter(evaluate, budget);
    Score currentScore = counter.score(current);
    List<T> bestOrder = List.copyOf(current);
    Score bestScore = currentScore;

    if (current.size() <= EXHAUSTIVE_UP_TO) {
      for (List<T> candidate : permutations(current)) {
        if (counter.spent()) {
          break;
        }
        Score s = counter.score(candidate);
        if (ORDER.compare(s, bestScore) < 0) {
          bestOrder = candidate;
          bestScore = s;
        }
      }
      return new Result<>(bestOrder, bestScore, currentScore, counter.used);
    }

    Set<List<T>> starts = new LinkedHashSet<>(seeds);
    for (List<T> seed : starts) {
      if (seed.size() != current.size() || counter.spent()) {
        continue;
      }
      Score s = counter.score(seed);
      if (ORDER.compare(s, bestScore) < 0) {
        bestOrder = List.copyOf(seed);
        bestScore = s;
      }
    }
    boolean improved = true;
    while (improved && !counter.spent()) {
      improved = false;
      for (List<T> neighbour : neighbours(bestOrder)) {
        if (counter.spent()) {
          break;
        }
        Score s = counter.score(neighbour);
        if (ORDER.compare(s, bestScore) < 0) {
          bestOrder = neighbour;
          bestScore = s;
          improved = true;
          break;
        }
      }
    }
    return new Result<>(bestOrder, bestScore, currentScore, counter.used);
  }

  /** Every order reached by swapping two stops or moving one stop to another place. */
  static <T> List<List<T>> neighbours(List<T> order) {
    List<List<T>> out = new ArrayList<>();
    int n = order.size();
    for (int i = 0; i < n; i++) {
      for (int j = i + 1; j < n; j++) {
        List<T> swapped = new ArrayList<>(order);
        swapped.set(i, order.get(j));
        swapped.set(j, order.get(i));
        out.add(List.copyOf(swapped));
      }
    }
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        if (i == j || j == i - 1) {
          continue;
        }
        List<T> moved = new ArrayList<>(order);
        T stop = moved.remove(i);
        moved.add(j, stop);
        out.add(List.copyOf(moved));
      }
    }
    return out;
  }

  static <T> List<List<T>> permutations(List<T> items) {
    List<List<T>> out = new ArrayList<>();
    permute(new ArrayList<>(items), 0, out);
    return out;
  }

  private static <T> void permute(List<T> a, int k, List<List<T>> out) {
    if (k == a.size()) {
      out.add(List.copyOf(a));
      return;
    }
    for (int i = k; i < a.size(); i++) {
      java.util.Collections.swap(a, k, i);
      permute(a, k + 1, out);
      java.util.Collections.swap(a, k, i);
    }
  }

  private static final class Counter {
    private final Function<?, Score> evaluate;
    private final int budget;
    private int used;

    Counter(Function<?, Score> evaluate, int budget) {
      this.evaluate = evaluate;
      this.budget = budget;
    }

    @SuppressWarnings("unchecked")
    <T> Score score(List<T> order) {
      used++;
      return ((Function<List<T>, Score>) evaluate).apply(order);
    }

    boolean spent() {
      return used >= budget;
    }
  }
}
