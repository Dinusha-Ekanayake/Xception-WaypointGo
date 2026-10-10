package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.demo.contract.DemoRuntime;
import com.waypoint.dispatch.identity.infrastructure.Argon2PasswordHasher;
import com.waypoint.dispatch.platform.config.DemoSignInProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What the sign-in page offers in a demo: the configured accounts that the demo
 * password still signs in, and the loaders' PIN while it still opens a loader.
 * Nothing is offered unless {@link DemoSignInProperties#enabled()} and demo mode
 * is on. The only secrets that can leave are the configured demo password and
 * PIN, and only while they work, never a password the server does not hold.
 *
 * <p>Field roles are offered; an administrator only when {@code DEMO_SIGN_IN_ADMIN}
 * is on, because it changes accounts and policies and the list is public. An
 * auditor or operations account, or one holding several roles, is never offered.
 *
 * <p>Checking a password costs an Argon2 verification, and the read is public,
 * so the answer is kept for {@link #KEEP} rather than worked out per request.
 */
@Component
public class DemoSignInQuery {
  static final Duration KEEP = Duration.ofMinutes(5);

  /** The roles a demo may offer: the four field applications, never one that administers or audits. */
  static final java.util.Set<String> OFFERED_ROLES = java.util.Set.of("dispatcher", "driver", "store_manager", "loader");

  public record Account(String email, String name, String role) {}

  public record DemoSignIn(List<Account> accounts, String password, Optional<String> pin) {}

  private record Kept(Instant at, Optional<DemoSignIn> answer) {}

  private final Database database;
  private final Argon2PasswordHasher hasher;
  private final DemoSignInProperties properties;
  private final DemoRuntime runtime;
  private final Clock clock;
  private volatile Kept kept;

  public DemoSignInQuery(
      Database database, Argon2PasswordHasher hasher, DemoSignInProperties properties, DemoRuntime runtime, Clock clock) {
    this.database = database;
    this.hasher = hasher;
    this.properties = properties;
    this.runtime = runtime;
    this.clock = clock;
  }

  public Optional<DemoSignIn> view() {
    Instant now = clock.realTime().now();
    if (!properties.enabled() || !properties.openOn(now.atZone(Clock.OPERATING_ZONE).toLocalDate()) || !runtime.view().enabled()) {
      return Optional.empty();
    }
    Kept current = kept;
    if (current == null || current.at().plus(KEEP).isBefore(now)) {
      current = new Kept(now, work());
      kept = current;
    }
    return current.answer();
  }

  private Optional<DemoSignIn> work() {
    return database.asModule(ModuleRole.IAM, null, () -> {
      List<Account> offered = new ArrayList<>();
      for (String raw : properties.accounts()) {
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) continue;
        Map<String, Object> user = database.queryOne(
            "SELECT u.password_hash, u.display_name,"
                + " (SELECT array_agg(r.role_code ORDER BY r.role_code)::text FROM iam.user_roles r WHERE r.user_id = u.user_id) AS roles"
                + " FROM iam.users u WHERE u.email = ? AND u.is_active",
            email);
        if (user == null || user.get("roles") == null) continue;
        List<String> roles = List.of(String.valueOf(user.get("roles")).replaceAll("[{}]", "").split(","));
        // One role, a field one, or an administrator when asked for; nothing that audits.
        boolean offeredRole = OFFERED_ROLES.contains(roles.get(0)) || (properties.admin() && "admin".equals(roles.get(0)));
        if (roles.size() != 1 || !offeredRole) continue;
        if (!hasher.matches(properties.password(), String.valueOf(user.get("password_hash")))) continue;
        offered.add(new Account(email, String.valueOf(user.get("display_name")), roles.get(0)));
      }
      if (offered.isEmpty()) {
        return Optional.<DemoSignIn>empty();
      }
      return Optional.of(new DemoSignIn(List.copyOf(offered), properties.password(), pinThatOpens(offered)));
    });
  }

  /**
   * The demo PIN while it opens a loader on the crew of an offered dock account
   * (the names its PIN screen shows); any other PIN stays unknown.
   */
  private Optional<String> pinThatOpens(List<Account> offered) {
    String pin = properties.pin() == null ? "" : properties.pin().trim();
    List<String> docks = offered.stream().filter(a -> OperatorRegistry.LOADER_ROLE.equals(a.role())).map(Account::email).toList();
    if (pin.isEmpty() || docks.isEmpty()) return Optional.empty();
    String marks = String.join(", ", java.util.Collections.nCopies(docks.size(), "?"));
    List<Object> params = new ArrayList<>(List.of(OperatorRegistry.LOADER_ROLE));
    params.addAll(docks);
    for (Map<String, Object> row : database.query(
        "SELECT DISTINCT u.user_id, u.pin_hash FROM iam.users u"
            + " JOIN iam.user_roles r ON r.user_id = u.user_id AND r.role_code = ?"
            + " JOIN iam.user_depot_access d ON d.user_id = u.user_id"
            + " WHERE u.is_active AND u.pin_hash IS NOT NULL AND d.depot_code IN ("
            + "   SELECT a.depot_code FROM iam.user_depot_access a JOIN iam.users dock ON dock.user_id = a.user_id"
            + "   WHERE dock.email IN (" + marks + "))",
        params.toArray())) {
      if (hasher.matches(pin, String.valueOf(row.get("pin_hash")))) return Optional.of(pin);
    }
    return Optional.empty();
  }
}
