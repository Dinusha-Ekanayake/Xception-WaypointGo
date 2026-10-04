package com.waypoint.dispatch.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.demo.application.DemoSettingsQuery;
import com.waypoint.dispatch.demo.application.ResetDayJob;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandBus;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.support.TestDatabase;
import java.time.Instant;
import java.time.LocalDate;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ExtendWith(TestDatabase.class)
class DemoIntegrationTest {
  @Autowired Migrator migrator;
  @Autowired AccountAdminUseCase accounts;
  @Autowired CommandBus bus;
  @Autowired ObjectMapper json;
  @Autowired DemoSettingsQuery settings;
  @Autowired Database db;
  @Autowired Clock clock;
  @Autowired ResetDayJob resetJob;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @Test
  void runtimeControlsAreVersionedAuditedAndAdminOnly() {
    migrator.migrate();
    String suffix = UUID.randomUUID().toString();
    Actor admin = Actor.user(accounts.createAccount(
        "demo-admin-" + suffix + "@test.local", "Demo admin", "DemoPassword2026!", "admin"));
    Actor driver = Actor.user(accounts.createAccount(
        "demo-driver-" + suffix + "@test.local", "Demo driver", "DemoPassword2026!", "driver"));
    var initial = settings.view();
    assertFalse(initial.enabled());
    Command enable = command("Enable", initial.rowVersion(), Map.of("reason", "Test demo enable"));
    assertThrows(DomainException.class, () -> bus.dispatch(driver, enable));
    bus.dispatch(admin, enable);
    assertTrue(settings.view().enabled());
    assertTrue(bus.dispatch(admin, enable).replayed());
    assertThrows(DomainException.class, () -> bus.dispatch(admin,
        command("SetClock", initial.rowVersion(), Map.of(
            "target", Instant.now().plusSeconds(3600).toString(), "reason", "Stale clock change"))));
    Instant real = clock.realTime().now();
    bus.dispatch(admin, command("SetClock", settings.view().rowVersion(), Map.of(
        "target", real.plusSeconds(3600).toString(), "reason", "After cutoff test")));
    assertTrue(clock.now().isAfter(real.plusSeconds(3590)));
    assertTrue(clock.realTime().now().isBefore(real.plusSeconds(30)));
    bus.dispatch(admin, command("Disable", settings.view().rowVersion(),
        Map.of("reason", "Restore normal operation")));
    assertFalse(settings.view().enabled());
    assertEquals(0, settings.view().offsetSeconds());
    assertThrows(DomainException.class, () -> bus.dispatch(admin,
        command("UpdateSettings", settings.view().rowVersion(),
            Map.of("reason", "Cannot update while off", "speed", 20))));
    long recorded = db.asSystem(ModuleRole.DEMO, () -> ((Number) db.queryOne(
        "SELECT count(*) AS n FROM demo.scenario_runs WHERE actor = ?", admin.userId()).get("n")).longValue());
    assertEquals(3, recorded);
    var first = settings.runs(admin.userId(), null, null);
    var cursor = first.get(0);
    var older = settings.runs(admin.userId(),
        ((java.sql.Timestamp) cursor.get("started_at")).toInstant(), (UUID) cursor.get("id"));
    assertTrue(older.stream().noneMatch(row -> row.get("id").equals(cursor.get("id"))));
  }

  @Test
  void resetPreparesOnlyAnEmptyOperatingDayThroughOwnerCommands() {
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    String suffix = UUID.randomUUID().toString();
    Actor admin = Actor.user(accounts.createAccount(
        "reset-admin-" + suffix + "@test.local", "Reset admin", "DemoPassword2026!", "admin"));
    accounts.grantDepot("reset-admin-" + suffix + "@test.local", "Peliyagoda");
    for (String role : new String[]{"dispatcher", "loader", "driver", "store_manager"}) {
      // These dedicated accounts are installed by setup on a real deployment.
      if (db.asSystem(ModuleRole.IAM, () -> db.queryOne(
          "SELECT 1 FROM iam.users WHERE email = ?", role + "@waypoint.local")) == null) {
        accounts.createAccount(role + "@waypoint.local", "Demo " + role,
            "DemoPassword2026!", role);
      }
    }
    if (!settings.view().enabled()) {
      bus.dispatch(admin, command("Enable", settings.view().rowVersion(),
          Map.of("reason", "Prepare isolated demo day")));
    }
    LocalDate day = reference.nextOperatingDay(clock.now().atZone(Clock.OPERATING_ZONE)
        .toLocalDate().plusDays(1));
    Command reset = command("ResetDay", settings.view().rowVersion(),
        Map.of("reason", "Prepare peak demo day", "serviceDate", day.toString()));
    bus.dispatch(admin, reset);
    assertTrue(bus.dispatch(admin, reset).replayed());
    for (int i = 0; i < 3; i++) resetJob.run(Instant.now());
    Map<String, Object> run = db.asSystem(ModuleRole.DEMO, () -> db.queryOne(
        "SELECT outcome,details FROM demo.scenario_runs WHERE id = ?", reset.commandId()));
    assertEquals("completed", run.get("outcome"), run.get("details").toString());
    long seeded = db.asSystem(ModuleRole.ORDERING, () -> ((Number) db.queryOne(
        "SELECT count(*) AS n FROM ordering.orders WHERE delivery_date = ?"
            + " AND warehouse_order_ref LIKE 'SEED-WH-%'", java.sql.Date.valueOf(day))
        .get("n")).longValue());
    assertEquals(85, seeded);
    assertThrows(DomainException.class, () -> bus.dispatch(admin,
        command("ResetDay", settings.view().rowVersion(),
            Map.of("reason", "Reject replacement of prepared day", "serviceDate", day.toString()))));
    bus.dispatch(admin, command("Disable", settings.view().rowVersion(),
        Map.of("reason", "End isolated demo test")));
  }

  private Command command(String verb, long version, Map<String, Object> payload) {
    return new Command(UUID.randomUUID(), "demo:" + verb, version,
        json.valueToTree(payload), Instant.now());
  }
}
