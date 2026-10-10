package com.waypoint.dispatch.platform.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Demo sign-in: the sign-in page offers these accounts to pick from, and the
 * loader's PIN screen offers the PIN, so a judge does not copy credentials. Off
 * unless both the accounts and the password are set, and shown only while an
 * administrator has demo mode on. The server offers an account only while the
 * password still signs it in, so an account whose password was changed is never
 * listed and its password never leaves; and only field roles are offered, never
 * an administrator (DemoSignInQuery).
 *
 * @param accounts the emails to offer, in order ({@code DEMO_SIGN_IN_ACCOUNTS}, comma separated)
 * @param password the shared demo password ({@code DEMO_SIGN_IN_PASSWORD}), the seed password
 * @param pin the loaders' demo PIN ({@code DEMO_SIGN_IN_PIN}); blank offers none
 * @param until the last day it is offered, Colombo time ({@code DEMO_SIGN_IN_UNTIL}, 2026-10-31);
 *     blank has no end. Set it to the judging window so it ends without anyone remembering to.
 */
@ConfigurationProperties(prefix = "app.demo.sign-in")
public record DemoSignInProperties(
    @DefaultValue("") List<String> accounts,
    @DefaultValue("") String password,
    @DefaultValue("") String pin,
    @DefaultValue("") String until) {

  public DemoSignInProperties {
    if (until != null && !until.isBlank()) {
      try {
        java.time.LocalDate.parse(until.trim());
      } catch (java.time.format.DateTimeParseException e) {
        throw new IllegalArgumentException("DEMO_SIGN_IN_UNTIL must be a date such as 2026-10-31");
      }
    }
  }

  public boolean enabled() {
    return accounts != null && accounts.stream().anyMatch(a -> !a.isBlank()) && password != null && !password.isBlank();
  }

  /** False once the last day has passed in Colombo; true with no end set. */
  public boolean openOn(java.time.LocalDate today) {
    return until == null || until.isBlank() || !today.isAfter(java.time.LocalDate.parse(until.trim()));
  }
}
