package com.waypoint.dispatch.intelligence.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Roadmap AI-J5, step 1: which ready replies a dispatcher takes, learned from
 * the replies they were shown and the one they picked.
 *
 * <p>A logistic regression over every reply on screen: one row per reply shown,
 * labelled 1 for the one picked. Its features are the reply itself and its place
 * in the list, so the pull of being first is learned apart from the reply and
 * left out of the score that ranks it. Plain gradient descent with an L2 penalty,
 * fixed steps from zero: the same record always gives the same scores.
 *
 * <p>Below {@link #MIN_POSTS} posts the ranker is not trained and the screen
 * keeps the rule order; a reply never seen keeps its rule place.
 */
public final class ReplyRanker {
  public static final int MIN_POSTS = 200;
  /** Places 1, 2, 3 and "4 or later": few lists are longer than four. */
  static final int PLACES = 4;
  static final int STEPS = 400;
  static final double LEARNING_RATE = 0.5;
  static final double L2 = 0.01;

  private ReplyRanker() {}

  /** One post: the replies on screen in order and the place (1 first) of the one picked. */
  public record Choice(List<String> shown, int rank, boolean explored) {
    public Choice {
      shown = List.copyOf(shown);
    }
  }

  /**
   * @param scores each reply's learned pull, without its place; higher is taken more
   * @param placeEffect the pull of places 2, 3 and 4 or later against the first
   * @param topAcceptance the share of posts, not explored, whose pick was the first reply shown
   */
  public record Ranking(
      boolean trained, int posts, Map<String, Double> scores, List<Double> placeEffect, double topAcceptance) {}

  public static Ranking fit(List<Choice> choices) {
    List<Choice> usable = choices.stream()
        .filter(c -> c.rank() >= 1 && c.rank() <= c.shown().size())
        .toList();
    double top = topAcceptance(usable);
    if (usable.size() < MIN_POSTS) {
      return new Ranking(false, usable.size(), Map.of(), List.of(), top);
    }
    List<String> replies = new ArrayList<>(new TreeSet<>(usable.stream().flatMap(c -> c.shown().stream()).toList()));
    Map<String, Integer> index = new LinkedHashMap<>();
    replies.forEach(r -> index.put(r, index.size()));
    // Weights: an intercept, one per reply, one per place after the first.
    int n = 1 + replies.size() + (PLACES - 1);
    double[] w = new double[n];
    List<int[]> rows = new ArrayList<>();
    List<Integer> labels = new ArrayList<>();
    for (Choice c : usable) {
      for (int p = 0; p < c.shown().size(); p++) {
        int place = Math.min(p, PLACES - 1);
        rows.add(new int[] {index.get(c.shown().get(p)), place});
        labels.add(p + 1 == c.rank() ? 1 : 0);
      }
    }
    int placeOffset = 1 + replies.size();
    for (int step = 0; step < STEPS; step++) {
      double[] grad = new double[n];
      for (int i = 0; i < rows.size(); i++) {
        int[] row = rows.get(i);
        double z = w[0] + w[1 + row[0]] + (row[1] > 0 ? w[placeOffset + row[1] - 1] : 0);
        double err = sigmoid(z) - labels.get(i);
        grad[0] += err;
        grad[1 + row[0]] += err;
        if (row[1] > 0) grad[placeOffset + row[1] - 1] += err;
      }
      for (int j = 0; j < n; j++) {
        double penalty = j == 0 ? 0 : L2 * w[j];
        w[j] -= LEARNING_RATE * (grad[j] / rows.size() + penalty);
      }
    }
    Map<String, Double> scores = new LinkedHashMap<>();
    replies.forEach(r -> scores.put(r, round(w[1 + index.get(r)])));
    List<Double> places = new ArrayList<>();
    for (int k = 0; k < PLACES - 1; k++) places.add(round(w[placeOffset + k]));
    return new Ranking(true, usable.size(), scores, places, top);
  }

  static double topAcceptance(List<Choice> choices) {
    List<Choice> plain = choices.stream().filter(c -> !c.explored()).toList();
    if (plain.isEmpty()) return 0;
    return round((double) plain.stream().filter(c -> c.rank() == 1).count() / plain.size());
  }

  private static double sigmoid(double z) {
    return 1 / (1 + Math.exp(-z));
  }

  private static double round(double v) {
    return Math.round(v * 1000) / 1000.0;
  }
}
