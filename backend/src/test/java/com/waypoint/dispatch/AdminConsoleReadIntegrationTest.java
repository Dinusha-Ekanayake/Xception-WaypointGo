package com.waypoint.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.support.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Admin directory reads are real, scoped, and require the matching policy. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(TestDatabase.class)
class AdminConsoleReadIntegrationTest {
  private static final String ADMIN = "admin-read-test@waypoint.test";
  private static final String DRIVER = "admin-read-driver@waypoint.test";
  private static final String PASSWORD = "AdminReadTest2026!";

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired ImportReferenceDataHandler importer;
  @Autowired AccountAdminUseCase accounts;
  @Autowired ReferenceQuery reference;
  @Autowired LoginHandler login;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @Test
  void adminDirectoriesReadPublishedDataAndRespectScope() throws Exception {
    migrator.migrate();
    importer.importFrom(Path.of("../data"), null);
    accounts.createAccount(ADMIN, "Admin directory test", PASSWORD, "admin");
    accounts.createAccount(DRIVER, "Driver directory test", PASSWORD, "driver");
    String depot = reference.depotCodes().get(0);
    accounts.grantDepot(ADMIN, depot);

    JsonNode roles = read(ADMIN, "/api/admin/roles?limit=100", 200);
    assertTrue(roles.get("items").size() > 0);
    JsonNode actions = read(ADMIN, "/api/admin/actions?limit=100", 200);
    assertTrue(actions.get("items").size() > 0);
    JsonNode depots = read(ADMIN, "/api/admin/reference/depots", 200);
    assertEquals(1, depots.size(), "only the granted depot is visible");
    assertEquals(depot, depots.get(0).get("code").asText());
    JsonNode outlets = read(ADMIN, "/api/admin/outlets?limit=200", 200);
    assertFalse(outlets.get("items").isEmpty());
    assertEquals(depot, outlets.get("items").get(0).get("depot").asText());
    JsonNode vehicles = read(ADMIN, "/api/admin/vehicles?limit=200", 200);
    assertFalse(vehicles.get("items").isEmpty());
    assertEquals(depot, vehicles.get("items").get(0).get("depot").asText());
    assertEquals(200, readStatus(ADMIN, "/api/admin/orders?limit=25"));
    assertEquals(200, readStatus(ADMIN, "/api/admin/plans?limit=25"));
    read(DRIVER, "/api/admin/roles", 403);
  }

  private JsonNode read(String email, String path, int expectedStatus) throws Exception {
    String token = login.login(email, PASSWORD, null, "127.0.0.1");
    var response = http.perform(get(path).cookie(new Cookie(AuthController.COOKIE, token))).andReturn().getResponse();
    assertEquals(expectedStatus, response.getStatus(), response.getContentAsString());
    return mapper.readTree(response.getContentAsString());
  }

  private int readStatus(String email, String path) throws Exception {
    String token = login.login(email, PASSWORD, null, "127.0.0.1");
    return http.perform(get(path).cookie(new Cookie(AuthController.COOKIE, token))).andReturn().getResponse().getStatus();
  }
}
