package com.waypoint.dispatch.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.waypoint.dispatch.demo.contract.DemoView;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.DemoSignInQuery;
import com.waypoint.dispatch.identity.application.OperatorRegistry;
import com.waypoint.dispatch.identity.infrastructure.Argon2PasswordHasher;
import com.waypoint.dispatch.platform.config.DemoSignInProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.support.TestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Demo sign-in offers only what the demo password and PIN still open, and only in demo mode. */
@SpringBootTest
@ExtendWith(TestDatabase.class)
class DemoSignInIntegrationTest {
  private static final String PASSWORD = "DemoPassword2026!";

  @Autowired Migrator migrator;
  @Autowired AccountAdminUseCase accounts;
  @Autowired OperatorRegistry operators;
  @Autowired Argon2PasswordHasher hasher;
  @Autowired Database db;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @Test
  void offersOnlyAccountsThePasswordOpensAndThePinOnlyWhileALoaderOpensWithIt() {
    migrator.migrate();
    String s = UUID.randomUUID().toString().substring(0, 8);
    String depot = "Peliyagoda";
    String dispatcher = "ds-dispatcher-" + s + "@test.local";
    String admin = "ds-admin-" + s + "@test.local";
    String dock = "ds-dock-" + s + "@test.local";
    String crew = "ds-crew-" + s + "@test.local";
    accounts.createAccount(dispatcher, "Demo dispatcher", PASSWORD, "dispatcher");
    accounts.createAccount(admin, "Demo admin", PASSWORD, "admin");
    accounts.createAccount(dock, "Demo dock", PASSWORD, "loader");
    accounts.createAccount(crew, "Demo crew", PASSWORD, "loader");
    accounts.grantDepot(dock, depot);
    accounts.grantDepot(crew, depot);
    String code = "LDR-" + String.format("%05d", Math.floorMod(s.hashCode(), 100000));
    operators.setPin(crew, "2468", code);
    // The production administrator: its password was changed, so it is never offered.
    db.asSystem(ModuleRole.IAM, () -> db.update(
        "UPDATE iam.users SET password_hash = ? WHERE email = ?", hasher.hash("Rotated-" + s), admin));

    AtomicBoolean demoOn = new AtomicBoolean(true);
    var query = new DemoSignInQuery(db, hasher,
        new DemoSignInProperties(List.of(dispatcher, admin, dock, "nobody-" + s + "@test.local"), PASSWORD, "2468", false, ""),
        () -> new DemoView(demoOn.get(), Instant.now(), 0, true, 2000, 60000, 1, 1), Clock.system());

    var offered = query.view().orElseThrow();
    // An administrator is left out unless DEMO_SIGN_IN_ADMIN asks for it (below).
    assertEquals(List.of(dispatcher, dock), offered.accounts().stream().map(DemoSignInQuery.Account::email).toList());
    assertEquals(List.of("dispatcher", "loader"), offered.accounts().stream().map(DemoSignInQuery.Account::role).toList());
    assertEquals(PASSWORD, offered.password());
    assertEquals(Optional.of("2468"), offered.pin());

    var wrongPin = new DemoSignInQuery(db, hasher, new DemoSignInProperties(List.of(dock), PASSWORD, "1357", false, ""),
        () -> new DemoView(true, Instant.now(), 0, true, 2000, 60000, 1, 1), Clock.system());
    assertEquals(Optional.empty(), wrongPin.view().orElseThrow().pin(), "a PIN that opens no loader is never offered");

    String seededAdmin = "ds-admin-seeded-" + s + "@test.local";
    accounts.createAccount(seededAdmin, "Seeded admin", PASSWORD, "admin");
    var adminListed = new DemoSignInQuery(db, hasher, new DemoSignInProperties(List.of(seededAdmin), PASSWORD, "", false, ""),
        () -> new DemoView(true, Instant.now(), 0, true, 2000, 60000, 1, 1), Clock.system());
    assertTrue(adminListed.view().isEmpty(), "an administrator is not offered unless asked for");
    var adminAsked = new DemoSignInQuery(db, hasher, new DemoSignInProperties(List.of(seededAdmin, admin), PASSWORD, "", true, ""),
        () -> new DemoView(true, Instant.now(), 0, true, 2000, 60000, 1, 1), Clock.system());
    var asked = adminAsked.view().orElseThrow();
    assertEquals(List.of(seededAdmin, admin), asked.accounts().stream().map(DemoSignInQuery.Account::email).toList(),
        "asked for, an administrator is listed whatever its password, as the list never carries it");
    assertTrue(asked.accounts().stream().noneMatch(DemoSignInQuery.Account::fillsPassword));
    assertNull(asked.password(), "with only administrators listed, no password leaves");
    assertTrue(offered.accounts().stream().allMatch(DemoSignInQuery.Account::fillsPassword));

    var ended = new DemoSignInQuery(db, hasher, new DemoSignInProperties(List.of(dispatcher), PASSWORD, "", false, "2020-01-01"),
        () -> new DemoView(true, Instant.now(), 0, true, 2000, 60000, 1, 1), Clock.system());
    assertTrue(ended.view().isEmpty(), "nothing is offered after DEMO_SIGN_IN_UNTIL");
    assertThrows(IllegalArgumentException.class, () -> new DemoSignInProperties(List.of(dispatcher), PASSWORD, "", false, "31/10/2026"));

    demoOn.set(false);
    assertTrue(query.view().isEmpty(), "nothing is offered once demo mode is off");
    var unconfigured = new DemoSignInQuery(db, hasher, new DemoSignInProperties(List.of(), "", "", false, ""),
        () -> new DemoView(true, Instant.now(), 0, true, 2000, 60000, 1, 1), Clock.system());
    assertTrue(unconfigured.view().isEmpty(), "off unless configured");
  }
}
