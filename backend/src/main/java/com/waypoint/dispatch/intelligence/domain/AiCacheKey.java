package com.waypoint.dispatch.intelligence.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * The key under which an AI answer is kept and served again (R-ML-47). Pure.
 * An answer is reused only for the same question asked the same way: the same
 * purpose, prompt version and model, the same scope, and the same masked words
 * once case, spacing and punctuation are set aside. Never "similar" words: in
 * this domain "we can wait" and "we can't wait" are near neighbours with
 * opposite answers. Only the hash is kept, never the words.
 */
public final class AiCacheKey {
  /** How long a reading of chat words stands: the words mean the same next month. */
  public static final Duration READING = Duration.ofDays(30);
  /** How long a drafted reply stands: what a depot answers changes. */
  public static final Duration DRAFT = Duration.ofDays(7);
  /** How long "no draft passed" stands, so a refused question is not asked all day. */
  public static final Duration NO_DRAFT = Duration.ofDays(1);

  private AiCacheKey() {}

  /**
   * Words as they are compared: Unicode-normalised, lower case, letters, digits,
   * the masks' brackets and single spaces only. "No!!", " no " and "NO." are one.
   */
  public static String normalise(String text) {
    if (text == null) return "";
    String t = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    // Keep letters (any script, with their marks), digits and the masks' [ ] and -; the rest is spacing.
    t = t.replaceAll("[^\\p{L}\\p{M}\\p{N}\\[\\]\\-]+", " ");
    return t.strip().replaceAll(" +", " ");
  }

  /**
   * A chat message's reading: its normalised words, who wrote it, and what the
   * line just before it was read as (not its words), so "no" after "can you
   * wait?" and "no" after "is the dock clear?" are kept apart, while the same
   * reply to the same kind of line is asked once across every chat.
   */
  public static String reading(String promptVersion, String model, String authorRole, String maskedText,
      List<String> earlierReadings) {
    return hash("reading", promptVersion, model, "*", authorRole, String.join(",", earlierReadings), normalise(maskedText));
  }

  /** A drafted reply: kept per depot, so one depot's answer never shows in another's chat. */
  public static String draft(String promptVersion, String model, String depotCode, String authorRole, String maskedText,
      List<String> earlierReadings) {
    return hash("draft", promptVersion, model, depotCode, authorRole, String.join(",", earlierReadings), normalise(maskedText));
  }

  private static String hash(String... parts) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      for (String p : parts) {
        byte[] b = (p == null ? "" : p).getBytes(StandardCharsets.UTF_8);
        // Length-prefixed, so no two different lists of parts join into the same bytes.
        sha.update((b.length + ":").getBytes(StandardCharsets.UTF_8));
        sha.update(b);
      }
      return HexFormat.of().formatHex(sha.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
    }
  }
}
