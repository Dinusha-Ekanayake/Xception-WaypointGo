package com.waypoint.dispatch.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.support.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Issue #177 end to end: client scopes (R-IAM-34), confirmed safe writes with
 * their own rate limit (R-IAM-35, P-32), the personal-field grant (R-IAM-36),
 * blocking a client (R-IAM-37), own connections and the usage view.
 */
@SpringBootTest(properties = {"app.mcp.enabled=true", "app.mcp.writes-per-credential-per-hour=6",
    "app.mcp.public-url=http://localhost/mcp",
    "app.relay.enabled=false", "app.scheduling.enabled=false"})
@AutoConfigureMockMvc
@ExtendWith(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpEnterpriseIntegrationTest {
  private static final String PASSWORD = "McpEnterprise2026!";
  private final String store = "mcp-ent-store-" + UUID.randomUUID() + "@waypoint.test";
  private final String admin = "mcp-ent-admin-" + UUID.randomUUID() + "@waypoint.test";
  private UUID storeId;
  private String depot;
  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired ImportReferenceDataHandler reference;
  @Autowired ReferenceQuery referenceQuery;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired Database database;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @BeforeAll
  void setup() {
    migrator.migrate();
    reference.importFrom(Path.of("../data"), null);
    storeId = accounts.createAccount(store, "MCP Enterprise Store", PASSWORD, "store_manager");
    database.asModule(ModuleRole.IAM, storeId, () -> database.update(
        "INSERT INTO iam.user_outlet_access (user_id, outlet_id) VALUES (?, 'OUT001')", storeId));
    accounts.createAccount(admin, "MCP Enterprise Admin", PASSWORD, "admin");
    depot = referenceQuery.outlet("OUT001", null).orElseThrow().depotCode();
  }

  // ---- client scopes ---------------------------------------------------------

  @Test
  void aNarrowScopeAllowsOnlyItsAreaAndNamesTheRuleWhenItRefuses() throws Exception {
    String token = connect(store, "orders.read");
    JsonNode context = json(mcp(get("/api/mcp/context"), token), 200);
    assertEquals(List.of("order:Read"), strings(context.get("readActions")), "the scope narrows discovery");
    assertEquals(List.of("orders.read"), strings(context.get("grantedScopes")));
    assertTrue(context.get("writeTools").isEmpty(), "no write scope, no write tools");

    status(mcp(get("/api/orders").param("outlet", "OUT001"), token), 200);
    String refused = body(mcp(get("/api/receipts/pending").param("outlet", "OUT001"), token), 403);
    assertTrue(refused.contains("R-IAM-34"), refused);

    String write = body(mcp(post("/api/mcp/writes").contentType(MediaType.APPLICATION_JSON)
        .content(raise(depot)), token), 403);
    assertTrue(write.contains("R-IAM-34") && write.contains("issues.write"), write);
  }

  @Test
  void theDefaultGrantIsEveryReadAndTheSafeWritesAndAnUnknownScopeIsRefused() throws Exception {
    JsonNode context = json(mcp(get("/api/mcp/context"), connect(store, null)), 200);
    assertEquals(List.of("issues.write", "waypoint.read"), strings(context.get("grantedScopes")));
    assertEquals(List.of("assign_issue", "raise_issue"), strings(context.get("writeTools")));
    assertFalse(context.get("personalFields").asBoolean(), "personal fields are off unless granted");
    assertTrue(strings(context.get("readActions")).contains("receipt:Read"));

    status(http.perform(post("/api/mcp/session").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("email", store, "password", PASSWORD, "scope", "openid profile")))).andReturn(), 422);
  }

  // ---- confirmed writes --------------------------------------------------------

  @Test
  void aWriteHappensOnlyWhenItsConfirmationIsSpentOnceByTheSameConnection() throws Exception {
    String token = connect(store, null);
    JsonNode prepared = json(mcp(post("/api/mcp/writes").contentType(MediaType.APPLICATION_JSON)
        .content(raise(depot)), token), 200);
    String confirmation = prepared.get("confirmation").asText();
    UUID commandId = UUID.fromString(prepared.get("commandId").asText());
    assertEquals("issue:Raise", prepared.get("kind").asText());
    assertEquals(0, issuesRaisedBy(commandId), "preparing changes nothing");

    String other = connect(store, null);
    String stolen = body(mcp(post("/api/mcp/writes/confirm").contentType(MediaType.APPLICATION_JSON)
        .content(confirm(confirmation)), other), 409);
    assertTrue(stolen.contains("R-IAM-35"), "another connection cannot spend it: " + stolen);

    JsonNode done = json(mcp(post("/api/mcp/writes/confirm").contentType(MediaType.APPLICATION_JSON)
        .content(confirm(confirmation)), token), 200);
    assertEquals(commandId.toString(), done.get("commandId").asText(), "the stored command, with its fixed id");
    assertEquals("OPEN", done.get("result").get("status").asText());
    assertEquals(1, issuesRaisedBy(commandId), "the bus ran it once");

    status(mcp(post("/api/mcp/writes/confirm").contentType(MediaType.APPLICATION_JSON)
        .content(confirm(confirmation)), token), 409);

    var audit = database.readAs(ModuleRole.INTEGRATION, null, () -> database.query(
        "SELECT action, reason FROM integration.audit_log WHERE command_id = ? ORDER BY audit_id", commandId));
    assertTrue(audit.stream().anyMatch(r -> "issue:Raise".equals(r.get("action"))), "the bus audited the command: " + audit);
    assertTrue(audit.stream().anyMatch(r -> String.valueOf(r.get("reason")).startsWith("MCP write confirmed")), audit.toString());
    assertFalse(audit.toString().contains("broken pallet"), "the payload is never in the audit reason");
  }

  @Test
  void anExpiredConfirmationIsRefusedAndTheBusStillAppliesTheBusinessPolicy() throws Exception {
    String token = connect(store, null);
    String confirmation = json(mcp(post("/api/mcp/writes").contentType(MediaType.APPLICATION_JSON)
        .content(raise(depot)), token), 200).get("confirmation").asText();
    database.asModule(ModuleRole.IAM, null, () -> database.update(
        "UPDATE iam.mcp_write_confirmations SET issued_at = issued_at - interval '10 minutes',"
            + " expires_at = expires_at - interval '10 minutes' WHERE user_id = ? AND consumed_at IS NULL", storeId));
    status(mcp(post("/api/mcp/writes/confirm").contentType(MediaType.APPLICATION_JSON)
        .content(confirm(confirmation)), token), 409);

    // A store manager may not assign issues: the MCP grant does not widen that.
    String assign = json(mcp(post("/api/mcp/writes").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("tool", "assign_issue", "expectedVersion", 1,
            "payload", Map.of("issueId", UUID.randomUUID().toString(), "assigneeUserId", storeId.toString())))), token), 200)
        .get("confirmation").asText();
    status(mcp(post("/api/mcp/writes/confirm").contentType(MediaType.APPLICATION_JSON)
        .content(confirm(assign)), token), 403);

    status(mcp(post("/api/mcp/writes").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("tool", "generate_draft_plan", "payload", Map.of()))), token), 422);
    status(mcp(post("/api/mcp/writes").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("tool", "assign_issue", "payload", Map.of()))), token), 422);
  }

  @Test
  void writesHaveTheirOwnHourlyLimit() throws Exception {
    String token = connect(store, null);
    for (int i = 0; i < 6; i++) {
      status(mcp(post("/api/mcp/writes").contentType(MediaType.APPLICATION_JSON).content(raise(depot)), token), 200);
    }
    MvcResult limited = mcp(post("/api/mcp/writes").contentType(MediaType.APPLICATION_JSON).content(raise(depot)), token);
    assertEquals(429, limited.getResponse().getStatus(), limited.getResponse().getContentAsString());
    assertNotNull(limited.getResponse().getHeader("Retry-After"));
    status(mcp(get("/api/mcp/context"), token), 200);
  }

  @Test
  void anAdministratorTurnsWritesOffAndPersonalFieldsOnWithPolicyAlone() throws Exception {
    String email = "mcp-ent-policy-" + UUID.randomUUID() + "@waypoint.test";
    UUID id = accounts.createAccount(email, "MCP Policy", PASSWORD, "store_manager");
    String token = connect(email, null);
    attach("WaypointMcpNoWrites", id);
    attach("WaypointMcpPersonalReader", id);
    JsonNode context = json(mcp(get("/api/mcp/context"), token), 200);
    assertTrue(context.get("writeTools").isEmpty());
    assertTrue(context.get("personalFields").asBoolean());
    String refused = body(mcp(post("/api/mcp/writes").contentType(MediaType.APPLICATION_JSON).content(raise(depot)), token), 403);
    assertTrue(refused.contains("R-IAM-35"), refused);

    attach("WaypointMcpBlocked", id);
    status(mcp(get("/api/mcp/context"), token), 403);
  }

  // ---- connections, clients and usage -----------------------------------------

  @Test
  void aPersonEndsTheirOwnConnectionFromTheBrowserButNotAnotherPersons() throws Exception {
    String email = "mcp-ent-own-" + UUID.randomUUID() + "@waypoint.test";
    accounts.createAccount(email, "MCP Own Connections", PASSWORD, "store_manager");
    String token = connect(email, "orders.read");
    connect(email, null);
    Cookie browser = browser(email);
    JsonNode own = json(http.perform(get("/api/mcp/connections").cookie(browser)).andReturn(), 200);
    assertEquals(2, own.size(), own.toString());
    String connectionId = null;
    for (JsonNode connection : own) {
      if (strings(connection.get("scopes")).equals(List.of("orders.read"))) {
        connectionId = connection.get("connectionId").asText();
      }
    }
    assertNotNull(connectionId, own.toString());
    assertFalse(own.toString().contains(token), "the credential itself is never shown");

    status(http.perform(post("/api/mcp/connections/revoke").cookie(browser(admin)).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("connectionId", connectionId)))).andReturn(), 404);
    status(http.perform(post("/api/mcp/connections/revoke").cookie(browser).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("connectionId", connectionId)))).andReturn(), 204);
    status(mcp(get("/api/mcp/context"), token), 401);

    String assistant = connect(store, null);
    status(mcp(get("/api/mcp/connections"), assistant), 403);
    status(mcp(get("/api/mcp/clients"), assistant), 403);
    status(mcp(get("/api/audit/mcp-usage"), assistant), 403);
  }

  @Test
  void blockingAClientEndsItsConnectionsWithAVersionAndAReason() throws Exception {
    UUID client = UUID.randomUUID();
    database.asModule(ModuleRole.IAM, null, () -> database.update(
        "INSERT INTO iam.oauth_clients (client_id, client_name, redirect_uris, registered_at)"
            + " VALUES (?, 'Test assistant', '[\"https://assistant.test/cb\"]'::jsonb, now())", client));
    String email = "mcp-ent-blocked-" + UUID.randomUUID() + "@waypoint.test";
    UUID id = accounts.createAccount(email, "MCP Blocked Client User", PASSWORD, "store_manager");
    String token = connect(email, null);
    database.asModule(ModuleRole.IAM, null, () -> database.update(
        "UPDATE iam.sessions SET oauth_client_id = ?, oauth_resource = 'http://localhost/mcp'"
            + " WHERE user_id = ? AND mcp_read_only", client, id));
    status(mcp(get("/api/mcp/context").header("X-Waypoint-Mcp-Resource", "http://localhost/mcp"), token), 200);

    Cookie administrator = browser(admin);
    JsonNode clients = json(http.perform(get("/api/mcp/clients").cookie(administrator)).andReturn(), 200);
    assertTrue(clients.toString().contains(client.toString()));
    status(http.perform(get("/api/mcp/clients").cookie(browser(store))).andReturn(), 403);

    String block = command("mcp:BlockClient", 1L, Map.of("clientId", client.toString(), "reason", "probing other depots"));
    status(http.perform(post("/api/commands").cookie(browser(store)).contentType(MediaType.APPLICATION_JSON).content(block)).andReturn(), 403);
    status(http.perform(post("/api/commands").cookie(administrator).contentType(MediaType.APPLICATION_JSON)
        .content(command("mcp:BlockClient", 7L, Map.of("clientId", client.toString(), "reason", "x")))).andReturn(), 409);
    JsonNode blocked = json(http.perform(post("/api/commands").cookie(administrator).contentType(MediaType.APPLICATION_JSON)
        .content(block)).andReturn(), 200);
    assertEquals(1, blocked.get("result").get("connectionsRevoked").asInt());
    status(mcp(get("/api/mcp/context").header("X-Waypoint-Mcp-Resource", "http://localhost/mcp"), token), 401);

    JsonNode after = json(http.perform(get("/api/mcp/clients").cookie(administrator)).andReturn(), 200);
    for (JsonNode c : after) {
      if (c.get("clientId").asText().equals(client.toString())) {
        assertEquals("probing other depots", c.get("blockReason").asText());
        assertEquals(2, c.get("rowVersion").asInt());
        assertEquals(0, c.get("activeConnections").asInt());
      }
    }
    status(http.perform(post("/api/commands").cookie(administrator).contentType(MediaType.APPLICATION_JSON)
        .content(command("mcp:UnblockClient", 2L, Map.of("clientId", client.toString())))).andReturn(), 200);
  }

  @Test
  void theUsageViewCountsCallsPerToolAndMarksRepeatedRefusals() throws Exception {
    String token = connect(store, "orders.read");
    status(mcp(get("/api/orders").param("outlet", "OUT001"), token), 200);
    for (int i = 0; i < 10; i++) {
      status(mcp(get("/api/receipts/pending").param("outlet", "OUT001"), token), 403);
    }
    JsonNode usage = json(http.perform(get("/api/audit/mcp-usage").cookie(browser(admin))).andReturn(), 200);
    boolean sawOrders = false;
    boolean sawAttention = false;
    for (JsonNode row : usage) {
      sawOrders |= row.get("tool").asText().equals("list_orders") && row.get("ok").asLong() >= 1;
      sawAttention |= row.get("tool").asText().equals("list_pending_receipts") && row.get("attention").asBoolean();
    }
    assertTrue(sawOrders, usage.toString());
    assertTrue(sawAttention, "ten refusals mark the pair for attention: " + usage);
    status(http.perform(get("/api/audit/mcp-usage").cookie(browser(store))).andReturn(), 403);
    status(http.perform(get("/api/audit/mcp-usage").param("from", "2026-01-01T00:00:00Z")
        .param("to", "2026-03-01T00:00:00Z").cookie(browser(admin))).andReturn(), 422);
  }

  // ---- helpers -----------------------------------------------------------------

  private String connect(String identity, String scope) throws Exception {
    var body = new java.util.HashMap<String, String>(Map.of("email", identity, "password", PASSWORD));
    if (scope != null) {
      body.put("scope", scope);
    }
    MvcResult result = http.perform(post("/api/mcp/session").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(body))).andReturn();
    return json(result, 200).get("token").asText();
  }

  private Cookie browser(String identity) {
    return new Cookie(AuthController.COOKIE, login.login(identity, PASSWORD, null, "127.0.0.1"));
  }

  private MvcResult mcp(MockHttpServletRequestBuilder request, String token) throws Exception {
    return http.perform(request.header("Authorization", "Bearer " + token)).andReturn();
  }

  private String raise(String depotCode) throws Exception {
    return mapper.writeValueAsString(Map.of("tool", "raise_issue", "payload", Map.of(
        "type", "DAMAGED_GOODS", "severity", "LOW", "depotCode", depotCode, "outletId", "OUT001",
        "subjects", List.of(Map.of("type", "order", "id", UUID.randomUUID().toString())),
        "description", "broken pallet at the back door")));
  }

  private String confirm(String confirmation) throws Exception {
    return mapper.writeValueAsString(Map.of("confirmation", confirmation));
  }

  private String command(String kind, Long version, Map<String, Object> payload) throws Exception {
    var envelope = new java.util.HashMap<String, Object>();
    envelope.put("commandId", UUID.randomUUID().toString());
    envelope.put("kind", kind);
    envelope.put("expectedVersion", version);
    envelope.put("payload", payload);
    return mapper.writeValueAsString(envelope);
  }

  private long issuesRaisedBy(UUID commandId) {
    return database.readAs(ModuleRole.INTEGRATION, null, () -> ((Number) database.queryOne(
        "SELECT count(*) AS n FROM integration.audit_log WHERE command_id = ? AND action = 'issue:Raise' AND decision = 'ALLOW'",
        commandId).get("n")).longValue());
  }

  private void attach(String policy, UUID userId) {
    database.asModule(ModuleRole.IAM, userId, () -> {
      database.update("INSERT INTO iam.policy_attachments (policy_id, principal_type, principal_id)"
          + " SELECT policy_id, 'user', ? FROM iam.policies WHERE name = ?", userId.toString(), policy);
      database.update("UPDATE iam.policy_generation SET generation = generation + 1");
    });
  }

  private JsonNode json(MvcResult result, int expected) throws Exception {
    return mapper.readTree(body(result, expected));
  }

  private String body(MvcResult result, int expected) throws Exception {
    String response = result.getResponse().getContentAsString();
    assertEquals(expected, result.getResponse().getStatus(), response);
    return response;
  }

  private void status(MvcResult result, int expected) throws Exception {
    body(result, expected);
  }

  private static List<String> strings(JsonNode array) {
    var out = new java.util.ArrayList<String>();
    array.forEach(n -> out.add(n.asText()));
    return out.stream().sorted().toList();
  }
}
