package com.waypoint.dispatch.identity.domain.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks a draft policy statement by statement, so the editor can show each
 * problem beside its row before anything is saved (R-IAM-42). Pure. An action
 * must be in the catalogue (a typo would otherwise deny silently forever); a
 * resource must be {@code *} or {@code wpt:<module>[:<type>[:<id>]]}, each part
 * allowed to be {@code *}.
 */
public final class PolicyLint {
  private static final java.util.regex.Pattern RESOURCE =
      java.util.regex.Pattern.compile("\\*|wpt:[a-z*][a-z0-9_*]*(:[A-Za-z0-9_*][A-Za-z0-9_\\-.*]*){0,2}");

  private PolicyLint() {}

  /** @param statement 1 for the first statement; 0 for the document as a whole */
  public record Problem(int statement, String message) {}

  public static List<Problem> check(PolicyDocument document, List<String> catalogue) {
    List<Problem> out = new ArrayList<>();
    int n = 0;
    for (Statement s : document.statements()) {
      n++;
      if (s.actions().isEmpty()) out.add(new Problem(n, "Name at least one action"));
      for (Pattern action : s.actions()) {
        if (catalogue.stream().noneMatch(action::matches)) {
          out.add(new Problem(n, "\"" + action.raw() + "\" is not an action in the catalogue"));
        }
      }
      for (Pattern resource : s.resources()) {
        if (!RESOURCE.matcher(resource.raw()).matches()) {
          out.add(new Problem(n, "\"" + resource.raw() + "\" is not a resource: use * or wpt:<module>:<type>:<id>"));
        }
      }
    }
    return out;
  }
}
