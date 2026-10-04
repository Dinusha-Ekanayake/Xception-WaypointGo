package com.waypoint.dispatch.identity.domain.policy;

/**
 * A string with optional {@code *} wildcards, used for both actions and
 * resources.
 *
 * <p>Deliberately not a regular expression. Policy authors write
 * {@code order:*} and {@code wpt:ref:outlet:*}, and turning user input into a
 * regex invites both surprises and catastrophic backtracking.
 */
public record Pattern(String raw) {

  public static final Pattern ANY = new Pattern("*");

  public boolean matches(String candidate) {
    if (candidate == null) {
      return false;
    }
    return matches(raw, 0, candidate, 0);
  }

  /** Greedy wildcard match with backtracking, iterative on the candidate. */
  private static boolean matches(String pattern, int p, String text, int t) {
    int star = -1;
    int mark = 0;
    while (t < text.length()) {
      if (p < pattern.length() && pattern.charAt(p) == '*') {
        star = p++;
        mark = t;
      } else if (p < pattern.length() && pattern.charAt(p) == text.charAt(t)) {
        p++;
        t++;
      } else if (star >= 0) {
        p = star + 1;
        t = ++mark;
      } else {
        return false;
      }
    }
    while (p < pattern.length() && pattern.charAt(p) == '*') {
      p++;
    }
    return p == pattern.length();
  }

  @Override
  public String toString() {
    return raw;
  }
}
