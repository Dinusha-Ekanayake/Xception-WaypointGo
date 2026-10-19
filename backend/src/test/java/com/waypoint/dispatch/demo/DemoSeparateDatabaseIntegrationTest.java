package com.waypoint.dispatch.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.demo.application.DemoSettingsQuery;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandBus;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.support.TestDatabase;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * R-DEMO-06: with DEMO_RESET on, the demo runs in a database of its own. In the
 * real database (any database not named waypoint_demo, as here), turning demo
 * mode on only asks the server to switch and changes no setting, and Reset demo
 * is refused, so nothing can ever ask for the real database to be dropped.
 */
@SpringBootTest(properties = "app.demo.reset=true")
@ExtendWith(TestDatabase.class)
class DemoSeparateDatabaseIntegrationTest {
  @Autowired Migrator migrator;
  @Autowired AccountAdminUseCase accounts;
  @Autowired CommandBus bus;
  @Autowired ObjectMapper json;
  @Autowired DemoSettingsQuery settings;
  @Autowired Database db;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @Test
  void inTheRealDatabaseDemoOnOnlyAsksForTheSwitchAndResetIsRefused() {
    migrator.migrate();
    String suffix = UUID.randomUUID().toString();
    Actor admin = Actor.user(accounts.createAccount(
        "switch-admin-" + suffix + "@test.local", "Switch admin", "DemoPassword2026!", "admin"));
    if (settings.view().enabled()) {
      bus.dispatch(admin, command("Disable", settings.view().rowVersion(), Map.of("reason", "Start from off")));
    }
    clearRequests();
    long before = settings.view().rowVersion();

    Command on = command("Enable", before, Map.of("reason", "Switch to the demo database"));
    Map<?, ?> answer = (Map<?, ?>) bus.dispatch(admin, on).value();
    assertEquals(Boolean.TRUE, answer.get("switching"));
    assertFalse(settings.view().enabled(), "the real database never turns demo mode on");
    assertEquals(before, settings.view().rowVersion(), "no setting changed");
    assertEquals("requested", db.asSystem(ModuleRole.DEMO, () -> db.queryOne(
        "SELECT outcome FROM demo.scenario_runs WHERE id = ? AND scenario_key = 'demo:SwitchToDemo'", on.commandId())).get("outcome"));
    assertThrows(DomainException.class, () -> bus.dispatch(admin,
        command("Enable", before, Map.of("reason", "A second switch while one waits"))));

    DomainException refused = assertThrows(DomainException.class, () -> bus.dispatch(admin,
        command("RequestReset", null, Map.of("reason", "Never in the real database", "confirm", "RESET"))));
    assertTrue(refused.getMessage().contains("only the demo database"), refused.getMessage());
    clearRequests();
  }

  /** The suite shares one database, and a waiting switch would refuse the next test's. */
  private void clearRequests() {
    db.asSystem(ModuleRole.DEMO, () -> db.update(
        "UPDATE demo.scenario_runs SET outcome = 'failed' WHERE outcome = 'requested'"));
  }

  private Command command(String verb, Long version, Map<String, Object> payload) {
    return new Command(UUID.randomUUID(), "demo:" + verb, version, json.valueToTree(payload), Instant.now());
  }
}
