package com.waypoint.dispatch.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.SessionRetentionJob;
import com.waypoint.dispatch.identity.infrastructure.SessionTokens;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.support.TestDatabase;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * R-IAM-33 against PostgreSQL: MCP requests are limited per credential and per
 * OAuth client, the counters are shared rows (so a second replica is no way
 * around them), a refusal is 429 with Retry-After, the first refusal in a
 * window is audited, and a limited caller can still disconnect.
 *
 * <p>The clock is pinned to the middle of a minute, so a window cannot roll
 * over during a test.
 */
@SpringBootTest(properties = {"app.mcp.enabled=true", "app.relay.enabled=false", "app.scheduling.enabled=false",
    "app.mcp.rate-per-credential-per-minute=3", "app.mcp.rate-per-client-per-minute=5",
    "app.mcp.public-url=" + McpRateLimitIntegrationTest.RESOURCE})
@AutoConfigureMockMvc
@ExtendWith(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpRateLimitIntegrationTest {
  private static final String PASSWORD = "correct horse battery staple";
  static final String RESOURCE = "http://127.0.0.1/mcp";
  static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(30);

  @TestConfiguration
  static class PinnedClock {
    @Bean
    @Primary
    Clock pinnedClock() {
      return Clock.fixed(NOW);
    }
  }

  private final String email = "mcp-rate-" + UUID.randomUUID() + "@waypoint.test";
  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired ImportReferenceDataHandler reference;
  @Autowired AccountAdminUseCase accounts;
  @Autowired Database database;
  @Autowired SessionTokens tokens;
  @Autowired SessionRetentionJob retention;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @BeforeAll
  void setup() {
    migrator.migrate();
    reference.importFrom(Path.of("../data"), null);
    UUID id = accounts.createAccount(email, "MCP Rate", PASSWORD, "store_manager");
    database.asModule(ModuleRole.IAM, id, () -> database.update(
        "INSERT INTO iam.user_outlet_access (user_id, outlet_id) VALUES (?, 'OUT001')", id));
  }

  private String connect() throws Exception {
    var result = http.perform(post("/api/mcp/session").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(java.util.Map.of("email", email, "password", PASSWORD))))
        .andExpect(status().isOk()).andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
  }

  private int read(String token) throws Exception {
    return http.perform(get("/api/orders").param("outlet", "OUT001").header("Authorization", "Bearer " + token))
        .andReturn().getResponse().getStatus();
  }

  /** As the remote adapter sends it: the credential plus the resource it was issued for. */
  private int readRemote(String token) throws Exception {
    return http.perform(get("/api/orders").param("outlet", "OUT001").header("Authorization", "Bearer " + token)
        .header("X-Waypoint-Mcp-Resource", RESOURCE)).andReturn().getResponse().getStatus();
  }

  private long refusalsAudited(UUID actor) {
    return ((Number) database.readAs(ModuleRole.INTEGRATION, null, () -> database.queryOne(
        "SELECT count(*) AS n FROM integration.audit_log WHERE actor_id = ? AND decision = 'DENY'"
            + " AND reason LIKE 'MCP rate limit%'", actor).get("n"))).longValue();
  }

  private UUID userId() {
    return (UUID) database.asSystem(ModuleRole.IAM, () -> database.queryOne(
        "SELECT user_id FROM iam.users WHERE email = ?", email).get("user_id"));
  }

  @Test
  void aCredentialOverItsLimitIsRefusedWith429AndRetryAfterAndAuditedOnce() throws Exception {
    String token = connect();
    long before = refusalsAudited(userId());
    for (int i = 0; i < 3; i++) {
      assertEquals(200, read(token), "request " + (i + 1) + " is within the limit");
    }
    var refused = http.perform(get("/api/orders").param("outlet", "OUT001").header("Authorization", "Bearer " + token))
        .andExpect(status().isTooManyRequests()).andReturn().getResponse();
    assertEquals("30", refused.getHeader("Retry-After"), "the pinned clock is 30 s into its minute");
    assertTrue(refused.getContentAsString().contains("RATE_LIMITED"), refused.getContentAsString());
    assertEquals(429, read(token));
    assertEquals(before + 1, refusalsAudited(userId()), "the first refusal in a window is audited, the rest counted");

    assertEquals(200, read(connect()), "another credential has its own count");
    http.perform(post("/api/mcp/session/end").header("Authorization", "Bearer " + token))
        .andExpect(result -> assertTrue(result.getResponse().getStatus() < 300, "a limited caller can still disconnect"));
  }

  @Test
  void credentialsOfOneOAuthClientShareTheClientLimit() throws Exception {
    // Registered from a documentation address (RFC 5737), so the fixture never
    // counts against the per-address registration throttle other tests use.
    UUID client = UUID.randomUUID();
    database.asSystem(ModuleRole.IAM, () -> database.update(
        "INSERT INTO iam.oauth_clients (client_id, client_name, redirect_uris, registered_at, registered_from)"
            + " VALUES (?, 'Rate test client', '[\"https://client.test/callback\"]'::jsonb, ?, '192.0.2.10')",
        client, Timestamp.from(NOW)));
    String first = connect();
    String second = connect();
    for (String token : java.util.List.of(first, second)) {
      database.asSystem(ModuleRole.IAM, () -> database.update(
          "UPDATE iam.sessions SET oauth_client_id = ?, oauth_resource = ? WHERE token_hash = ?",
          client, RESOURCE, tokens.hash(token)));
    }

    assertEquals(200, readRemote(first));
    assertEquals(200, readRemote(first));
    assertEquals(200, readRemote(first));
    assertEquals(200, readRemote(second));
    assertEquals(200, readRemote(second));
    assertEquals(429, readRemote(second), "the sixth request through one client is over its limit of 5");
  }

  @Test
  void oldWindowsAreRemovedByRetention() throws Exception {
    read(connect());
    retention.run(NOW.plus(Duration.ofHours(2)));
    long left = ((Number) database.asSystem(ModuleRole.IAM, () -> database.queryOne(
        "SELECT count(*) AS n FROM iam.mcp_rate_windows WHERE window_start <= ?", Timestamp.from(NOW)).get("n"))).longValue();
    assertEquals(0, left);
  }
}
