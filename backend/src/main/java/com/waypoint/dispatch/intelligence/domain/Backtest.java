package com.waypoint.dispatch.intelligence.domain;

import com.waypoint.dispatch.intelligence.domain.ModelMetrics.Scored;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * How each model did on the predictions it actually made, over finished weeks
 * of outcomes (roadmap MF-04, R-ML-16): the stored prediction beside what
 * happened, never a fresh score. Each model is judged against the built-in
 * estimate on the same weeks: better only when both its Brier score and its
 * log loss are lower, with at least {@link #MIN_LABELS} labelled stops on each
 * side. Pure.
 */
public final class Backtest {
  public static final int MIN_LABELS = 200;
  public static final String BASELINE = "deterministic";

  private Backtest() {}

  /** One stop the model scored: its day, its prediction, and its label when it has one. */
  public record Outcome(LocalDate day, Optional<Double> lateProb, Optional<Boolean> late) {}

  public record Week(int isoYear, int isoWeek, int stops, int labelled, Optional<Double> brier) {}

  public enum Verdict {
    BETTER,
    WORSE,
    MIXED,
    NOT_ENOUGH,
    BASELINE
  }

  public record Result(
      String modelLabel, int stops, int labelled, double coverage, Optional<Double> brier, Optional<Double> logLoss,
      Optional<ModelMetrics.Calibration> calibration, Verdict verdict, List<Week> weeks) {}

  public static List<Result> run(Map<String, List<Outcome>> byModel) {
    Map<String, Result> first = new TreeMap<>();
    byModel.forEach((label, outcomes) -> first.put(label, measure(label, outcomes, Verdict.NOT_ENOUGH)));
    Optional<Result> baseline = Optional.ofNullable(first.get(BASELINE));
    List<Result> out = new ArrayList<>();
    for (Result r : first.values()) {
      Verdict v = r.modelLabel().equals(BASELINE) ? Verdict.BASELINE : verdict(r, baseline);
      out.add(new Result(r.modelLabel(), r.stops(), r.labelled(), r.coverage(), r.brier(), r.logLoss(), r.calibration(), v, r.weeks()));
    }
    return out;
  }

  static Verdict verdict(Result candidate, Optional<Result> baseline) {
    if (baseline.isEmpty() || candidate.labelled() < MIN_LABELS || baseline.get().labelled() < MIN_LABELS
        || candidate.brier().isEmpty() || baseline.get().brier().isEmpty()) {
      return Verdict.NOT_ENOUGH;
    }
    boolean brier = candidate.brier().get() < baseline.get().brier().get();
    boolean loss = candidate.logLoss().get() < baseline.get().logLoss().get();
    return brier && loss ? Verdict.BETTER : !brier && !loss ? Verdict.WORSE : Verdict.MIXED;
  }

  private static Result measure(String label, List<Outcome> outcomes, Verdict verdict) {
    List<Scored> scored = new ArrayList<>();
    Map<Integer, List<Outcome>> byWeek = new TreeMap<>();
    for (Outcome o : outcomes) {
      if (o.lateProb().isPresent() && o.late().isPresent()) {
        scored.add(new Scored(o.lateProb().get(), o.late().get()));
      }
      int key = o.day().get(IsoFields.WEEK_BASED_YEAR) * 100 + o.day().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
      byWeek.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
    }
    List<Week> weeks = new ArrayList<>();
    byWeek.forEach((key, week) -> {
      List<Scored> s = week.stream()
          .filter(o -> o.lateProb().isPresent() && o.late().isPresent())
          .map(o -> new Scored(o.lateProb().get(), o.late().get()))
          .toList();
      weeks.add(new Week(key / 100, key % 100, week.size(), s.size(), ModelMetrics.brier(s)));
    });
    double coverage = OutcomeLabel.coverage(scored.size(), outcomes.size());
    return new Result(label, outcomes.size(), scored.size(), coverage, ModelMetrics.brier(scored),
        ModelMetrics.logLoss(scored), ModelMetrics.calibration(scored), verdict, weeks);
  }
}
