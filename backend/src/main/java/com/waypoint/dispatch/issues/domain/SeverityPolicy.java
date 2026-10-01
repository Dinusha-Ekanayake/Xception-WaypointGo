package com.waypoint.dispatch.issues.domain;

import com.waypoint.dispatch.issues.contract.IssueViews.IssueSeverity;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueType;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How serious an issue raised by the system is, and how long an unassigned issue
 * may wait before it escalates. Values come from effective-dated
 * {@code issues.parameters} rows; a missing one refuses rather than defaulting
 * (POL-10), so the policy is complete or it does not exist.
 *
 * <p>Escalation is an escalation timer in the EDGE-CASES sense: "waiting forever"
 * is not a state. A person raising an issue chooses its severity; this decides
 * only for issues the system raises from another module's event.
 */
public record SeverityPolicy(Map<IssueType, IssueSeverity> defaults, Map<IssueSeverity, Duration> deadlines) {
  public static final String DEFAULT_PREFIX = "default.severity.";
  public static final String DEADLINE_PREFIX = "escalation.minutes.";

  public SeverityPolicy {
    defaults = Map.copyOf(defaults);
    deadlines = Map.copyOf(deadlines);
  }

  public static SeverityPolicy from(Map<String, String> parameters) {
    Map<IssueType, IssueSeverity> defaults = new EnumMap<>(IssueType.class);
    for (IssueType type : IssueType.values()) {
      String value = require(parameters, DEFAULT_PREFIX + type.name());
      try {
        defaults.put(type, IssueSeverity.valueOf(value.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException e) {
        throw refused(DEFAULT_PREFIX + type.name() + " is not a severity: " + value);
      }
    }
    Map<IssueSeverity, Duration> deadlines = new EnumMap<>(IssueSeverity.class);
    for (IssueSeverity severity : IssueSeverity.values()) {
      String value = require(parameters, DEADLINE_PREFIX + severity.name());
      try {
        long minutes = Long.parseLong(value.trim());
        if (minutes <= 0) {
          throw refused(DEADLINE_PREFIX + severity.name() + " must be positive");
        }
        deadlines.put(severity, Duration.ofMinutes(minutes));
      } catch (NumberFormatException e) {
        throw refused(DEADLINE_PREFIX + severity.name() + " is not a whole number of minutes: " + value);
      }
    }
    return new SeverityPolicy(defaults, deadlines);
  }

  public IssueSeverity defaultFor(IssueType type) {
    return defaults.get(type);
  }

  public Duration deadline(IssueSeverity severity) {
    return deadlines.get(severity);
  }

  private static String require(Map<String, String> parameters, String key) {
    String value = parameters.get(key);
    if (value == null || value.isBlank()) {
      throw refused("issue parameter " + key + " has no value in force");
    }
    return value;
  }

  private static DomainException refused(String message) {
    return new DomainException(ErrorCode.CONSTRAINT_VIOLATED, message, List.of("POL-10"));
  }
}
