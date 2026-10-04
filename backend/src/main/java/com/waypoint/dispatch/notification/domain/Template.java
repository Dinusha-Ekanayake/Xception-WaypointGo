package com.waypoint.dispatch.notification.domain;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills {@code {name}} placeholders from an event's facts.
 *
 * <p>A placeholder the event does not supply renders as {@code -} and is
 * reported, never thrown: a routing row naming a fact an event lacks is a data
 * mistake to count and fix, not a reason to drop the notification.
 */
public final class Template {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
  static final String MISSING = "-";

  private Template() {}

  public record Rendered(String text, Set<String> missing) {
    public Rendered {
      missing = Set.copyOf(missing);
    }
  }

  public static Rendered render(String template, Map<String, String> facts) {
    Set<String> missing = new LinkedHashSet<>();
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = facts.get(m.group(1));
      if (value == null || value.isBlank()) {
        missing.add(m.group(1));
        value = MISSING;
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return new Rendered(out.toString().strip(), missing);
  }
}
