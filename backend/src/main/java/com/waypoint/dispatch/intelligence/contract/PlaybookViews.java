package com.waypoint.dispatch.intelligence.contract;

import java.util.List;
import java.util.Optional;

/**
 * Issue #269 and docs/issues/decision-suggestions/PLAN.md: what to do about a
 * situation on the road, read by Live's "Needs you" and by the trip's chat.
 */
public final class PlaybookViews {
  private PlaybookViews() {}

  /** The dispatcher records which suggestions were shown and which, if any, was taken. */
  public static final String RECORD = "ml:RecordSuggestion";

  public record MessageView(String to, String template) {}

  /** One situation's steps and ready message; {@code message} is empty when there is nobody useful to write to. */
  public record PlaybookView(String situation, List<String> steps, Optional<MessageView> message) {}

  /**
   * @param chance 0 to 1, summing to 1 over the suggestions offered
   * @param chosenTimes once chances are learned: how often this was taken here, of {@code shownTimes} times shown
   */
  public record SuggestionView(
      String action, double chance, Optional<String> warning, Optional<Integer> chosenTimes,
      Optional<Integer> shownTimes) {}

  /**
   * The suggestions for one stop in one situation.
   *
   * @param basis {@code rules} until learned chances exist (PLAN, "How it works", 3)
   * @param version the catalogue's version, sent back when the dispatcher decides
   */
  public record SuggestionsView(
      String situation, String deliveryId, String basis, int version, List<SuggestionView> suggestions,
      PlaybookView playbook) {}
}
