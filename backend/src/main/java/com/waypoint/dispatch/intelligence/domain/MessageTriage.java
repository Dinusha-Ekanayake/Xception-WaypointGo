package com.waypoint.dispatch.intelligence.domain;

import java.util.Map;
import java.util.Optional;

/**
 * Jev live on a trip's chat (decision suggestions part 2, R-ML-35). Pure. Which
 * situation of the playbooks a driver's or a store's own words describe, typed,
 * spoken or a report sent as "other". The options are {@link ReportTriage}'s, so
 * a class read from a message and a report's own type mean the same thing; "none
 * of these" is an answer too, and most chat is that.
 *
 * <p>A class counts on the trip's Next step card only at or above the live
 * confidence (P-39); below it the message adds nothing, and the card never
 * pretends it was read.
 */
public final class MessageTriage {
  public static final String INSTRUCTIONS =
      "A driver or a store manager wrote this on a delivery trip's chat. Does it report one of these situations?"
          + " Most chat reports none of them.";

  /** The answer that reports nothing. */
  public static final String NONE = "OTHER";

  private MessageTriage() {}

  /** The options in an order fixed by the message, so a retry asks the same question. */
  public static Map<String, String> shuffled(long seed) {
    return ReportTriage.shuffled(seed);
  }

  /** The situation a class gives the card: one of the playbooks', at or above the confidence; else none. */
  public static Optional<Playbooks.Situation> signal(String situation, double confidence, double minConfidence) {
    if (situation == null || NONE.equals(situation) || confidence < minConfidence) {
      return Optional.empty();
    }
    return Playbooks.ofReport(situation).or(() -> {
      try {
        return Optional.of(Playbooks.Situation.valueOf(situation));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }).filter(s -> ReportTriage.OPTIONS.containsKey(s.name()));
  }
}
