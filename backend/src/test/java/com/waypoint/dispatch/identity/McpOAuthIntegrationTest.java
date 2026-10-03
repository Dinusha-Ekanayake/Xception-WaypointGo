package com.waypoint.dispatch.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.identity.domain.oauth.Pkce;
import com.waypoint.dispatch.platform.db.*;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.support.TestDatabase;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;

@SpringBootTest(properties = {"app.mcp.enabled=true", "app.mcp.public-url=https://waypoint.test/mcp", "app.relay.enabled=false", "app.scheduling.enabled=false"})
@AutoConfigureMockMvc
@ExtendWith(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpOAuthIntegrationTest {
  static final String RESOURCE = "https://waypoint.test/mcp", REDIRECT = "https://assistant.test/callback";
  static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", PASSWORD = "McpOAuthTest2026!";
  String email;
  UUID user;
  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired ImportReferenceDataHandler reference;
  @Autowired AccountAdminUseCase accounts;
  @Autowired Database database;
  @Autowired SessionRegistry sessions;
  @DynamicPropertySource static void db(DynamicPropertyRegistry r) { r.add("app.database-url", TestDatabase::url); }
  @BeforeAll void setup() {
    migrator.migrate(); reference.importFrom(Path.of("../data"), null);
    email = "oauth-" + UUID.randomUUID() + "@waypoint.test";
    user = accounts.createAccount(email, "OAuth store", PASSWORD, "store_manager");
    database.asModule(ModuleRole.IAM, null, () -> database.update("INSERT INTO iam.user_outlet_access (user_id, outlet_id) VALUES (?, 'OUT001')", user));
  }
  String register() throws Exception {
    return mapper.readTree(http.perform(post("/api/oauth/register").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("client_name", "Test assistant", "redirect_uris", List.of(REDIRECT), "token_endpoint_auth_method", "none"))))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("client_id").asText();
  }
  Map<String, String> approval(String client) {
    return new HashMap<>(Map.of("clientId", client, "redirectUri", REDIRECT, "responseType", "code", "codeChallenge", Pkce.challengeOf(VERIFIER),
        "codeChallengeMethod", "S256", "state", "client-state", "resource", RESOURCE, "scope", "waypoint.read", "email", email, "password", PASSWORD));
  }
  String authorize(String client) throws Exception {
    var r = http.perform(post("/api/oauth/authorize").header("Host", "waypoint.test").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(approval(client)))).andExpect(status().isOk()).andReturn().getResponse();
    assertNull(r.getHeader("Set-Cookie"));
    String redirect = mapper.readTree(r.getContentAsString()).get("redirectTo").asText();
    assertTrue(redirect.startsWith(REDIRECT + "?")); assertTrue(redirect.contains("state=client-state"));
    return Arrays.stream(URI.create(redirect).getRawQuery().split("&")).filter(v -> v.startsWith("code="))
        .map(v -> URLDecoder.decode(v.substring(5), StandardCharsets.UTF_8)).findFirst().orElseThrow();
  }
  ResultActions exchange(String client, String code, String verifier, String resource) throws Exception {
    return http.perform(post("/api/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "authorization_code")
        .param("client_id", client).param("code", code).param("redirect_uri", REDIRECT).param("code_verifier", verifier).param("resource", resource));
  }
  String token(String client, String code) throws Exception {
    var r = exchange(client, code, VERIFIER, RESOURCE).andExpect(status().isOk()).andReturn().getResponse();
    assertEquals("no-store", r.getHeader("Cache-Control"));
    return mapper.readTree(r.getContentAsString()).get("access_token").asText();
  }
  @Test void metadataUsesConfiguredOriginEvenWithForgedForwardingHeaders() throws Exception {
    var meta = mapper.readTree(http.perform(get("/api/oauth/authorization-server").header("Host", "attacker.test")
        .header("X-Forwarded-Host", "attacker.test")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertEquals("https://waypoint.test", meta.get("issuer").asText());
  }
  @Test void oauthTokenReadsOnlyItsScopeAndCannotWriteOrBecomeABrowser() throws Exception {
    String client = register(), token = token(client, authorize(client));
    assertTrue(sessions.resolve(token).isEmpty());
    http.perform(get("/api/orders").param("outlet", "OUT001").header("Authorization", "Bearer " + token).header("X-Waypoint-Mcp-Resource", RESOURCE)).andExpect(status().isOk());
    http.perform(get("/api/orders").param("outlet", "OUT002").header("Authorization", "Bearer " + token).header("X-Waypoint-Mcp-Resource", RESOURCE)).andExpect(status().isForbidden());
    http.perform(post("/api/commands").header("Authorization", "Bearer " + token).header("X-Waypoint-Mcp-Resource", RESOURCE)
        .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
  }
  @Test void tokenRequiresItsResourceOnExchangeAndEveryRead() throws Exception {
    String client = register();
    exchange(client, authorize(client), VERIFIER, "https://other.test/mcp").andExpect(status().isBadRequest());
    String token = token(client, authorize(client));
    http.perform(get("/api/mcp/context").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    http.perform(get("/api/mcp/context").header("Authorization", "Bearer " + token).header("X-Waypoint-Mcp-Resource", "https://other.test/mcp")).andExpect(status().isUnauthorized());
    http.perform(get("/api/mcp/context").header("Authorization", "Bearer " + token).header("X-Waypoint-Mcp-Resource", RESOURCE)).andExpect(status().isOk());
  }
  @Test void replayRevokesTheFirstTokenAndConcurrentExchangeCannotIssueTwoTokens() throws Exception {
    String client = register(), code = authorize(client), token = token(client, code);
    exchange(client, code, VERIFIER, RESOURCE).andExpect(status().isBadRequest());
    assertTrue(sessions.resolveMcp(token).isEmpty());
    String racing = authorize(client);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      var gate = new CountDownLatch(1);
      Callable<Integer> call = () -> { gate.await(); return exchange(client, racing, VERIFIER, RESOURCE).andReturn().getResponse().getStatus(); };
      Future<Integer> a = pool.submit(call), b = pool.submit(call); gate.countDown();
      var statuses = new ArrayList<>(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))); Collections.sort(statuses);
      assertTrue(statuses.equals(List.of(200, 400)) || statuses.equals(List.of(400, 400)), statuses.toString());
      var live = database.readAs(ModuleRole.IAM, null, () -> database.queryOne(
          "SELECT count(*) AS live FROM iam.sessions WHERE oauth_client_id = ?", UUID.fromString(client)));
      assertEquals(0L, ((Number) live.get("live")).longValue());
    } finally { pool.shutdownNow(); }
  }
  @Test void wrongVerifierAndUnregisteredRedirectCannotProduceAccess() throws Exception {
    String client = register(); exchange(client, authorize(client), VERIFIER + "wrong", RESOURCE).andExpect(status().isBadRequest());
    var request = approval(client); request.put("redirectUri", "https://attacker.test/callback");
    http.perform(post("/api/oauth/authorize").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isUnprocessableEntity());
    request = approval(client); request.put("password", "wrong");
    http.perform(post("/api/oauth/authorize").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isUnauthorized());
  }
  @Test void unsupportedScopeIsRejectedAndRevocationIsIdempotent() throws Exception {
    String client = register(); var request = approval(client); request.put("scope", "waypoint.write");
    http.perform(post("/api/oauth/authorize").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isUnprocessableEntity());
    String token = token(client, authorize(client));
    for (int n = 0; n < 2; n++) http.perform(post("/api/oauth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .param("client_id", client).param("token", token)).andExpect(status().isOk());
    assertTrue(sessions.resolveMcp(token).isEmpty());
  }
  @Test void genericClientWithoutResourceAndWithExtraScopesIsBoundToThisEndpoint() throws Exception {
    String client = register();
    var request = approval(client);
    request.remove("resource");
    request.put("scope", "openid waypoint.read profile");
    var response = http.perform(post("/api/oauth/authorize").header("Host", "waypoint.test").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(request))).andExpect(status().isOk()).andReturn().getResponse();
    String redirect = mapper.readTree(response.getContentAsString()).get("redirectTo").asText();
    String code = Arrays.stream(URI.create(redirect).getRawQuery().split("&")).filter(v -> v.startsWith("code="))
        .map(v -> URLDecoder.decode(v.substring(5), StandardCharsets.UTF_8)).findFirst().orElseThrow();
    var tokenResponse = http.perform(post("/api/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "authorization_code")
        .param("client_id", client).param("code", code).param("redirect_uri", REDIRECT).param("code_verifier", VERIFIER))
        .andExpect(status().isOk()).andReturn().getResponse();
    String token = mapper.readTree(tokenResponse.getContentAsString()).get("access_token").asText();
    assertEquals("waypoint.read", mapper.readTree(tokenResponse.getContentAsString()).get("scope").asText());
    http.perform(get("/api/mcp/context").header("Authorization", "Bearer " + token).header("X-Waypoint-Mcp-Resource", RESOURCE)).andExpect(status().isOk());
    http.perform(get("/api/mcp/context").header("Authorization", "Bearer " + token).header("X-Waypoint-Mcp-Resource", "https://other.test/mcp")).andExpect(status().isUnauthorized());
  }
}
