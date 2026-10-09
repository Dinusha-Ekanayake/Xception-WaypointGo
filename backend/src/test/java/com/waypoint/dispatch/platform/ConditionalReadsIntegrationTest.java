package com.waypoint.dispatch.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.support.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * F-07 end to end: a polled read carries an ETag and comes back 304 when the
 * browser already holds it; sessions and credentials are never kept; signing
 * out tells the browser to drop what it kept.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(TestDatabase.class)
class ConditionalReadsIntegrationTest {
  static final String PASSWORD = "OnTheRoad2026!";

  @Autowired MockMvc http;
  @Autowired Migrator migrator;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;

  String admin;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @BeforeEach
  void setUp() {
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    admin = "cr-" + UUID.randomUUID().toString().substring(0, 8) + "@reads.test";
    accounts.createAccount(admin, "Admin", PASSWORD, "admin");
    accounts.grantDepot(admin, reference.vehicle("VEH001", null).orElseThrow().depotCode());
  }

  @Test
  void anUnchangedReadComesBackAsA304WithNoBody() throws Exception {
    Cookie session = session();
    MvcResult first = http.perform(get("/api/reference/vehicles/VEH001").cookie(session)).andReturn();
    assertEquals(200, first.getResponse().getStatus(), first.getResponse().getContentAsString());
    String etag = first.getResponse().getHeader("ETag");
    assertNotNull(etag);
    assertEquals("private, no-cache", first.getResponse().getHeader("Cache-Control"));

    MvcResult again = http.perform(get("/api/reference/vehicles/VEH001").cookie(session).header("If-None-Match", etag)).andReturn();
    assertEquals(304, again.getResponse().getStatus());
    assertEquals(0, again.getResponse().getContentAsByteArray().length);
  }

  @Test
  void theSessionIsNeverKeptAndSigningOutClearsWhatWas() throws Exception {
    Cookie session = session();
    MvcResult read = http.perform(get("/api/session").cookie(session)).andReturn();
    assertEquals(200, read.getResponse().getStatus());
    assertEquals("no-store", read.getResponse().getHeader("Cache-Control"));
    assertNull(read.getResponse().getHeader("ETag"));

    MvcResult out = http.perform(post("/api/session/end").cookie(session).header("Origin", "http://localhost").header("X-Forwarded-Host", "localhost")).andReturn();
    assertEquals(204, out.getResponse().getStatus(), out.getResponse().getContentAsString());
    assertEquals("\"cache\"", out.getResponse().getHeader("Clear-Site-Data"));
  }

  private Cookie session() {
    return new Cookie(AuthController.COOKIE, login.login(admin, PASSWORD, null, "127.0.0.1"));
  }
}
