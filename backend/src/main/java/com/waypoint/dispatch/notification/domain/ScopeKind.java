package com.waypoint.dispatch.notification.domain;

import java.util.Locale;

/** Where a recipient must hold scope: the scope types Identity routes by. */
public enum ScopeKind {
  OUTLET,
  DEPOT,
  VEHICLE;

  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static ScopeKind parse(String code) {
    return valueOf(code.toUpperCase(Locale.ROOT));
  }
}
