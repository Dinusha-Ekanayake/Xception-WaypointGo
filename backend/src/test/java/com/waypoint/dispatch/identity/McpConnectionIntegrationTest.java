package com.waypoint.dispatch.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.application.OperatorRegistry;
import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.support.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.mcp.enabled=true", "app.relay.enabled=false", "app.scheduling.enabled=false"})
@AutoConfigureMockMvc
@ExtendWith(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpConnectionIntegrationTest {
  private static final String PASSWORD = "McpReadOnly2026!";
  private final String email = "mcp-" + UUID.randomUUID() + "@waypoint.test";
  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired ImportReferenceDataHandler reference;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired SessionRegistry sessions;
  @Autowired OperatorRegistry operators;
  @Autowired Database database;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @BeforeAll
  void setup() {
    migrator.migrate();
    reference.importFrom(Path.of("../data"), null);
    UUID id = accounts.createAccount(email, "MCP Store", PASSWORD, "store_manager");
    grantOutlet(id);
  }

  private String connect(String identity) throws Exception {
    var result = http.perform(post("/api/mcp/session").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(java.util.Map.of("email", identity, "password", PASSWORD))))
        .andExpect(status().isOk()).andReturn();
    assertNull(result.getResponse().getHeader("Set-Cookie"));
    assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
    return mapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
  }

  @Test
  void dedicatedTokenCanReadOwnOutletButCannotBeUsedAsABrowserSession() throws Exception {
    String token = connect(email);
    assertTrue(token.startsWith("mcp."));
    assertTrue(sessions.resolve(token).isEmpty());
    http.perform(get("/api/orders").param("outlet", "OUT001")
        .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    http.perform(get("/api/orders").param("outlet", "OUT002")
        .header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    JsonNode context = mapper.readTree(http.perform(get("/api/mcp/context")
        .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn()
        .getResponse().getContentAsString());
    assertFalse(context.toString().contains(token));
    assertTrue(context.get("readActions").toString().contains("order:Read"));
    http.perform(get("/api/audit").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void bearerAndCookieFormsCannotReachCommandsUploadsOrAccountReads() throws Exception {
    String token = connect(email);
    for (String route : java.util.List.of("/api/commands", "/api/execution/deliveries/" + UUID.randomUUID() + "/proof")) {
      http.perform(post(route).header("Authorization", "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isForbidden());
      http.perform(post(route).cookie(new Cookie(AuthController.COOKIE, token))
          .contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isForbidden());
    }
    http.perform(get("/api/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    assertFalse(database.readAs(ModuleRole.INTEGRATION, null, () -> database.query(
        "SELECT reason FROM integration.audit_log WHERE action = 'mcp:Connect' AND decision = 'DENY'")).isEmpty());
  }

  @Test
  void browserBearerTokenIsRejectedAndOwnConnectionCanBeRevoked() throws Exception {
    String browser = login.login(email, PASSWORD, null, "127.0.0.1");
    http.perform(get("/api/orders").param("outlet", "OUT001")
        .header("Authorization", "Bearer " + browser)).andExpect(status().isUnauthorized());
    String token = connect(email);
    http.perform(post("/api/mcp/session/end").header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());
    http.perform(get("/api/mcp/context").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void connectionPolicyDenyAffectsTheNextRequest() throws Exception {
    String ownEmail = "mcp-deny-" + UUID.randomUUID() + "@waypoint.test";
    UUID id = accounts.createAccount(ownEmail, "MCP Deny", PASSWORD, "store_manager");
    grantOutlet(id);
    String token = connect(ownEmail);
    database.asModule(ModuleRole.IAM, id, () -> {
      UUID policy = UUID.randomUUID();
      database.update("INSERT INTO iam.policies (policy_id, name, description) VALUES (?, ?, 'MCP denial test')", policy, "McpDeny" + id.toString().replace("-", ""));
      database.update("INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default) VALUES (?, 1, ?::jsonb, true)",
          policy, "{\"Version\":\"2026-01-01\",\"Statement\":[{\"Sid\":\"RevokeMcp\",\"Effect\":\"Deny\",\"Action\":[\"mcp:Connect\"],\"Resource\":[\"*\"]}]}");
      database.update("INSERT INTO iam.policy_attachments (policy_id, principal_type, principal_id) VALUES (?, 'user', ?)", policy, id.toString());
      database.update("UPDATE iam.policy_generation SET generation = generation + 1");
    });
    http.perform(get("/api/mcp/context").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    http.perform(post("/api/mcp/session/end").header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());
  }

  @Test
  void accountDisableEndsExistingConnection() throws Exception {
    String ownEmail = "mcp-disabled-" + UUID.randomUUID() + "@waypoint.test";
    UUID id = accounts.createAccount(ownEmail, "MCP Disabled", PASSWORD, "driver");
    String token = connect(ownEmail);
    database.asModule(ModuleRole.IAM, id, () -> database.update("UPDATE iam.users SET is_active = false WHERE user_id = ?", id));
    http.perform(get("/api/mcp/context").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void allSixRolesConnectWithoutBorrowingAnotherPersonsIdentity() throws Exception {
    var expected = java.util.Map.of("dispatcher", "plan:Read", "store_manager", "order:Read",
        "loader", "loading:Read", "driver", "delivery:Read", "auditor", "audit:Read", "admin", "iam:ReadPolicy");
    for (var role : expected.entrySet()) {
      String ownEmail = "mcp-role-" + UUID.randomUUID() + "@waypoint.test";
      UUID id = accounts.createAccount(ownEmail, "MCP Role", PASSWORD, role.getKey());
      String token = connect(ownEmail);
      JsonNode context = mapper.readTree(http.perform(get("/api/mcp/context")
          .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn()
          .getResponse().getContentAsString());
      assertEquals(id.toString(), context.get("userId").asText());
      assertTrue(context.get("readActions").toString().contains(role.getValue()), role.getKey());
      assertFalse(context.has("operator"));
      assertFalse(context.has("deviceId"));
      sessions.revoke(token);
    }
  }

  @Test
  void personalLoaderReadsDoNotBorrowThePinSwitchedBrowsersScope() throws Exception {
    String ownEmail = "mcp-loader-" + UUID.randomUUID() + "@waypoint.test";
    UUID ownId = accounts.createAccount(ownEmail, "Personal loader", PASSWORD, "loader");
    String switchedEmail = "mcp-switched-" + UUID.randomUUID() + "@waypoint.test";
    UUID switchedId = accounts.createAccount(switchedEmail, "Switched loader", PASSWORD, "loader");
    var depots = database.readAs(ModuleRole.REF, null,
        () -> database.query("SELECT DISTINCT depot_code FROM ref.vehicles ORDER BY depot_code"));
    String ownDepot = (String) depots.get(0).get("depot_code");
    String otherDepot = (String) depots.get(1).get("depot_code");
    accounts.grantDepot(ownEmail, ownDepot);
    accounts.grantDepot(switchedEmail, ownDepot);
    accounts.grantDepot(switchedEmail, otherDepot);
    operators.setPin(switchedEmail, "2468", null);
    String browser = login.login(ownEmail, PASSWORD, null, "127.0.0.1");
    operators.switchTo(browser, sessions.resolve(browser).orElseThrow(), switchedId, "2468");
    String token = connect(ownEmail);
    UUID ownTrip = loadingTrip(ownDepot);
    UUID otherTrip = loadingTrip(otherDepot);
    String ownPath = "/api/loading/trips/" + ownTrip + "/manifest";
    String otherPath = "/api/loading/trips/" + otherTrip + "/manifest";
    http.perform(get(otherPath).cookie(new Cookie(AuthController.COOKIE, browser)))
        .andExpect(status().isOk());
    http.perform(get(ownPath).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    // Detail reads deliberately do not reveal whether an inaccessible record exists.
    http.perform(get(otherPath).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    var rows = database.readAs(ModuleRole.INTEGRATION, null, () -> database.query(
        "SELECT actor_id FROM integration.audit_log WHERE action = 'loading:Read' AND resource = ? AND decision = 'ALLOW'",
        "wpt:loading:trip:" + ownTrip));
    assertTrue(rows.stream().anyMatch(r -> ownId.equals(r.get("actor_id"))));
    assertFalse(rows.stream().anyMatch(r -> switchedId.equals(r.get("actor_id"))));
  }

  @Test
  void driverDeliveryReadsRespectTheAssignmentDateInSql() throws Exception {
    String ownEmail = "mcp-date-" + UUID.randomUUID() + "@waypoint.test";
    UUID id = accounts.createAccount(ownEmail, "Assigned driver", PASSWORD, "driver");
    var vehicle = database.readAs(ModuleRole.REF, null, () -> database.queryOne(
        "SELECT vehicle_id, depot_code FROM ref.vehicles ORDER BY vehicle_id LIMIT 1"));
    String vehicleId = (String) vehicle.get("vehicle_id");
    String depot = (String) vehicle.get("depot_code");
    LocalDate date = LocalDate.of(2200, 1, 1).plusDays(Math.floorMod(id.hashCode(), 100000));
    database.asModule(ModuleRole.IAM, null, () -> database.update(
        "INSERT INTO iam.vehicle_driver_assignments (assignment_id, vehicle_id, driver_user_id, validity, assigned_by) VALUES (?, ?, ?, daterange(?::date, ?::date, '[)'), ?)",
        UUID.randomUUID(), vehicleId, id, date.toString(), date.plusDays(1).toString(), id));
    UUID permitted = delivery(vehicleId, depot, date);
    UUID outsideDate = delivery(vehicleId, depot, date.plusDays(1));
    String token = connect(ownEmail);
    http.perform(get("/api/execution/deliveries/" + permitted).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    http.perform(get("/api/execution/deliveries/" + outsideDate).header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  @Test
  void discoveryReadsRespectScopeAndHideInaccessibleCustody() throws Exception {
    var depots = database.readAs(ModuleRole.REF, null,
        () -> database.query("SELECT DISTINCT depot_code FROM ref.vehicles ORDER BY depot_code"));
    String ownDepot = (String) depots.get(0).get("depot_code");
    String otherDepot = (String) depots.get(1).get("depot_code");
    String loaderEmail = "mcp-discovery-" + UUID.randomUUID() + "@waypoint.test";
    accounts.createAccount(loaderEmail, "Discovery loader", PASSWORD, "loader");
    accounts.grantDepot(loaderEmail, ownDepot);
    String loader = connect(loaderEmail);
    http.perform(get("/api/loading/trips").param("depot", ownDepot).param("date", "2200-01-01")
        .header("Authorization", "Bearer " + loader)).andExpect(status().isOk());
    http.perform(get("/api/loading/trips").param("depot", otherDepot).param("date", "2200-01-01")
        .header("Authorization", "Bearer " + loader)).andExpect(status().isForbidden());
    String driverEmail = "mcp-runsheet-" + UUID.randomUUID() + "@waypoint.test";
    accounts.createAccount(driverEmail, "Discovery driver", PASSWORD, "driver");
    String driver = connect(driverEmail);
    http.perform(get("/api/execution/run-sheets").param("date", "2200-01-01")
        .header("Authorization", "Bearer " + driver)).andExpect(status().isOk());
    http.perform(get("/api/execution/run-sheets").param("date", "2200-01-01").param("depot", otherDepot)
        .header("Authorization", "Bearer " + driver)).andExpect(status().isForbidden());
    String store = connect(email);
    http.perform(get("/api/receipts/pending").param("outlet", "OUT001")
        .header("Authorization", "Bearer " + store)).andExpect(status().isOk());
    http.perform(get("/api/receipts/pending").param("outlet", "OUT002")
        .header("Authorization", "Bearer " + store)).andExpect(status().isForbidden());
    http.perform(get("/api/receipts/" + UUID.randomUUID() + "/custody")
        .header("Authorization", "Bearer " + store)).andExpect(status().isNotFound());
  }

  // Test-only records exercise the existing owning-module reads and their real RLS policies.
  private UUID loadingTrip(String depot) {
    UUID trip = UUID.randomUUID();
    String vehicle = (String) database.readAs(ModuleRole.REF, null, () -> database.queryOne(
        "SELECT vehicle_id FROM ref.vehicles WHERE depot_code = ? LIMIT 1", depot)).get("vehicle_id");
    database.asSystem(ModuleRole.LOADING, () -> {
      database.update("""
          INSERT INTO loading.trips (trip_id, plan_version, plan_id, depot_code, service_date, vehicle_id,
              trip_number, trips_for_vehicle, brand_code, district_name, temperature, planned_departure,
              dock_code, weight_cap_kg, volume_cap_m3, received_at)
          VALUES (?, 1, ?, ?, '2200-01-01', ?, 1, 1, 'Fresh', 'Colombo', 'ambient', '04:00', 'Dock 1', 1000, 10, now())
          """, trip, UUID.randomUUID(), depot, vehicle);
      database.update("INSERT INTO loading.sessions (trip_id, depot_code, plan_version, updated_at) VALUES (?, ?, 1, now())", trip, depot);
    });
    return trip;
  }

  private UUID delivery(String vehicle, String depot, LocalDate date) {
    UUID trip = UUID.randomUUID();
    UUID delivery = UUID.randomUUID();
    database.asSystem(ModuleRole.EXECUTION, () -> {
      database.update("INSERT INTO execution.trips (trip_id, plan_id, plan_version, depot_code, vehicle_id, service_date, released_at) VALUES (?, ?, 1, ?, ?, ?::date, now())",
          trip, UUID.randomUUID(), depot, vehicle, date.toString());
      database.update("""
          INSERT INTO execution.delivery_records (delivery_id, trip_id, order_id, outlet_id, depot_code,
              vehicle_id, service_date, stop_sequence, item_count, planned_arrival, window_open, window_close,
              released_at, server_recorded_at)
          VALUES (?, ?, ?, 'OUT001', ?, ?, ?::date, 1, 1, '05:00', '04:00', '08:00', now(), now())
          """, delivery, trip, UUID.randomUUID(), depot, vehicle, date.toString());
    });
    return delivery;
  }

  private void grantOutlet(UUID id) {
    database.asModule(ModuleRole.IAM, id, () -> database.update(
        "INSERT INTO iam.user_outlet_access (user_id, outlet_id) VALUES (?, 'OUT001')", id));
  }
}
