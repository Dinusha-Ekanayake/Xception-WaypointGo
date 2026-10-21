package com.waypoint.dispatch.intelligence.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Where the language models and Jev fail (R-ML-27): the administrator's AI health
 * screen. Counts and masked inputs only; never an unmasked fact.
 */
public final class AiHealthViews {
  private AiHealthViews() {}

  /** Administrators only: it reaches every depot (R-IAM, a catalogue row in 20261021T1700). */
  public static final String READ = "ml:ReadAiHealth";

  /** One use of a model on one depot day: how each call ended and how long it took. */
  public record CallDayView(
      String purpose, LocalDate day, long used, long unfaithful, long failed, long capped, int p50Millis,
      int p95Millis) {}

  /** A call that was not used, newest first, with the masked facts that were sent. */
  public record FailureView(
      Instant at, String purpose, String outcome, String model, Optional<String> promptVersion, int millis,
      String sentFacts) {}

  /** Jev's shadow guesses against the real issue type, per prompt version and model. */
  /** @param source issue or report (R-ML-33): what Jev was asked about under that prompt */
  public record JevVersionView(Optional<String> promptVersion, String model, long answered, long agreed, String source) {}

  public record JevView(long pending, long answered, long agreed, long failed, List<JevVersionView> byVersion) {}

  /** Suggested decisions per situation: decided cases, how many took a chip, how many took the first. */
  public record SuggestionUseView(String situation, long decided, long chosen, long chosenFirst) {}

  public record AiHealthView(
      int days, List<CallDayView> calls, List<FailureView> failures, JevView jev,
      List<SuggestionUseView> suggestions) {}
}
