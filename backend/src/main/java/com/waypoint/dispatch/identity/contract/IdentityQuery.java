package com.waypoint.dispatch.identity.contract;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask Identity.
 *
 * <p>Effective access is policy AND scope (R-IAM-09). {@link #permits} answers
 * the policy half; the scope half is enforced in SQL by row-level security, so a
 * module never widens reach by filtering in application code.
 */
public interface IdentityQuery {

  /** @param resource {@code wpt:<module>:<type>:<id>}, or {@code null} for an unscoped request */
  boolean permits(UUID userId, String action, String resource);

  ScopeView scopeOf(UUID userId);

  /**
   * The vehicle a driver is assigned to on a date. Driver scope is temporal:
   * yesterday's driver cannot post today (R-IAM-13).
   */
  Optional<String> driverVehicleOn(UUID userId, LocalDate date);

  /**
   * Active accounts with a role and a scope, for Notification to route to.
   *
   * @param scopeType {@code depot}, {@code outlet} or {@code vehicle}
   */
  List<UUID> recipientsFor(String roleCode, String scopeType, String scopeId);

  record ScopeView(
      UUID userId, List<String> roles, List<String> depotCodes, List<String> outletIds) {

    public ScopeView {
      roles = List.copyOf(roles);
      depotCodes = List.copyOf(depotCodes);
      outletIds = List.copyOf(outletIds);
    }
  }
}
