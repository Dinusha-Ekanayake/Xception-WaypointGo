package com.waypoint.dispatch.messaging.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * What of a typed message is spoken, and in which language (R-MSG-10). Pure.
 * The language comes from the script, as on the phone: a Sinhala letter means
 * Sinhala, a Tamil letter Tamil, else English. The same text in the same
 * language is spoken once and kept, so a reply sent a hundred times costs one.
 */
public final class SpeechText {
  public static final int MAX = 600;

  private SpeechText() {}

  public static String languageOf(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c >= '඀' && c <= '෿') return "si";
      if (c >= '஀' && c <= '௿') return "ta";
    }
    return "en";
  }

  /** The text as it is spoken: separators become pauses, and nothing too long. Empty when there is nothing to say. */
  public static Optional<String> spoken(String body) {
    if (body == null) return Optional.empty();
    String t = body.replaceAll("\\s*\u00B7\\s*", ", ").replaceAll("\\s+", " ").strip();
    if (t.isEmpty()) return Optional.empty();
    return Optional.of(t.length() > MAX ? t.substring(0, MAX) : t);
  }

  /** The key a spoken text is kept under: its SHA-256 and its language. */
  public static String key(String spoken) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(spoken.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
