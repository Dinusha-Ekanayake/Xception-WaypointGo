package com.waypoint.dispatch.identity.domain.policy;

/** Allow or Deny. A Deny that matches always wins, whatever else allows it. */
public enum Effect {
  ALLOW,
  DENY;

  public static Effect parse(String value) {
    return valueOf(value.trim().toUpperCase());
  }
}
