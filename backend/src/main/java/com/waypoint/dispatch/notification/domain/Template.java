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
 *
 * <p>Values are written as people read them (GLOSSARY.md): an ISO date as
 * "Tue 6 Oct", a time without its seconds as "05:30", and a count of one with
 * its noun in the singular ("1 stop", not "1 stops").
 */
public final class Template {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
  private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Pattern CLOCK_SECONDS = Pattern.compile("(\\d{2}:\\d{2}):\\d{2}(?:\\.\\d+)?");
  private static final Pattern ONE_PLURAL =
      Pattern.compile("\\b1 (stop|unit|trip|order|item|vehicle|minute)s\\b");
  private static final java.time.format.DateTimeFormatter DAY =
      java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH);
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
      m.appendReplacement(out, Matcher.quoteReplacement(readable(value)));
    }
    m.appendTail(out);
    return new Rendered(ONE_PLURAL.matcher(out.toString().strip()).replaceAll("1 $1"), missing);
  }

  /** A fact as a person reads it: dates by day name, clock times to the minute. */
  static String readable(String value) {
    if (ISO_DATE.matcher(value).matches()) {
      try {
        return java.time.LocalDate.parse(value).format(DAY);
      } catch (java.time.format.DateTimeParseException e) {
        return value;
      }
    }
    Matcher clock = CLOCK_SECONDS.matcher(value);
    return clock.matches() ? clock.group(1) : value;
  }
}
