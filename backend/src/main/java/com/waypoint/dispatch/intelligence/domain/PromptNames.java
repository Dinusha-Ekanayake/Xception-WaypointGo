package com.waypoint.dispatch.intelligence.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * People's names out of what goes to a language model (R-ML-31). Pure. The names
 * are our own accounts', so this works in any script where a pattern for "a name"
 * cannot: each account's full name and, for a name of two words or more, its first
 * word. Each person becomes {@code [person-N]} in order of first appearance, the
 * same token every time within one prompt, and the tokens are put back in the
 * answer on our side, so the model never sees a name. Masking too much is safe;
 * a common word that is also someone's first name is masked too.
 */
public final class PromptNames {
  static final int MIN_FIRST_NAME = 3;
  private static final Pattern TOKEN = Pattern.compile("\\[person-\\d+]");

  private final Pattern pattern;
  /** Every variant, lower case, to the person it belongs to. */
  private final Map<String, String> owner;

  private PromptNames(Pattern pattern, Map<String, String> owner) {
    this.pattern = pattern;
    this.owner = owner;
  }

  public static PromptNames of(Collection<String> displayNames) {
    Map<String, String> owner = new HashMap<>();
    for (String raw : displayNames) {
      if (raw == null || raw.isBlank()) continue;
      String name = raw.strip().replaceAll("\\s+", " ");
      String person = name.toLowerCase(Locale.ROOT);
      owner.putIfAbsent(person, person);
      String[] words = name.split(" ");
      String first = words[0].replaceAll("[^\\p{L}\\p{M}'-]", "");
      if (words.length > 1 && first.codePointCount(0, first.length()) >= MIN_FIRST_NAME) {
        owner.putIfAbsent(first.toLowerCase(Locale.ROOT), person);
      }
    }
    if (owner.isEmpty()) return new PromptNames(null, owner);
    List<String> variants = new ArrayList<>(owner.keySet());
    variants.sort(Comparator.comparingInt(String::length).reversed());
    StringBuilder alternatives = new StringBuilder();
    for (String v : variants) {
      if (!alternatives.isEmpty()) alternatives.append('|');
      alternatives.append(Pattern.quote(v));
    }
    Pattern p = Pattern.compile(
        "(?<![\\p{L}\\p{M}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{M}\\p{N}])",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    return new PromptNames(p, owner);
  }

  /** One prompt's masking: the same person is the same token across every string masked with it. */
  public Session session() {
    return new Session();
  }

  /** Puts the names back where the answer kept a token; a token it invented stays as it is. */
  public static String restore(String text, Map<String, String> tokens) {
    if (text == null || tokens.isEmpty()) return text;
    Matcher m = TOKEN.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(out, Matcher.quoteReplacement(tokens.getOrDefault(m.group(), m.group())));
    }
    m.appendTail(out);
    return out.toString();
  }

  public final class Session {
    private final Map<String, String> tokenOf = new LinkedHashMap<>();
    private final Map<String, String> original = new LinkedHashMap<>();

    public String mask(String text) {
      if (text == null || pattern == null) return text;
      Matcher m = pattern.matcher(text);
      StringBuilder out = new StringBuilder();
      while (m.find()) {
        String person = owner.get(m.group().toLowerCase(Locale.ROOT));
        String token = tokenOf.computeIfAbsent(person, p -> "[person-" + (tokenOf.size() + 1) + "]");
        // The fullest form written stands for the person when the answer is put back.
        original.merge(token, m.group(), (had, now) -> now.length() > had.length() ? now : had);
        m.appendReplacement(out, Matcher.quoteReplacement(token));
      }
      m.appendTail(out);
      return out.toString();
    }

    /**
     * Masks every string value in facts built of maps, lists and scalars, never a
     * key: a first name such as "Next" must not rename the field it sits in.
     */
    public Object maskValues(Object value) {
      if (value instanceof String s) return mask(s);
      if (value instanceof Map<?, ?> map) {
        Map<Object, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, maskValues(v)));
        return out;
      }
      if (value instanceof Collection<?> list) {
        List<Object> out = new ArrayList<>();
        list.forEach(v -> out.add(maskValues(v)));
        return out;
      }
      return value;
    }

    /** Token to the fullest form of the name written, for {@link #restore}. */
    public Map<String, String> tokens() {
      return Map.copyOf(original);
    }

    /** The people masked so far, for a test or a count. */
    public Set<String> people() {
      return new LinkedHashSet<>(tokenOf.keySet());
    }
  }
}
