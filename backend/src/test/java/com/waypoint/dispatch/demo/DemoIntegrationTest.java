package com.waypoint.dispatch.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.demo.application.DemoSettingsQuery;
import com.waypoint.dispatch.demo.application.ResetDayJob;
import com.waypoint.dispatch.demo.application.SimulationJob;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.platform.config.DataConfig;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
    clearClosesBeyondTheDemoWindow();
    // The test database is shared: another test may have left demo mode on.
    if (settings.view().enabled()) {
      bus.dispatch(admin, command("Disable", settings.view().rowVersion(), Map.of("reason", "Start from normal operation")));
    }
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
    // Other tests may have closed a later day; the clock may not go back before that close.
    Instant target = latestClose().map(c -> c.plusSeconds(60)).filter(c -> c.isAfter(real.plusSeconds(3600)))
        .orElse(real.plusSeconds(3600));
    bus.dispatch(admin, command("SetClock", settings.view().rowVersion(), Map.of(
        "target", target.toString(), "reason", "After cutoff test")));
    assertTrue(clock.now().isAfter(target.minusSeconds(10)));
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
    // Other tests place orders on the next operating days of the shared database,
    // so take the first day in the seven-day window that is still empty.
    clearClosesBeyondTheDemoWindow();
    LocalDate today = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate();
    LocalDate candidate = reference.nextOperatingDay(today.plusDays(1));
    while (taken(candidate) && !candidate.isAfter(today.plusDays(7))) {
      candidate = reference.nextOperatingDay(candidate.plusDays(1));
    }
    LocalDate day = candidate;
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

  @Autowired SimulationJob simulationJob;

  @Test
  void simulationsAreAdminOnlyRefusedWhileOffAndStopWhenDemoModeEnds() {
    migrator.migrate();
    String suffix = UUID.randomUUID().toString();
    Actor admin = Actor.user(accounts.createAccount(
        "sim-admin-" + suffix + "@test.local", "Sim admin", "DemoPassword2026!", "admin"));
    Actor driver = Actor.user(accounts.createAccount(
        "sim-driver-" + suffix + "@test.local", "Sim driver", "DemoPassword2026!", "driver"));
    if (settings.view().enabled()) {
      bus.dispatch(admin, command("Disable", settings.view().rowVersion(), Map.of("reason", "Start from off")));
    }
    assertThrows(DomainException.class, () -> bus.dispatch(admin,
        command("StartSimulation", null, Map.of("reason", "Refused while off"))));
    bus.dispatch(admin, command("Enable", settings.view().rowVersion(), Map.of("reason", "Simulation test")));
    assertThrows(DomainException.class, () -> bus.dispatch(driver,
        command("StartSimulation", null, Map.of("reason", "Drivers cannot start this"))));
    assertThrows(DomainException.class, () -> bus.dispatch(admin,
        command("StartSimulation", null, Map.of("reason", "Nothing released", "serviceDate", "2030-01-02"))));

    // A route whose driver is not assigned: the real position command refuses it and the run says why.
    UUID refused = UUID.randomUUID();
    UUID running = UUID.randomUUID();
    db.asSystem(ModuleRole.DEMO, () -> {
      for (UUID id : new UUID[]{refused, running}) {
        db.update("INSERT INTO demo.simulations(id,vehicle_id,service_date,driver_user_id,waypoints,status,reason,started_by)"
                + " VALUES (?,?,?,?,?::jsonb,'running','test',?)",
            id, "TEST-" + id, java.sql.Date.valueOf("2030-01-02"), driver.userId(),
            "[{\"latitude\":6.9665,\"longitude\":79.8862},{\"latitude\":6.9271,\"longitude\":79.8612}]", admin.userId());
      }
      return null;
    });
    bus.dispatch(admin, command("ControlSimulations", null, Map.of("reason", "Hold one", "action", "pause", "simulationId", running.toString())));
    simulationJob.run(Instant.now());
    Map<String, Object> failed = db.asSystem(ModuleRole.DEMO, () -> db.queryOne(
        "SELECT status,failure FROM demo.simulations WHERE id = ?", refused));
    assertEquals("failed", failed.get("status"));
    assertTrue(failed.get("failure").toString().startsWith("Position refused"));
    assertEquals("paused", db.asSystem(ModuleRole.DEMO, () -> db.queryOne(
        "SELECT status FROM demo.simulations WHERE id = ?", running)).get("status"));

    bus.dispatch(admin, command("Disable", settings.view().rowVersion(), Map.of("reason", "End simulation test")));
    assertEquals("stopped", db.asSystem(ModuleRole.DEMO, () -> db.queryOne(
        "SELECT status FROM demo.simulations WHERE id = ?", running)).get("status"));
    assertTrue(settings.simulations(admin.userId(), null, null).stream().anyMatch(r -> r.get("id").equals(running)));
  }

  private Command command(String verb, Long version, Map<String, Object> payload) {
    return new Command(UUID.randomUUID(), "demo:" + verb, version,
        json.valueToTree(payload), Instant.now());
  }

  /**
   * Other suites close days years ahead to stay clear of real dates. No clock this
   * system runs can reach those closes (the demo clock stays within seven days), but
   * they would hold the demo clock back, so they are cleared as the owner.
   */
  private static void clearClosesBeyondTheDemoWindow() {
    try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
      statement.executeUpdate("DELETE FROM ordering.day_closures WHERE closed_at > now() + interval '7 days'");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Whether Peliyagoda already has orders or a close on that day, read past row-level security. */
  private static boolean taken(LocalDate day) {
    try (Connection owner = ownerConnection();
        PreparedStatement query = owner.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM ordering.orders WHERE depot_code = 'Peliyagoda' AND delivery_date = ?)"
                + " OR EXISTS (SELECT 1 FROM ordering.day_closures WHERE depot_code = 'Peliyagoda' AND service_date = ?)")) {
      query.setObject(1, day);
      query.setObject(2, day);
      try (ResultSet rows = query.executeQuery()) {
        rows.next();
        return rows.getBoolean(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The latest close of any depot, as the clock guard sees it. */
  private static java.util.Optional<Instant> latestClose() {
    try (Connection owner = ownerConnection(); Statement statement = owner.createStatement();
        ResultSet rows = statement.executeQuery("SELECT max(closed_at) FROM ordering.day_closures")) {
      rows.next();
      return java.util.Optional.ofNullable(rows.getTimestamp(1)).map(java.sql.Timestamp::toInstant);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Connection ownerConnection() throws SQLException {
    String url = TestDatabase.url();
    return DriverManager.getConnection(DataConfig.toJdbcUrl(url), DataConfig.username(url), DataConfig.password(url));
  }
}
