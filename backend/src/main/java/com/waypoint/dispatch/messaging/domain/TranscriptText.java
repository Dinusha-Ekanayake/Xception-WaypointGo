package com.waypoint.dispatch.messaging.domain;

import java.util.Optional;
import java.util.Set;

/**
 * A machine transcript as it may be stored (FF-05): the engine's words in their
 * own script, trimmed, at most {@link #MAX} characters, with the language the
 * engine named when it is one of ours. Never a number or a name the system adds:
 * the transcript is what the engine heard, labelled as such on screen.
 */
public final class TranscriptText {
  public static final int MAX = 4000;
  static final Set<String> LANGUAGES = Set.of("en", "si", "ta");

  private TranscriptText() {}

  /**
   * @param language en, si or ta; empty when the engine named another or none
   * @param unclear the engine said noise or cut-off words leave part of it uncertain (R-ML-37)
   */
  public record Heard(String text, Optional<String> language, boolean unclear) {
    public Heard(String text, Optional<String> language) {
      this(text, language, false);
    }
  }

  /** What to store from the engine's answer; empty when it heard no speech. */
  public static Optional<Heard> heard(String text, String language) {
    return heard(text, language, false);
  }

  /** As above, kept with whether the engine heard it clearly. */
  public static Optional<Heard> heard(String text, String language, boolean unclear) {
    String cleaned = text == null ? "" : text.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
    if (cleaned.isEmpty()) {
      return Optional.empty();
    }
    if (cleaned.length() > MAX) {
      cleaned = cleaned.substring(0, MAX);
    }
    String lang = language == null ? "" : language.strip().toLowerCase(java.util.Locale.ROOT);
    return Optional.of(new Heard(cleaned, LANGUAGES.contains(lang) ? Optional.of(lang) : Optional.empty(), unclear));
  }
}
