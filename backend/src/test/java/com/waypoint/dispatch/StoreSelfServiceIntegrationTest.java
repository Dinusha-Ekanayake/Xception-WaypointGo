package com.waypoint.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceCache;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceVersionReader;
import com.waypoint.dispatch.support.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A store manager keeping their own profile (R-IAM-32) and their store's details
 * (R-REF-01) up to date, over HTTP and the command endpoint.
 *
 * <p>The assertions that matter would pass with the guards removed only if they
 * were wrong: the profile command never reaches another account, a stale version
 * is refused, a store outside the manager's scope is refused, a mall bay stays a
 * mall bay, and what planning reads changes with the store's window. The last test
 * puts the published outlets back, because the reference snapshot is shared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(TestDatabase.class)
class StoreSelfServiceIntegrationTest {

  private static final String MANAGER = "ssv-manager@waypoint.test";
  private static final String OTHER = "ssv-other@waypoint.test";
  private static final String DRIVER = "ssv-driver@waypoint.test";
  private static final String PASSWORD = "SelfService2026!";

  /** Rear dock, 04:00-07:45 in the supplied data. */
  private static final String REAR = "OUT005";
  /** A mall bay bound by the mall's 09:00-11:00. */
  private static final String MALL = "OUT015";
  /** Another store, outside the manager's scope. */
  private static final String ELSEWHERE = "OUT001";

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;
  @Autowired ReferenceCache cache;
  @Autowired ReferenceVersionReader reader;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @Test
  @Order(1)
  void bootstrap() {
    migrator.migrate();
    clearFixtures();
    referenceImport.importFrom(Path.of("../data"), null);
    accounts.createAccount(MANAGER, "Self Service Manager", PASSWORD, "store_manager");
    accounts.createAccount(OTHER, "Other Store Manager", PASSWORD, "store_manager");
    accounts.createAccount(DRIVER, "Self Service Driver", PASSWORD, "driver");
    accounts.grantOutlet(MANAGER, REAR);
    accounts.grantOutlet(MANAGER, MALL);
    accounts.grantOutlet(OTHER, ELSEWHERE);
  }

  // ---- R-IAM-32: one's own name and phone ----

  @Test
  @Order(2)
  void aManagerChangesTheirOwnNameAndPhone() throws Exception {
    JsonNode before = mapper.readTree(read(MANAGER, "/api/profile", 200));
    assertEquals("Self Service Manager", before.get("displayName").asText());
    assertTrue(before.get("phone").isNull());
    long version = before.get("rowVersion").asLong();

    JsonNode ack =
        mapper.readTree(
            command(MANAGER, "iam:UpdateOwnProfile", version, """
                {"displayName":"  Nuwan   Perera ","phone":"+94 77 123-4567"}
                """, 200));
    assertEquals(version + 1, ack.get("result").get("rowVersion").asLong());

    JsonNode after = mapper.readTree(read(MANAGER, "/api/profile", 200));
    assertEquals("Nuwan Perera", after.get("displayName").asText());
    assertEquals("+94771234567", after.get("phone").asText());
    assertEquals(MANAGER, after.get("email").asText(), "the sign-in name is not part of the profile command");
    assertEquals(
        "Nuwan Perera",
        mapper.readTree(read(MANAGER, "/api/session", 200)).get("displayName").asText(),
        "the session reads the name live, so the next request already shows it");
  }

  @Test
  @Order(3)
  void aStaleVersionOrAMalformedNumberChangesNothing() throws Exception {
    long version = mapper.readTree(read(MANAGER, "/api/profile", 200)).get("rowVersion").asLong();
    command(MANAGER, "iam:UpdateOwnProfile", version - 1, """
        {"displayName":"Stale Name","phone":""}
        """, 409);
    String refused = command(MANAGER, "iam:UpdateOwnProfile", version, """
        {"displayName":"Nuwan Perera","phone":"12ab"}
        """, 422);
    assertTrue(refused.contains("R-IAM-32"), refused);
    JsonNode profile = mapper.readTree(read(MANAGER, "/api/profile", 200));
    assertEquals("Nuwan Perera", profile.get("displayName").asText());
    assertEquals("+94771234567", profile.get("phone").asText());
    assertEquals(version, profile.get("rowVersion").asLong());
  }

  @Test
  @Order(4)
  void theCommandOnlyEverChangesTheActorsOwnAccount() throws Exception {
    UUID other = userIdOf(OTHER);
    long version = mapper.readTree(read(MANAGER, "/api/profile", 200)).get("rowVersion").asLong();
    command(MANAGER, "iam:UpdateOwnProfile", version, """
        {"userId":"%s","displayName":"Not Somebody Else","phone":"0771234567"}
        """.formatted(other), 200);
    assertEquals("Other Store Manager", nameOf(OTHER), "a userId in the payload is ignored");
    assertEquals("Not Somebody Else", nameOf(MANAGER));
  }

  @Test
  @Order(5)
  void aRoleWithoutTheGrantCanNeitherReadNorChangeAProfile() throws Exception {
    read(DRIVER, "/api/profile", 403);
    command(DRIVER, "iam:UpdateOwnProfile", 1L, """
        {"displayName":"Driver Renamed"}
        """, 403);
    assertEquals("Self Service Driver", nameOf(DRIVER));
  }

  // ---- R-REF-01: the store's window, dock and contacts ----

  @Test
  @Order(6)
  void aManagerChangesTheirStoresWindowDockAndContactsAndPlanningSeesIt() throws Exception {
    JsonNode none = mapper.readTree(read(MANAGER, "/api/reference/outlets/" + REAR + "/details", 200));
    assertEquals(0, none.get("rowVersion").asLong());
    assertTrue(none.get("contactPhone").isNull());

    JsonNode ack =
        mapper.readTree(
            command(MANAGER, "reference:UpdateOutletDetails", 0L, """
                {"outletId":"%s","windowOpen":"05:30","windowClose":"08:00","dockType":"street",
                 "contactName":"Nuwan","contactPhone":"077 123 4567","receivingNotes":"Ring the bell"}
                """.formatted(REAR), 200));
    assertEquals(1, ack.get("result").get("rowVersion").asLong());

    OutletView seen = reference.outlet(REAR, null).orElseThrow();
    assertEquals(LocalTime.of(5, 30), seen.windowOpen(), "the snapshot planning reads has the store's window");
    assertEquals(LocalTime.of(8, 0), seen.effectiveWindowClose().orElseThrow());
    assertEquals("street", seen.dockType());

    JsonNode details = mapper.readTree(read(MANAGER, "/api/reference/outlets/" + REAR + "/details", 200));
    assertEquals("0771234567", details.get("contactPhone").asText());
    assertEquals("Ring the bell", details.get("receivingNotes").asText());
    assertEquals(1, details.get("rowVersion").asLong());
  }

  @Test
  @Order(7)
  void aStaleVersionIsRefusedAndBlanksGoBackToThePublishedOutlet() throws Exception {
    command(MANAGER, "reference:UpdateOutletDetails", 0L, """
        {"outletId":"%s"}
        """.formatted(REAR), 409);
    command(MANAGER, "reference:UpdateOutletDetails", 1L, """
        {"outletId":"%s"}
        """.formatted(REAR), 200);
    OutletView seen = reference.outlet(REAR, null).orElseThrow();
    assertEquals(LocalTime.of(4, 0), seen.windowOpen());
    assertEquals(LocalTime.of(7, 45), seen.windowClose());
    assertEquals("rear_dock", seen.dockType());
    JsonNode details = mapper.readTree(read(MANAGER, "/api/reference/outlets/" + REAR + "/details", 200));
    assertTrue(details.get("contactName").isNull());
    assertEquals(2, details.get("rowVersion").asLong());
  }

  @Test
  @Order(8)
  void aMallBayStaysAndTheWindowMustStillOverlapTheMalls() throws Exception {
    String leave = command(MANAGER, "reference:UpdateOutletDetails", 0L, """
        {"outletId":"%s","dockType":"street"}
        """.formatted(MALL), 422);
    assertTrue(leave.contains("R-REF-01"), leave);
    String outside = command(MANAGER, "reference:UpdateOutletDetails", 0L, """
        {"outletId":"%s","windowOpen":"12:00","windowClose":"13:00"}
        """.formatted(MALL), 422);
    assertTrue(outside.contains("R-PLN-29"), outside);
    command(MANAGER, "reference:UpdateOutletDetails", 0L, """
        {"outletId":"%s","windowOpen":"08:00","windowClose":"10:00"}
        """.formatted(MALL), 200);
    OutletView seen = reference.outlet(MALL, null).orElseThrow();
    assertEquals(LocalTime.of(9, 0), seen.effectiveWindowOpen().orElseThrow(), "still the mall's opening");
    assertEquals(LocalTime.of(10, 0), seen.effectiveWindowClose().orElseThrow(), "the store's own close");
  }

  @Test
  @Order(9)
  void aStoreOutsideTheManagersScopeIsRefusedAndAudited() throws Exception {
    String refused = command(OTHER, "reference:UpdateOutletDetails", 0L, """
        {"outletId":"%s","contactName":"Not my store"}
        """.formatted(REAR), 403);
    assertTrue(refused.contains("R-IAM-28"), refused);
    command(DRIVER, "reference:UpdateOutletDetails", 0L, """
        {"outletId":"%s","contactName":"Driver"}
        """.formatted(REAR), 403);
    assertTrue(
        mapper.readTree(read(MANAGER, "/api/reference/outlets/" + REAR + "/details", 200)).get("contactName").isNull());
    Map<String, Object> denial =
        database.asSystemSeparately(
            ModuleRole.INTEGRATION,
            () ->
                database.queryOne(
                    "SELECT count(*) AS n FROM integration.audit_log WHERE action = ? AND resource = ? AND decision = 'DENY'",
                    "reference:UpdateOutletDetails",
                    "wpt:ref:outlet:" + REAR));
    assertTrue(((Number) denial.get("n")).longValue() >= 1, "a refusal is audited, never silent");
  }

  @Test
  @Order(10)
  void cleanUpPutsThePublishedOutletsBack() {
    clearFixtures();
    database.asModule(
        ModuleRole.REF,
        null,
        () -> {
          cache.loadedVersionId().flatMap(reader::load).ifPresent(cache::publish);
          return null;
        });
    OutletView rear = reference.outlet(REAR, null).orElseThrow();
    assertEquals(LocalTime.of(4, 0), rear.windowOpen());
    assertFalse(rear.dockType().equals("street"));
  }

  // ---- helpers ----

  private String command(String email, String kind, Long expectedVersion, String payload, int expectedStatus)
      throws Exception {
    String body =
        """
        {"commandId":"%s","kind":"%s","expectedVersion":%s,"payload":%s,
         "clientRecordedAt":"2026-10-03T08:00:00Z"}
        """
            .formatted(UUID.randomUUID(), kind, expectedVersion == null ? "null" : expectedVersion.toString(), payload);
    return perform(post("/api/commands").content(body), email, expectedStatus);
  }

  private String read(String email, String path, int expectedStatus) throws Exception {
    return perform(get(path), email, expectedStatus);
  }

  private String perform(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String email, int expectedStatus)
      throws Exception {
    String token = login.login(email, PASSWORD, null, "127.0.0.1");
    MvcResult result =
        http.perform(builder.cookie(new Cookie(AuthController.COOKIE, token)).contentType(MediaType.APPLICATION_JSON))
            .andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(expectedStatus, result.getResponse().getStatus(), response);
    return response;
  }

  private UUID userIdOf(String email) {
    return (UUID)
        database
            .asModule(ModuleRole.IAM, null, () -> database.queryOne("SELECT user_id FROM iam.users WHERE email = ?", email))
            .get("user_id");
  }

  private String nameOf(String email) {
    return (String)
        database
            .asModule(ModuleRole.IAM, null, () -> database.queryOne("SELECT display_name FROM iam.users WHERE email = ?", email))
            .get("display_name");
  }

  private void clearFixtures() {
    database.asModule(
        ModuleRole.REF,
        null,
        () -> database.update("DELETE FROM ref.outlet_details WHERE outlet_id IN (?, ?, ?)", REAR, MALL, ELSEWHERE));
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          String mine = " (SELECT user_id FROM iam.users WHERE email LIKE 'ssv-%')";
          database.update("DELETE FROM iam.user_outlet_access WHERE user_id IN" + mine);
          database.update("DELETE FROM iam.user_depot_access WHERE user_id IN" + mine);
          database.update("DELETE FROM iam.sessions WHERE user_id IN" + mine);
          database.update("DELETE FROM iam.user_roles WHERE user_id IN" + mine);
          database.update("DELETE FROM iam.users WHERE email LIKE 'ssv-%'");
        });
  }
}
