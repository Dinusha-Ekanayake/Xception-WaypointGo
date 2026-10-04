package com.waypoint.dispatch.referencedata.domain;

/**
 * One reason an import was refused.
 *
 * @param rule the check that failed, matching the names in FOUNDATION-PLAN 1.7
 * @param subject what failed, for example an outlet id, so the fix is obvious
 */
public record ReferenceViolation(String rule, String subject, String detail) {

  @Override
  public String toString() {
    return rule + " [" + subject + "]: " + detail;
  }
}
