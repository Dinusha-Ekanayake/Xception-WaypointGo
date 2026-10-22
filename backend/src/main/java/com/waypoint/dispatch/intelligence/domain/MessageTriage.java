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
      "A driver, a store manager or the dispatcher wrote this on a delivery trip's chat. Pick what it does."
          + " If it reports a problem on the road or at a store, pick the problem.";

  /** The answer that reports nothing this list names. */
  public static final String NONE = "OTHER";

  /** Unrelated, abusive or nonsense: it gets no suggested reply and opens nothing (the off-topic guard). */
  public static final String OFF_TOPIC = "OFF_TOPIC";

  /**
   * What a chat message does, beside the problems of {@link ReportTriage}: what
   * the suggested replies answer (R-ML-43). Plain words, keyed by name.
   */
  public static final Map<String, String> ACTS = acts();

  /** Every option Jev picks from for a chat message: the problems, the acts, off topic and none. */
  public static final Map<String, String> OPTIONS = options();

  private static Map<String, String> acts() {
    Map<String, String> a = new java.util.LinkedHashMap<>();
    a.put("ASKS_CAN_WAIT", "Asks whether the store can still receive later, or wait for a late delivery.");
    a.put("ASKS_WHEN", "Asks when the vehicle or the delivery will arrive.");
    a.put("SAYS_YES", "Agrees, accepts or says yes.");
    a.put("SAYS_NO", "Refuses or says they cannot.");
    a.put("CAN_RECEIVE_LATER", "Says the store can receive later today, perhaps at a time.");
    a.put("DELIVER_NEXT_DAY", "Asks to deliver on the next delivery day or another day.");
    a.put("STORE_OPEN_NOW", "Says the store is open now or someone is there to receive.");
    a.put("DOCK_CLEAR", "Says the unloading area or the dock is clear now.");
    a.put("THANKS", "Thanks or acknowledges; nothing to answer.");
    return java.util.Collections.unmodifiableMap(a);
  }

  private static Map<String, String> options() {
    Map<String, String> o = new java.util.LinkedHashMap<>();
    ReportTriage.OPTIONS.forEach((k, v) -> {
      if (!NONE.equals(k)) o.put(k, v);
    });
    o.putAll(ACTS);
    o.put(OFF_TOPIC, "Not about this delivery: unrelated, abusive or nonsense.");
    o.put(NONE, "Something else about the delivery: none of the above.");
    return java.util.Collections.unmodifiableMap(o);
  }

  private MessageTriage() {}

  /** The options in an order fixed by the message, so a retry asks the same question. */
  public static Map<String, String> shuffled(long seed) {
    java.util.List<String> keys = new java.util.ArrayList<>(OPTIONS.keySet());
    java.util.Collections.sort(keys);
    java.util.Collections.shuffle(keys, new java.util.Random(seed));
    Map<String, String> out = new java.util.LinkedHashMap<>();
    keys.forEach(k -> out.put(k, OPTIONS.get(k)));
    return out;
  }

  /** Whether a label names a problem of the room, not an act. */
  public static boolean isProblem(String label) {
    return label != null && !NONE.equals(label) && ReportTriage.OPTIONS.containsKey(label);
  }

  /**
   * What steers the suggested replies (R-ML-43): the label when it was read sure
   * enough from clear words; empty otherwise, and the replies fall back to the
   * conversation's rules. Off topic is a label too: it asks for no reply.
   */
  public static Optional<String> steering(String label, double confidence, double minConfidence, boolean unclear) {
    if (label == null || NONE.equals(label) || unclear || confidence < minConfidence) return Optional.empty();
    // Off topic takes every reply away, so a wrong one costs more: it needs more certainty.
    if (OFF_TOPIC.equals(label) && confidence < SURE_OFF_TOPIC) return Optional.empty();
    return OPTIONS.containsKey(label) ? Optional.of(label) : Optional.empty();
  }

  /**
   * Above this, Jev reading words as off topic hides the suggested replies. Measured
   * on the chat set: spam, abuse and markup read at 0.96 and above, while a real
   * Singlish answer was once read off topic at 0.5.
   */
  public static final double SURE_OFF_TOPIC = 0.8;

  /** How a message read stands (R-ML-37). */
  public enum Reading { SIGNAL, REVIEW, NOTHING }

  /** Above this, Jev saying "none of these" about a noisy voice note is trusted without a review. */
  public static final double SURE_NOTHING = 0.9;

  /**
   * What a reading does: counts on the room, goes to the dispatcher to confirm, or
   * adds nothing. Words heard unclearly are always confirmed, unless Jev is very
   * sure they report nothing; a situation read below the confidence is confirmed
   * rather than dropped, so a real problem said unsurely is not lost. What the
   * dispatcher confirmed is what counts.
   */
  public static Reading reading(String situation, double confidence, double minConfidence, boolean unclear,
      Optional<String> confirmed) {
    if (confirmed.isPresent()) {
      return NONE.equals(confirmed.get()) ? Reading.NOTHING : Reading.SIGNAL;
    }
    if (situation == null) return Reading.NOTHING;
    if (unclear) {
      // Words in doubt are confirmed when they may be a problem; chat Jev is very sure of is left alone.
      return isProblem(situation) || confidence < SURE_NOTHING ? Reading.REVIEW : Reading.NOTHING;
    }
    // An act, off topic or none: nothing for the room; it steers the replies instead.
    if (!isProblem(situation)) return Reading.NOTHING;
    if (signal(situation, confidence, minConfidence).isPresent()) return Reading.SIGNAL;
    return signal(situation, 1.0, 0.0).isPresent() ? Reading.REVIEW : Reading.NOTHING;
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
