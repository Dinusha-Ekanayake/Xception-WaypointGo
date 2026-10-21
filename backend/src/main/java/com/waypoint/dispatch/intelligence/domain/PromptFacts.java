package com.waypoint.dispatch.intelligence.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What may be sent to a language model, and what may come back (roadmap AI-0,
 * R-ML-10). Pure. Only facts of a known shape go: every key on an allow-list,
 * every value a short string, number, true or false, or a short list of them;
 * anything else is dropped. Every string is masked first: phone numbers,
 * national identity numbers, e-mail addresses and links never leave. An answer
 * is used only when every figure in it is one the facts hold.
 */
public final class PromptFacts {
  public static final int MAX_STRING = 300;
  public static final int MAX_LIST = 12;

  private PromptFacts() {}

  /** The explanations the screens build (frontend roles/dispatcher/data/explain.ts), field by field. */
  public enum Kind {
    ORDER(Set.of("headline", "rule", "reason", "stopped", "met", "canPlace", "fits", "refused", "next", "closest",
        "change"),
        Set.of("label", "detail", "where", "why")),
    PLAN(Set.of("headline", "carries", "leftOff", "cannotBeServed", "byHand", "notes", "next", "whatIf", "nowServed",
        "nowDeferred"),
        Set.of("label", "count", "example"));

    final Set<String> keys;
    final Set<String> itemKeys;

    Kind(Set<String> keys, Set<String> itemKeys) {
      this.keys = keys;
      this.itemKeys = itemKeys;
    }

    /** The key a screen asks under: {@code plan:<planId>:...} or {@code order:<planId>:...}. */
    public static Optional<Kind> ofKey(String key) {
      if (key == null) return Optional.empty();
      if (key.startsWith("plan:")) return Optional.of(PLAN);
      if (key.startsWith("order:")) return Optional.of(ORDER);
      return Optional.empty();
    }
  }

  private static final Pattern PHONE = Pattern.compile("(?<![\\w])(?:\\+?94[\\s-]?|0)\\d{2}[\\s-]?\\d{3}[\\s-]?\\d{4}(?![\\w])");
  private static final Pattern NIC = Pattern.compile("(?<![\\w])(?:\\d{9}[VvXx]|\\d{12})(?![\\w])");
  private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
  private static final Pattern LINK = Pattern.compile("(?:https?://|www\\.)\\S+");
  private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.:]\\d+)?");

  /**
   * The version of an instruction (R-ML-27): the first 12 hex digits of its
   * SHA-256. A changed prompt is a new version by construction.
   */
  public static String version(String instruction) {
    try {
      byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
          .digest(instruction.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(hash).substring(0, 12);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every JVM", e);
    }
  }

  /** Masks what identifies a person or leads elsewhere; the rest of the text is kept. */
  public static String mask(String text) {
    String t = LINK.matcher(text).replaceAll("[link]");
    t = EMAIL.matcher(t).replaceAll("[e-mail]");
    t = NIC.matcher(t).replaceAll("[id]");
    t = PHONE.matcher(t).replaceAll("[phone]");
    return t.length() > MAX_STRING ? t.substring(0, MAX_STRING) : t;
  }

  /** The facts that may go, masked; keys and shapes off the list are dropped. */
  public static Map<String, Object> allowed(Kind kind, Map<?, ?> facts) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (facts == null) return out;
    for (Map.Entry<?, ?> e : facts.entrySet()) {
      if (!(e.getKey() instanceof String key) || !kind.keys.contains(key)) continue;
      Object value = clean(kind, e.getValue(), true);
      if (value != null) out.put(key, value);
    }
    return out;
  }

  private static Object clean(Kind kind, Object value, boolean listAllowed) {
    if (value instanceof String s) return mask(s);
    if (value instanceof Number || value instanceof Boolean) return value;
    if (value instanceof List<?> list && listAllowed) {
      List<Object> out = new ArrayList<>();
      for (Object item : list.subList(0, Math.min(list.size(), MAX_LIST))) {
        Object c = item instanceof Map<?, ?> m ? item(kind, m) : clean(kind, item, false);
        if (c != null) out.add(c);
      }
      return out;
    }
    return null;
  }

  private static Map<String, Object> item(Kind kind, Map<?, ?> m) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : m.entrySet()) {
      if (e.getKey() instanceof String k && kind.itemKeys.contains(k)) {
        Object v = clean(kind, e.getValue(), false);
        if (v != null) out.put(k, v);
      }
    }
    return out.isEmpty() ? null : out;
  }

  /** The answer, when every figure in it is one the facts hold; else empty, and the screen keeps the rules' text. */
  public static Optional<String> faithful(String answer, String facts) {
    if (answer == null || answer.isBlank() || answer.length() > 1_500) return Optional.empty();
    Set<String> known = new HashSet<>(figures(facts));
    for (String n : figures(answer)) {
      if (!known.contains(n)) return Optional.empty();
    }
    return Optional.of(answer.strip());
  }

  private static List<String> figures(String text) {
    List<String> out = new ArrayList<>();
    Matcher m = NUMBER.matcher(text);
    while (m.find()) out.add(m.group());
    return out;
  }
}
