package com.waypoint.dispatch.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.config.DataConfig;
import com.waypoint.dispatch.platform.config.DirectoryLocator;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandAuthorizer;
import com.waypoint.dispatch.platform.messaging.CommandBus;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.IdempotencyGuard;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.support.TestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Issue #5: the identity defects an audit found and the tests did not.
 *
 * <p>Each test is the one that would have caught the defect: a lockout that
 * fires, a refused sign-in that leaves an audit row, a permission revoked between
 * the check and the transaction, a pool that cannot touch a table without
 * adopting a role. Accounts are named for this run, so nothing here depends on
 * what an earlier run left behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(TestDatabase.class)
@Import(IdentityHardeningIntegrationTest.MovableTime.class)
class IdentityHardeningIntegrationTest {

  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  private static final String ADMIN = "hrd-admin-" + RUN + "@waypoint.test";
  private static final String VICTIM = "hrd-victim-" + RUN + "@waypoint.test";
  private static final String LOADER = "hrd-loader-" + RUN + "@waypoint.test";
  private static final String DRIVER = "hrd-driver-" + RUN + "@waypoint.test";
  private static final String FIRST_DISPATCHER = "hrd-dispatch-a-" + RUN + "@waypoint.test";
  private static final String SECOND_DISPATCHER = "hrd-dispatch-b-" + RUN + "@waypoint.test";
  private static final String PASSWORD = "HardeningTest2026!";
  private static final String DEPOT = "Peliyagoda";

  /** TEST-NET-3: addresses no other test signs in from, so their counters start at zero. */
  private static final String ATTACKER = "203.0.113." + (10 + (RUN.hashCode() & 0x7f));
  private static final String ELSEWHERE = "198.51.100." + (10 + (RUN.hashCode() & 0x7f));

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired SessionRegistry sessions;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired IdentityQuery identity;
  @Autowired MeterRegistry meters;
  @Autowired MovableClock clock;
  @Autowired List<CommandHandler> handlers;
  @Autowired CommandAuthorizer authorizer;
  @Autowired IdempotencyGuard idempotency;
  @Autowired AuditLog audit;
  @Autowired Metrics metrics;
  @Autowired AppProperties properties;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @TestConfiguration
  static class MovableTime {
    @Bean
    @Primary
    MovableClock movableClock() {
      return new MovableClock();
    }

    /** A handler whose action the catalogue says nothing enforces yet. */
    @Bean
    CommandHandler probeHandler() {
      return new CommandHandler() {
        @Override
        public String kind() {
          return "hardening:Probe";
        }

        @Override
        public String action() {
          return "hardening:Probe";
        }

        @Override
        public ModuleRole moduleRole() {
          return ModuleRole.IAM;
        }

        @Override
        public Object handle(Actor actor, Command command) {
          return Map.of("ran", true);
        }
      };
    }
  }

  /** The system clock until a test moves it, so sessions can be aged without sleeping. */
  static final class MovableClock implements Clock {
    private final AtomicReference<Duration> ahead = new AtomicReference<>(Duration.ZERO);

    void advance(Duration by) {
      ahead.updateAndGet(current -> current.plus(by));
    }

    void reset() {
      ahead.set(Duration.ZERO);
    }

    @Override
    public Instant now() {
      return Instant.now().plus(ahead.get());
    }
  }

  @Test
  @Order(1)
  void bootstrap() {
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    accounts.createAccount(ADMIN, "Hardening Admin", PASSWORD, "admin");
    accounts.createAccount(VICTIM, "Hardening Victim", PASSWORD, "store_manager");
    accounts.createAccount(LOADER, "Hardening Loader", PASSWORD, "loader");
    accounts.createAccount(DRIVER, "Hardening Driver", PASSWORD, "driver");
    accounts.createAccount(FIRST_DISPATCHER, "Hardening Dispatcher A", PASSWORD, "dispatcher");
    accounts.createAccount(SECOND_DISPATCHER, "Hardening Dispatcher B", PASSWORD, "dispatcher");
    asIdentity(
        () ->
            database.update(
                "INSERT INTO iam.action_catalogue (action, module, description, implemented)"
                    + " VALUES ('hardening:Probe', 'test', 'Exists only for this test', false)"
                    + " ON CONFLICT (action) DO UPDATE SET implemented = false"));
  }

  // ---- authentication -------------------------------------------------------

  /**
   * SEC-05, and the definition of done: eight failures lead to 429, the history
   * is kept, and each denial is audited. Before, the failure and its audit row
   * were written and then rolled back, so no count ever reached eight.
   */
  @Test
  @Order(10)
  void eightFailedSignInsLockTheIdentityOutAndEveryOneIsRecorded() throws Exception {
    UUID victim = userIdOf(VICTIM);
    long auditedBefore = deniedSignIns(victim);

    for (int attempt = 1; attempt <= 8; attempt++) {
      assertEquals(
          401, signIn(VICTIM, "wrong-password", ATTACKER, null).getResponse().getStatus(), "attempt " + attempt);
    }

    MvcResult locked = signIn(VICTIM, "wrong-password", ATTACKER, null);
    assertEquals(429, locked.getResponse().getStatus(), "the ninth is throttled, not judged");
    long retryAfter = Long.parseLong(locked.getResponse().getHeader("Retry-After"));
    assertTrue(retryAfter > 0 && retryAfter <= 15 * 60, "Retry-After " + retryAfter);

    // The right password is throttled too: a lockout that a correct guess opens
    // is not a lockout.
    assertEquals(429, signIn(VICTIM, PASSWORD, ATTACKER, null).getResponse().getStatus());

    assertEquals(8, failures(VICTIM, ATTACKER), "the failures are stored, not rolled back");
    assertEquals(auditedBefore + 8, deniedSignIns(victim), "each refused sign-in is audited");
  }

  /** The reason the counter is keyed on the pair: a stranger cannot lock someone else out. */
  @Test
  @Order(11)
  void theOwnerOfTheAccountCanStillSignInFromSomewhereElse() throws Exception {
    assertEquals(200, signIn(VICTIM, PASSWORD, ELSEWHERE, null).getResponse().getStatus());
  }

  @Test
  @Order(12)
  void aSuccessMarksEarlierFailuresFromThatAddressAndDeletesNothing() throws Exception {
    assertEquals(401, signIn(LOADER, "wrong-password", ELSEWHERE, null).getResponse().getStatus());
    assertEquals(401, signIn(LOADER, "wrong-password", ELSEWHERE, null).getResponse().getStatus());
    assertEquals(200, signIn(LOADER, PASSWORD, ELSEWHERE, null).getResponse().getStatus());

    Map<String, Object> history =
        asIdentity(
            () ->
                database.queryOne(
                    "SELECT count(*) AS kept, count(cleared_at) AS cleared"
                        + " FROM iam.login_attempts"
                        + " WHERE email = ? AND source_ip = ?::inet AND NOT succeeded",
                    LOADER,
                    ELSEWHERE));
    assertEquals(2L, ((Number) history.get("kept")).longValue(), "the history is kept");
    assertEquals(2L, ((Number) history.get("cleared")).longValue(), "and no longer counts");
  }

  /** The window is measured on the injected clock, so the lockout ends without anyone waiting. */
  @Test
  @Order(13)
  void theLockoutLiftsWhenTheFailuresLeaveTheWindow() throws Exception {
    clock.advance(Duration.ofMinutes(16));
    try {
      assertEquals(200, signIn(VICTIM, PASSWORD, ATTACKER, null).getResponse().getStatus());
    } finally {
      clock.reset();
    }
  }

  @Test
  @Order(20)
  void aStateChangingRequestFromAnotherOriginIsRefused() throws Exception {
    MvcResult refused =
        http.perform(
                post("/api/session")
                    .header("Host", "loader.waypointgo.live")
                    .header("Origin", "https://evil.example")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(credentials(ADMIN, PASSWORD, null)))
            .andReturn();
    assertEquals(403, refused.getResponse().getStatus());
    assertNull(refused.getResponse().getHeader("Set-Cookie"), "no session for a cross-site sign-in");

    MvcResult sameSite =
        http.perform(
                post("/api/session")
                    .header("Host", "loader.waypointgo.live")
                    .header("Origin", "https://loader.waypointgo.live")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(credentials(ADMIN, PASSWORD, null)))
            .andReturn();
    assertEquals(200, sameSite.getResponse().getStatus());
  }

  @Test
  @Order(21)
  void theSessionCookieIsStrictLastsAsLongAsTheSessionAndIsStoredOnlyAsAHash() throws Exception {
    MvcResult result = signIn(ADMIN, PASSWORD, "127.0.0.1", null);
    String header = result.getResponse().getHeader("Set-Cookie");
    assertNotNull(header);
    assertTrue(header.contains("HttpOnly"), header);
    assertTrue(header.contains("SameSite=Strict"), header);
    assertTrue(
        header.contains("Max-Age=" + properties.session().absoluteLifetime().toSeconds()), header);

    String token = result.getResponse().getCookie(AuthController.COOKIE).getValue();
    Map<String, Object> stored =
        asIdentity(
            () ->
                database.queryOne(
                    "SELECT count(*) FILTER (WHERE token_hash = ?) AS plaintext,"
                        + " count(*) FILTER (WHERE token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex'))"
                        + " AS hashed FROM iam.sessions",
                    token,
                    token));
    assertEquals(0L, ((Number) stored.get("plaintext")).longValue(), "the token is never stored");
    assertEquals(1L, ((Number) stored.get("hashed")).longValue());

    MvcResult out =
        http.perform(post("/api/session/end").cookie(new Cookie(AuthController.COOKIE, token)))
            .andReturn();
    assertEquals(204, out.getResponse().getStatus());
    String cleared = out.getResponse().getHeader("Set-Cookie");
    assertTrue(cleared.contains("Max-Age=0"), cleared);
    assertTrue(cleared.contains("SameSite=Strict"), cleared);
    assertTrue(sessions.resolve(token).isEmpty(), "signing out ends the session server side");
  }

  @Test
  @Order(22)
  void signingInAgainEndsTheSessionTheBrowserAlreadyHeld() throws Exception {
    String first = cookieOf(signIn(LOADER, PASSWORD, "127.0.0.1", null));
    assertTrue(sessions.resolve(first).isPresent());

    // The same browser, still holding the loader's cookie, signs in as someone else.
    String second = cookieOf(signIn(ADMIN, PASSWORD, "127.0.0.1", first));

    assertTrue(sessions.resolve(first).isEmpty(), "the previous session must not outlive the new one");
    assertTrue(sessions.resolve(second).isPresent());
  }

  /** Expiry is decided by the injected clock, and a session in use is not written on every read. */
  @Test
  @Order(23)
  void aSessionExpiresByTheClockAndIsTouchedAtMostOnceAMinute() {
    String token = login.login(LOADER, PASSWORD, null, "127.0.0.1");
    Instant firstSeen = lastSeen(token);
    try {
      clock.advance(Duration.ofSeconds(20));
      assertTrue(sessions.resolve(token).isPresent());
      assertEquals(firstSeen, lastSeen(token), "twenty seconds on, the read wrote nothing");

      clock.advance(Duration.ofSeconds(50));
      assertTrue(sessions.resolve(token).isPresent());
      assertTrue(lastSeen(token).isAfter(firstSeen), "past the minute, the idle expiry slides");

      // Idle for longer than the idle lifetime: gone, however long the absolute one is.
      clock.advance(properties.session().idleLifetime().plusMinutes(1));
      assertTrue(sessions.resolve(token).isEmpty(), "an idle session ends by the injected clock");
    } finally {
      clock.reset();
    }
  }

  // ---- authorization --------------------------------------------------------

  /**
   * Policy administration through the command endpoint: a receipt, so a retry
   * answers from the receipt and changes nothing twice; and a version, so a second
   * administrator working from the same page is refused.
   */
  @Test
  @Order(30)
  void policyIsAdministeredByCommandWithAReceiptAndAVersionGuard() throws Exception {
    String name = "HrdRegistrar" + RUN;
    String createId = UUID.randomUUID().toString();
    String create =
        """
        {"name":"%s","description":"May register devices","document":
          {"Version":"test","Statement":[
            {"Sid":"Register","Effect":"Allow","Action":["iam:RegisterDevice"],"Resource":["*"]}]}}
        """
            .formatted(name);
    assertFalse(ack(ADMIN, createId, "iam:CreatePolicy", null, create, 200).get("replayed").asBoolean());
    assertTrue(
        ack(ADMIN, createId, "iam:CreatePolicy", null, create, 200).get("replayed").asBoolean(),
        "the same command id answers from its receipt");
    assertEquals(1, policyVersionCount(name), "and the policy was created once");

    long version = policyRowVersion(name);
    String attach =
        """
        {"name":"%s","principalType":"user","principalId":"%s"}
        """
            .formatted(name, userIdOf(LOADER));
    ack(ADMIN, UUID.randomUUID().toString(), "iam:AttachPolicy", version, attach, 200);
    assertTrue(identity.permits(userIdOf(LOADER), "iam:RegisterDevice", "wpt:iam:device:*"));

    // The same version again is a second administrator working from a stale read.
    ack(ADMIN, UUID.randomUUID().toString(), "iam:DetachPolicy", version, attach, 409);
    assertTrue(
        identity.permits(userIdOf(LOADER), "iam:RegisterDevice", "wpt:iam:device:*"),
        "a refused detach changes nothing");

    // The read surface carries the version a caller has to send back.
    JsonNode listed =
        mapper.readTree(read(ADMIN, "/api/policies?limit=100", 200)).get("items");
    boolean found = false;
    for (JsonNode policy : listed) {
      if (name.equals(policy.get("name").asText())) {
        assertEquals(version + 1, policy.get("rowVersion").asLong());
        found = true;
      }
    }
    assertTrue(found || listed.size() == 100, "the new policy is listed with its rowVersion");
  }

  /** The write endpoints are gone, not merely unused. */
  @Test
  @Order(31)
  void thePolicyControllerOnlyReads() throws Exception {
    String token = login.login(ADMIN, PASSWORD, null, "127.0.0.1");
    MvcResult result =
        http.perform(
                post("/api/policies")
                    .cookie(new Cookie(AuthController.COOKIE, token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"HrdDirect\",\"description\":\"x\",\"document\":{}}"))
            .andReturn();
    assertEquals(405, result.getResponse().getStatus());
  }

  /**
   * SEC-03, and the definition of done. The loader may register a device when the
   * command arrives. Between that check and the transaction, an administrator
   * detaches the policy. The re-check inside the transaction refuses the command,
   * nothing is written, and the refusal is audited and counted.
   */
  @Test
  @Order(32)
  void aPermissionRevokedMidFlightIsCaughtByTheReCheckInsideTheTransaction() throws Exception {
    String name = "HrdRegistrar" + RUN;
    Actor loader = Actor.user(userIdOf(LOADER));
    String detach =
        """
        {"name":"%s","principalType":"user","principalId":"%s"}
        """
            .formatted(name, loader.userId());
    double racesBefore = total("waypoint.race.lost");
    long deniedBefore = denials(loader.userId(), "iam:RegisterDevice");

    CommandAuthorizer revokesAfterTheFirstAnswer =
        new CommandAuthorizer() {
          @Override
          public Optional<String> denyReason(
              Actor actor, String action, String resource, Command command) {
            Optional<String> first = authorizer.denyReason(actor, action, resource, command);
            assertTrue(first.isEmpty(), "the loader is allowed when the command arrives");
            try {
              ack(ADMIN, UUID.randomUUID().toString(), "iam:DetachPolicy", policyRowVersion(name), detach, 200);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
            return first;
          }

          @Override
          public Optional<String> denyReasonInTransaction(
              Actor actor, String action, String resource, Command command) {
            return authorizer.denyReasonInTransaction(actor, action, resource, command);
          }
        };
    CommandBus bus =
        new CommandBus(
            handlers,
            Optional.of(revokesAfterTheFirstAnswer),
            idempotency,
            database,
            audit,
            metrics,
            mapper);
    String label = "Raced tablet " + RUN;

    DomainException thrown =
        assertThrows(
            DomainException.class,
            () ->
                bus.dispatch(
                    loader,
                    new Command(
                        UUID.randomUUID(),
                        "iam:RegisterDevice",
                        null,
                        mapper.readTree(
                            "{\"label\":\"" + label + "\",\"kind\":\"shared_tablet\"}"),
                        null)));

    assertEquals(ErrorCode.FORBIDDEN, thrown.code());
    assertEquals(
        0L,
        ((Number)
                asIdentity(
                        () ->
                            database.queryOne(
                                "SELECT count(*) AS made FROM iam.devices WHERE device_label = ?",
                                label))
                    .get("made"))
            .longValue(),
        "the command changed nothing");
    assertEquals(racesBefore + 1, total("waypoint.race.lost"), "the lost race is counted");
    assertEquals(
        deniedBefore + 1,
        denials(loader.userId(), "iam:RegisterDevice"),
        "and audited, after the rollback that would have taken the row with it");
  }

  @Test
  @Order(33)
  void anUnknownKindIsForbiddenAndAuditedAndAnUnimplementedActionIsNotRouted() throws Exception {
    UUID admin = userIdOf(ADMIN);
    long before = denials(admin, "platform:UnknownCommand");

    ack(ADMIN, UUID.randomUUID().toString(), "vehicle:Teleport", null, "{}", 403);

    assertEquals(before + 1, denials(admin, "platform:UnknownCommand"));

    // The catalogue flag is read at run time. The probe's handler exists, but its
    // action is not marked implemented, so the bus does not route it.
    String unimplemented =
        ack(ADMIN, UUID.randomUUID().toString(), "hardening:Probe", null, "{}", 403)
            .get("detail")
            .asText();
    assertTrue(unimplemented.contains("not implemented"), unimplemented);

    // Switched on, the same command gets as far as policy, which nobody was granted.
    setImplemented("hardening:Probe", true);
    try {
      String denied =
          ack(ADMIN, UUID.randomUUID().toString(), "hardening:Probe", null, "{}", 403)
              .get("detail")
              .asText();
      assertFalse(denied.contains("not implemented"), denied);
    } finally {
      setImplemented("hardening:Probe", false);
    }
  }

  // ---- privilege changes ------------------------------------------------------

  /** SEC-02: a role change takes effect now, by ending the sessions opened under the old role. */
  @Test
  @Order(40)
  void changingARoleRevokesTheAccountsSessionsAndChangesWhatItMayDo() throws Exception {
    UUID loader = userIdOf(LOADER);
    String token = login.login(LOADER, PASSWORD, null, "127.0.0.1");
    assertFalse(identity.permits(loader, "delivery:Record", "*"), "a loader does not deliver");
    double revokedBefore = total("waypoint.session.revoked");

    JsonNode result =
        ack(
                ADMIN,
                UUID.randomUUID().toString(),
                "iam:ChangeRole",
                accountVersion(loader),
                "{\"userId\":\"%s\",\"roleCode\":\"driver\"}".formatted(loader),
                200)
            .get("result");

    assertTrue(result.get("sessionsRevoked").asInt() >= 1);
    assertTrue(sessions.resolve(token).isEmpty(), "the old role's session is gone");
    assertTrue(total("waypoint.session.revoked") > revokedBefore, "revocations are counted");
    assertEquals(List.of("driver"), identity.scopeOf(loader).roles());
    assertTrue(
        identity.permits(loader, "delivery:Record", "*"),
        "the next decision sees the new role with no restart");

    // Back again, for the tests that follow.
    ack(
        ADMIN,
        UUID.randomUUID().toString(),
        "iam:ChangeRole",
        accountVersion(loader),
        "{\"userId\":\"%s\",\"roleCode\":\"loader\"}".formatted(loader),
        200);
  }

  @Test
  @Order(41)
  void aScopeChangeNeedsTheAccountVersionAndRevokingAScopeEndsSessions() throws Exception {
    UUID victim = userIdOf(VICTIM);
    String grant = "{\"userId\":\"%s\",\"outletId\":\"OUT001\"}".formatted(victim);

    // No version at all is the caller's mistake; a stale one is a conflict.
    ack(ADMIN, UUID.randomUUID().toString(), "iam:GrantScope", null, grant, 422);
    long version = accountVersion(victim);
    ack(ADMIN, UUID.randomUUID().toString(), "iam:GrantScope", version, grant, 200);
    ack(ADMIN, UUID.randomUUID().toString(), "iam:RevokeScope", version, grant, 409);
    assertEquals(List.of("OUT001"), identity.scopeOf(victim).outletIds());

    String token = login.login(VICTIM, PASSWORD, null, "127.0.0.1");
    ack(ADMIN, UUID.randomUUID().toString(), "iam:RevokeScope", accountVersion(victim), grant, 200);

    assertTrue(sessions.resolve(token).isEmpty(), "a session must not carry a revoked scope");
    assertEquals(List.of(), identity.scopeOf(victim).outletIds());
  }

  /** R-IAM-18: a depot is never left with nobody who can publish its plan. */
  @Test
  @Order(42)
  void theLastActiveDispatcherOfADepotCannotBeDisabledDemotedOrTakenOffIt() throws Exception {
    UUID first = userIdOf(FIRST_DISPATCHER);
    UUID second = userIdOf(SECOND_DISPATCHER);
    accounts.grantDepot(FIRST_DISPATCHER, DEPOT);
    accounts.grantDepot(SECOND_DISPATCHER, DEPOT);
    // Dispatchers other test classes left on the depot would make "last" untrue.
    asIdentity(
        () ->
            database.update(
                "DELETE FROM iam.user_depot_access WHERE depot_code = ? AND user_id NOT IN (?, ?)",
                DEPOT,
                first,
                second));

    // With two, one may go.
    ack(
        ADMIN,
        UUID.randomUUID().toString(),
        "iam:DisableUser",
        accountVersion(first),
        "{\"userId\":\"%s\"}".formatted(first),
        200);

    String id = "{\"userId\":\"%s\"}".formatted(second);
    String problem =
        ack(ADMIN, UUID.randomUUID().toString(), "iam:DisableUser", accountVersion(second), id, 409)
            .toString();
    assertTrue(problem.contains("R-IAM-18"), problem);
    ack(
        ADMIN,
        UUID.randomUUID().toString(),
        "iam:ChangeRole",
        accountVersion(second),
        "{\"userId\":\"%s\",\"roleCode\":\"loader\"}".formatted(second),
        409);
    ack(
        ADMIN,
        UUID.randomUUID().toString(),
        "iam:RevokeScope",
        accountVersion(second),
        "{\"userId\":\"%s\",\"depotCode\":\"%s\"}".formatted(second, DEPOT),
        409);
    assertEquals(List.of(DEPOT), identity.scopeOf(second).depotCodes(), "nothing was taken away");
  }

  // ---- devices ----------------------------------------------------------------

  @Test
  @Order(50)
  void aDeviceIsRegisteredUsedAtSignInAuditedAndRetired() throws Exception {
    // A device nobody registered used to fail the session's foreign key as a 500.
    assertEquals(
        422,
        signIn(ADMIN, PASSWORD, "127.0.0.1", null, UUID.randomUUID()).getResponse().getStatus());

    String deviceId =
        ack(
                ADMIN,
                UUID.randomUUID().toString(),
                "iam:RegisterDevice",
                null,
                "{\"label\":\"Dock tablet %s\",\"kind\":\"shared_tablet\",\"depotCode\":\"%s\"}"
                    .formatted(RUN, DEPOT),
                200)
            .get("result")
            .get("deviceId")
            .asText();

    MvcResult signedIn = signIn(ADMIN, PASSWORD, "127.0.0.1", null, UUID.fromString(deviceId));
    assertEquals(200, signedIn.getResponse().getStatus());
    String token = cookieOf(signedIn);
    assertEquals(UUID.fromString(deviceId), sessions.resolve(token).orElseThrow().deviceId());

    // A command sent from that session is audited with the device, not only the person.
    UUID victim = userIdOf(VICTIM);
    command(
        new Cookie(AuthController.COOKIE, token),
        UUID.randomUUID().toString(),
        "iam:UpdateUser",
        accountVersion(victim),
        "{\"userId\":\"%s\",\"displayName\":\"Renamed From The Dock\"}".formatted(victim),
        200);
    assertEquals(
        UUID.fromString(deviceId),
        asIntegration(
                () ->
                    database.queryOne(
                        "SELECT device_id FROM integration.audit_log"
                            + " WHERE action = 'iam:UpdateUser' AND resource = ? AND decision = 'ALLOW'"
                            + " AND reason <> 'command applied' ORDER BY occurred_at DESC LIMIT 1",
                        "wpt:iam:user:" + victim))
            .get("device_id"));

    JsonNode device = deviceRow(deviceId);
    assertTrue(device.get("active").asBoolean());
    assertFalse(device.get("lastSeenAt").isNull(), "signing in from a device marks it seen");

    // Retiring needs the version, ends the sessions opened from it, and is final.
    command(ADMIN, "iam:RetireDevice", null, "{\"deviceId\":\"%s\"}".formatted(deviceId), 422);
    JsonNode retired =
        ack(
                ADMIN,
                UUID.randomUUID().toString(),
                "iam:RetireDevice",
                device.get("rowVersion").asLong(),
                "{\"deviceId\":\"%s\"}".formatted(deviceId),
                200)
            .get("result");
    assertEquals(1, retired.get("sessionsRevoked").asInt());
    assertTrue(sessions.resolve(token).isEmpty());
    assertFalse(deviceRow(deviceId).get("active").asBoolean());
    assertEquals(
        422,
        signIn(ADMIN, PASSWORD, "127.0.0.1", null, UUID.fromString(deviceId)).getResponse().getStatus(),
        "a retired device cannot sign in");

    // Listing devices is a permission, and a driver does not have it.
    read(DRIVER, "/api/devices", 403);
  }

  // ---- database roles ---------------------------------------------------------

  /** SEC-15: the pool is waypoint_app, which holds nothing until it adopts a module role. */
  @Test
  @Order(60)
  void thePoolRunsAsWaypointAppAndCannotTouchATableWithoutAdoptingARole() {
    Map<String, Object> runtime =
        database.queryOne(
            "SELECT current_user AS name, r.rolsuper, r.rolbypassrls, r.rolinherit,"
                + " (SELECT count(*) FROM pg_class c WHERE c.relowner = r.oid) AS owned"
                + " FROM pg_roles r WHERE r.rolname = current_user");
    assertEquals("waypoint_app", runtime.get("name"));
    assertEquals(false, runtime.get("rolsuper"), "the runtime role is not a superuser");
    assertEquals(false, runtime.get("rolbypassrls"), "SEC-07: it cannot bypass row-level security");
    assertEquals(false, runtime.get("rolinherit"), "it inherits nothing from the module roles");
    assertEquals(0L, ((Number) runtime.get("owned")).longValue(), "and it owns no tables");

    // The intended failure: a query with no SET LOCAL ROLE is a permission error.
    RuntimeException refused =
        assertThrows(
            RuntimeException.class, () -> database.query("SELECT user_id FROM iam.users LIMIT 1"));
    assertEquals("42501", sqlStateOf(refused), "permission denied, not an empty result");

    // And with the role adopted, the same statement works.
    assertNotNull(asIdentity(() -> database.query("SELECT user_id FROM iam.users LIMIT 1")));
  }

  /**
   * The deployment path: migrate runs as the owner on its own connection and gives
   * waypoint_app the password the pool's URL carries. Logged in with it, the
   * session is nobody until it adopts a module role, and may adopt one.
   */
  @Test
  @Order(61)
  void migrateLetsThePoolLogInAsWaypointAppWithItsOwnPassword() throws Exception {
    String ownerUrl = TestDatabase.url();
    java.net.URI owner = java.net.URI.create(ownerUrl);
    String password = "hrd-" + UUID.randomUUID();
    String runtimeUrl =
        "postgresql://waypoint_app:" + password + "@" + owner.getHost() + ":" + owner.getPort()
            + owner.getPath();
    AppProperties deployed =
        new AppProperties(
            runtimeUrl,
            properties.dataDir(),
            properties.migrationsDir(),
            ownerUrl,
            properties.cookieSecure(),
            properties.allowedOrigins(),
            properties.problemTypeBase(),
            properties.session(),
            properties.loginThrottle(),
            properties.http(),
            properties.observability());

    assertEquals(0, new Migrator(deployed).migrate(), "the schema is already current");

    String jdbcUrl =
        "jdbc:postgresql://" + owner.getHost() + ":" + owner.getPort() + owner.getPath();
    try (Connection connection = DriverManager.getConnection(jdbcUrl, "waypoint_app", password);
        Statement statement = connection.createStatement()) {
      try (ResultSet who = statement.executeQuery("SELECT session_user, current_user")) {
        who.next();
        assertEquals("waypoint_app", who.getString(1));
      }
      SQLException refused =
          assertThrows(
              SQLException.class, () -> statement.executeQuery("SELECT 1 FROM iam.users LIMIT 1"));
      assertEquals("42501", refused.getSQLState());

      connection.setAutoCommit(false);
      statement.execute("SET LOCAL ROLE waypoint_iam");
      try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM iam.users")) {
        rows.next();
        assertTrue(rows.getLong(1) > 0);
      }
      connection.rollback();

      // It cannot make itself the owner, or anything it was not granted.
      SQLException notGranted =
          assertThrows(SQLException.class, () -> statement.execute("SET ROLE " + owner.getUserInfo().split(":")[0]));
      assertEquals("42501", notGranted.getSQLState());
      connection.rollback();
      SQLException notTheOwner =
          assertThrows(SQLException.class, () -> statement.execute("SET ROLE " + Migrator.OWNER_ROLE));
      assertEquals("42501", notTheOwner.getSQLState());
    }
  }

  /**
   * SEC-28: nothing the migrations made belongs to a superuser. The owner used to
   * be whoever ran migrate, which in the Docker deployments is the image's
   * bootstrap account.
   */
  @Test
  @Order(62)
  void theSchemaBelongsToAnOwnerThatIsNotASuperuser() throws Exception {
    try (Connection owner = ownerConnection();
        Statement statement = owner.createStatement()) {
      try (ResultSet role =
          statement.executeQuery(
              "SELECT rolsuper, rolbypassrls, rolcreatedb, rolreplication, rolinherit"
                  + " FROM pg_roles WHERE rolname = 'waypoint_migrator'")) {
        assertTrue(role.next(), "the owner role exists");
        assertFalse(role.getBoolean("rolsuper"), "not a superuser");
        assertFalse(role.getBoolean("rolbypassrls"), "row-level security applies to it");
        assertFalse(role.getBoolean("rolcreatedb"));
        assertFalse(role.getBoolean("rolreplication"));
        assertFalse(role.getBoolean("rolinherit"), "it holds no module's privileges");
      }

      String application =
          " n.nspname NOT IN ('public', 'information_schema') AND n.nspname NOT LIKE 'pg\\_%'";
      List<String> strays = new ArrayList<>();
      try (ResultSet rows =
          statement.executeQuery(
              "SELECT c.oid::regclass::text AS name FROM pg_class c"
                  + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                  + " WHERE pg_get_userbyid(c.relowner) <> 'waypoint_migrator' AND"
                  + application
                  + " UNION ALL SELECT p.oid::regprocedure::text FROM pg_proc p"
                  + " JOIN pg_namespace n ON n.oid = p.pronamespace"
                  + " WHERE pg_get_userbyid(p.proowner) <> 'waypoint_migrator' AND"
                  + application
                  + " UNION ALL SELECT 'schema ' || n.nspname FROM pg_namespace n"
                  + " WHERE pg_get_userbyid(n.nspowner) <> 'waypoint_migrator' AND"
                  + application
                  + " UNION ALL SELECT 'public.schema_migrations' FROM pg_class c"
                  + " WHERE c.oid = 'public.schema_migrations'::regclass"
                  + " AND pg_get_userbyid(c.relowner) <> 'waypoint_migrator'")) {
        while (rows.next()) {
          strays.add(rows.getString("name"));
        }
      }
      assertEquals(List.of(), strays, "every schema, table, index, sequence and function moved");

      // The definer functions run as their owner, so none runs as a superuser now.
      try (ResultSet definers =
          statement.executeQuery(
              "SELECT count(*) FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner"
                  + " WHERE p.prosecdef AND r.rolsuper AND p.pronamespace IN"
                  + " (SELECT oid FROM pg_namespace WHERE nspname IN ('app', 'integration'))")) {
        definers.next();
        assertEquals(0, definers.getLong(1));
      }
    }
  }

  /**
   * The deployed path after the handover: migrate logs in as the cluster's own
   * account and applies the file as waypoint_migrator. The table it creates must
   * belong to the owner and carry its module's grants, which come from default
   * privileges that had to be declared again for the new owner; it can add a
   * module role; and it can backfill a table that forces row-level security by
   * lifting the force around the statement.
   */
  @Test
  @Order(63)
  void aLaterMigrationRunsAsTheOwnerAndItsTableGetsTheModulesGrants(@TempDir Path dir)
      throws Exception {
    String probe = "99990101T0000_test_owner_probe.sql";
    migrationsWith(
        dir,
        probe,
        """
        CREATE TABLE ordering.hrd_owner_probe (id integer PRIMARY KEY);
        CREATE ROLE waypoint_hrd_probe NOLOGIN NOINHERIT;
        GRANT waypoint_hrd_probe TO waypoint_app;
        ALTER TABLE iam.user_depot_access NO FORCE ROW LEVEL SECURITY;
        UPDATE iam.user_depot_access SET depot_code = depot_code
         WHERE user_id = '00000000-0000-0000-0000-000000000000';
        ALTER TABLE iam.user_depot_access FORCE ROW LEVEL SECURITY;
        """);
    try (Connection owner = ownerConnection();
        Statement statement = owner.createStatement()) {
      try {
        assertEquals(
            1,
            new Migrator(deployed(TestDatabase.url(), "", dir.toString())).migrate(),
            "only the probe is new");

        assertEquals(
            "waypoint_migrator",
            scalar(
                statement,
                "SELECT pg_get_userbyid(relowner) FROM pg_class"
                    + " WHERE oid = 'ordering.hrd_owner_probe'::regclass"),
            "created as the owner, whoever logged in");
        for (String privilege : List.of("SELECT", "INSERT", "UPDATE")) {
          assertEquals(
              "t",
              scalar(
                  statement,
                  "SELECT has_table_privilege('waypoint_ordering', 'ordering.hrd_owner_probe', '"
                      + privilege
                      + "')"),
              "the module role may " + privilege + " a table a later migration adds");
        }
        assertEquals(
            "f",
            scalar(
                statement,
                "SELECT has_table_privilege('waypoint_ordering', 'ordering.hrd_owner_probe', 'DELETE')"),
            "and still never DELETE");
        assertEquals(
            "f",
            scalar(
                statement,
                "SELECT has_table_privilege('waypoint_planning', 'ordering.hrd_owner_probe', 'SELECT')"),
            "SEC-16: another module's role still cannot read it");
        assertEquals(
            "t",
            scalar(statement, "SELECT pg_has_role('waypoint_app', 'waypoint_hrd_probe', 'MEMBER')"),
            "a migration can add a module role and let the pool adopt it");
        assertEquals(
            "t",
            scalar(
                statement,
                "SELECT relforcerowsecurity FROM pg_class"
                    + " WHERE oid = 'iam.user_depot_access'::regclass"),
            "the force is back on");
      } finally {
        statement.execute("DROP TABLE IF EXISTS ordering.hrd_owner_probe");
        statement.execute("DROP ROLE IF EXISTS waypoint_hrd_probe");
        statement.execute("DELETE FROM public.schema_migrations WHERE filename = '" + probe + "'");
      }
    }
  }

  /**
   * SEC-30: the owner is subject to forced row-level security and no policy names
   * it, so a backfill would update no rows and report success. The run fails
   * instead, and being one transaction it leaves nothing behind.
   */
  @Test
  @Order(64)
  void aBackfillThatRowLevelSecurityWouldHideFailsAndChangesNothing(@TempDir Path dir)
      throws Exception {
    String probe = "99990101T0001_test_blind_backfill.sql";
    migrationsWith(
        dir,
        probe,
        """
        CREATE TABLE ordering.hrd_blind_probe (id integer PRIMARY KEY);
        UPDATE iam.user_depot_access SET depot_code = depot_code;
        """);
    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () -> new Migrator(deployed(TestDatabase.url(), "", dir.toString())).migrate());
    assertEquals("42501", sqlStateOf(refused), "refused, not silently filtered to no rows");

    try (Connection owner = ownerConnection();
        Statement statement = owner.createStatement()) {
      assertNull(
          scalar(statement, "SELECT to_regclass('ordering.hrd_blind_probe')"),
          "the statement before the refused one rolled back with it");
      assertEquals(
          "0",
          scalar(
              statement,
              "SELECT count(*) FROM public.schema_migrations WHERE filename = '" + probe + "'"));
    }
  }

  /**
   * A deployment need not give migrate a superuser's credentials at all: with a
   * password of its own the owner role logs in directly, finds the schema current
   * and still provisions the runtime login. What it cannot do is what only a
   * superuser can.
   */
  @Test
  @Order(65)
  void migrateCanLogInAsTheOwnerItselfWhichCannotDoWhatASuperuserCan() throws Exception {
    java.net.URI base = java.net.URI.create(TestDatabase.url());
    String location = "@" + base.getHost() + ":" + base.getPort() + base.getPath();
    String ownerPassword = "hrd-" + UUID.randomUUID();
    String runtimePassword = "hrd-" + UUID.randomUUID();
    String jdbcUrl = DataConfig.toJdbcUrl(TestDatabase.url());

    try (Connection superuser = ownerConnection();
        Statement asSuperuser = superuser.createStatement()) {
      asSuperuser.execute(
          "ALTER ROLE waypoint_migrator LOGIN PASSWORD '" + ownerPassword + "'");
      try {
        Migrator migrator =
            new Migrator(
                deployed(
                    "postgresql://waypoint_app:" + runtimePassword + location,
                    "postgresql://waypoint_migrator:" + ownerPassword + location,
                    properties.migrationsDir()));
        assertEquals(0, migrator.migrate(), "the schema is already current");

        try (Connection runtime =
            DriverManager.getConnection(jdbcUrl, "waypoint_app", runtimePassword)) {
          assertTrue(runtime.isValid(2), "the owner role set the runtime login's password");
        }

        try (Connection owner =
                DriverManager.getConnection(jdbcUrl, "waypoint_migrator", ownerPassword);
            Statement statement = owner.createStatement()) {
          for (String superuserOnly :
              List.of(
                  "ALTER ROLE waypoint_app BYPASSRLS",
                  "ALTER ROLE waypoint_migrator SUPERUSER",
                  "CREATE ROLE waypoint_hrd_super SUPERUSER",
                  "COPY (SELECT 1) TO PROGRAM 'true'",
                  "SET ROLE waypoint_iam")) {
            SQLException denied =
                assertThrows(SQLException.class, () -> statement.execute(superuserOnly));
            assertEquals("42501", denied.getSQLState(), superuserOnly);
          }
        }
      } finally {
        asSuperuser.execute("ALTER ROLE waypoint_migrator NOLOGIN PASSWORD NULL");
      }
    }
  }

  // ---- contract ---------------------------------------------------------------

  /** R-IAM-13: driver scope is a vehicle on a date. Yesterday's driver cannot post today. */
  @Test
  @Order(70)
  void driverScopeHoldsOnTheAssignedDatesAndFailsAcrossTheBoundary() throws Exception {
    UUID driver = userIdOf(DRIVER);
    LocalDate from = LocalDate.of(2031, 3, 3).plusDays(RUN.hashCode() & 0xff);
    LocalDate until = from.plusDays(2);
    asIdentity(
        () ->
            database.update(
                "DELETE FROM iam.vehicle_driver_assignments"
                    + " WHERE vehicle_id = 'VEH004' AND validity && daterange(?, ?, '[)')",
                java.sql.Date.valueOf(from),
                java.sql.Date.valueOf(until)));
    ack(
        ADMIN,
        UUID.randomUUID().toString(),
        "iam:AssignDriver",
        accountVersion(driver),
        "{\"vehicleId\":\"VEH004\",\"driverUserId\":\"%s\",\"from\":\"%s\",\"until\":\"%s\"}"
            .formatted(driver, from, until),
        200);

    // The contract query.
    assertEquals(Optional.of("VEH004"), identity.driverVehicleOn(driver, from));
    assertEquals(Optional.of("VEH004"), identity.driverVehicleOn(driver, until.minusDays(1)));
    assertEquals(Optional.empty(), identity.driverVehicleOn(driver, until), "the range is half open");
    assertEquals(Optional.empty(), identity.driverVehicleOn(driver, from.minusDays(1)));

    // The SQL predicate a module's row policy calls, as that module's role.
    assertTrue(drives(driver, "VEH004", from));
    assertFalse(drives(driver, "VEH004", until), "the day after the assignment ends");
    assertFalse(drives(driver, "VEH004", from.minusDays(1)), "the day before it starts");
    assertFalse(drives(driver, "VEH005", from), "another vehicle on the same day");
    assertFalse(drives(userIdOf(LOADER), "VEH004", from), "someone else on the same vehicle");
    assertFalse(drives(null, "VEH004", from), "SEC-13: no actor, no scope");
  }

  @Test
  @Order(71)
  void theContractAnswersScopeAndRecipients() {
    UUID second = userIdOf(SECOND_DISPATCHER);

    IdentityQuery.ScopeView scope = identity.scopeOf(second);
    assertEquals(List.of("dispatcher"), scope.roles());
    assertEquals(List.of(DEPOT), scope.depotCodes());

    List<UUID> recipients = identity.recipientsFor("dispatcher", "depot", DEPOT);
    assertTrue(recipients.contains(second));
    assertFalse(
        recipients.contains(userIdOf(FIRST_DISPATCHER)),
        "a disabled account has no session to read a notification with");
    assertThrows(
        IllegalArgumentException.class, () -> identity.recipientsFor("dispatcher", "planet", "x"));
  }

  // ---- reference reads -------------------------------------------------------

  /**
   * R-IAM-28, SEC-29: {@code reference:Read} says a role may read outlets and
   * fleet; scope says whose. Reference data is one snapshot shared by every
   * actor, so before this a dispatcher of one depot could read the other's.
   */
  @Test
  @Order(80)
  void aReferenceReadStopsAtTheActorsOwnScope() throws Exception {
    clock.reset();
    String dispatcher = "hrd-ref-dispatch-" + RUN + "@waypoint.test";
    String store = "hrd-ref-store-" + RUN + "@waypoint.test";
    String driver = "hrd-ref-driver-" + RUN + "@waypoint.test";
    UUID dispatcherId = accounts.createAccount(dispatcher, "Kandy Dispatcher", PASSWORD, "dispatcher");
    UUID storeId = accounts.createAccount(store, "Outlet One Manager", PASSWORD, "store_manager");
    UUID driverId = accounts.createAccount(driver, "Kandy Driver", PASSWORD, "driver");
    grantScope(dispatcherId, "\"depotCode\":\"Kandy\"");
    grantScope(storeId, "\"outletId\":\"OUT001\"");
    LocalDate today = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate();

    // A dispatcher reads their own depot: lists, one outlet, one vehicle.
    read(dispatcher, "/api/reference/outlets?depot=Kandy", 200);
    read(dispatcher, "/api/reference/vehicles?depot=Kandy&date=" + today, 200);
    read(dispatcher, "/api/reference/outlets/OUT076", 200);
    read(dispatcher, "/api/reference/vehicles/VEH060", 200);

    // And not the other one. Each refusal names the rule, is audited and counted.
    long auditedBefore = denials(dispatcherId, "reference:Read");
    double countedBefore = total("waypoint.scope.denied");
    String refused = read(dispatcher, "/api/reference/outlets?depot=Peliyagoda", 403);
    assertTrue(refused.contains("R-IAM-28"), refused);
    read(dispatcher, "/api/reference/vehicles?depot=Peliyagoda&date=" + today, 403);
    read(dispatcher, "/api/reference/outlets/OUT001", 403);
    read(dispatcher, "/api/reference/vehicles/VEH001", 403);
    assertEquals(
        auditedBefore + 4,
        denials(dispatcherId, "reference:Read"),
        "a refused read leaves an audit row, never an empty list");
    assertEquals(countedBefore + 4, total("waypoint.scope.denied"));

    // What is the same for every depot is not scoped, and unknown is still unknown.
    read(dispatcher, "/api/reference/version", 200);
    read(dispatcher, "/api/reference/calendar/" + today, 200);
    read(dispatcher, "/api/reference/outlets/OUT999", 404);

    // A store manager reads their own outlet and the calendar, which the store
    // screens need, and no other outlet. Fleet and depot lists are not in the
    // store policy at all, so policy refuses those before scope is asked.
    assertEquals(
        "OUT001",
        mapper.readTree(read(store, "/api/reference/outlets/OUT001", 200)).get("outletId").asText());
    read(store, "/api/reference/calendar/" + today, 200);
    assertTrue(read(store, "/api/reference/outlets/OUT002", 403).contains("R-IAM-28"));
    assertFalse(read(store, "/api/reference/vehicles/VEH001", 403).contains("R-IAM-28"));
    assertFalse(read(store, "/api/reference/outlets?depot=Peliyagoda", 403).contains("R-IAM-28"));
    read(store, "/api/reference/version", 403);

    // A driver holds no depot. Their scope is a vehicle on a date (R-IAM-13):
    // nothing before the assignment, then their own vehicle and the outlets of
    // the depot it works from, but not the depot's lists or its other vehicles.
    read(driver, "/api/reference/vehicles/VEH060", 403);
    read(driver, "/api/reference/outlets/OUT076", 403);
    asIdentity(
        () ->
            database.update(
                "DELETE FROM iam.vehicle_driver_assignments"
                    + " WHERE vehicle_id = 'VEH060' AND validity && daterange(?, ?, '[)')",
                java.sql.Date.valueOf(today),
                java.sql.Date.valueOf(today.plusDays(1))));
    ack(
        ADMIN,
        UUID.randomUUID().toString(),
        "iam:AssignDriver",
        accountVersion(driverId),
        "{\"vehicleId\":\"VEH060\",\"driverUserId\":\"%s\",\"from\":\"%s\",\"until\":\"%s\"}"
            .formatted(driverId, today, today.plusDays(1)),
        200);
    read(driver, "/api/reference/vehicles/VEH060", 200);
    read(driver, "/api/reference/outlets/OUT076", 200);
    read(driver, "/api/reference/vehicles/VEH059", 403);
    read(driver, "/api/reference/outlets/OUT001", 403);
    read(driver, "/api/reference/outlets?depot=Kandy", 403);

    // The assignment ends with the day. Tomorrow the same driver reads nothing.
    clock.advance(Duration.ofDays(1));
    try {
      read(driver, "/api/reference/vehicles/VEH060", 403);
      read(driver, "/api/reference/outlets/OUT076", 403);
    } finally {
      clock.reset();
    }
  }

  // ---- helpers ----------------------------------------------------------------

  private MvcResult signIn(String email, String password, String address, String heldToken)
      throws Exception {
    return signIn(email, password, address, heldToken, null);
  }

  private MvcResult signIn(
      String email, String password, String address, String heldToken, UUID deviceId)
      throws Exception {
    MockHttpServletRequestBuilder request =
        post("/api/session")
            .contentType(MediaType.APPLICATION_JSON)
            .content(credentials(email, password, deviceId))
            .with(
                servletRequest -> {
                  servletRequest.setRemoteAddr(address);
                  return servletRequest;
                });
    if (heldToken != null) {
      request.cookie(new Cookie(AuthController.COOKIE, heldToken));
    }
    return http.perform(request).andReturn();
  }

  private static String credentials(String email, String password, UUID deviceId) {
    return deviceId == null
        ? "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)
        : "{\"email\":\"%s\",\"password\":\"%s\",\"deviceId\":\"%s\"}"
            .formatted(email, password, deviceId);
  }

  private static String cookieOf(MvcResult result) {
    return result.getResponse().getCookie(AuthController.COOKIE).getValue();
  }

  private String command(
      String email, String kind, Long expectedVersion, String payload, int expectedStatus)
      throws Exception {
    return command(sessionOf(email), UUID.randomUUID().toString(), kind, expectedVersion, payload, expectedStatus);
  }

  private String command(
      Cookie session,
      String commandId,
      String kind,
      Long expectedVersion,
      String payload,
      int expectedStatus)
      throws Exception {
    String body =
        """
        {"commandId":"%s","kind":"%s","expectedVersion":%s,"payload":%s,
         "clientRecordedAt":"2026-10-01T08:00:00Z"}
        """
            .formatted(commandId, kind, expectedVersion == null ? "null" : expectedVersion, payload);
    MvcResult result =
        http.perform(
                post("/api/commands")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(expectedStatus, result.getResponse().getStatus(), response);
    return response;
  }

  private JsonNode ack(
      String email,
      String commandId,
      String kind,
      Long expectedVersion,
      String payload,
      int expectedStatus)
      throws Exception {
    return mapper.readTree(
        command(sessionOf(email), commandId, kind, expectedVersion, payload, expectedStatus));
  }

  private String read(String email, String path, int expectedStatus) throws Exception {
    MvcResult result = http.perform(get(path).cookie(sessionOf(email))).andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(expectedStatus, result.getResponse().getStatus(), response);
    return response;
  }

  private void grantScope(UUID userId, String target) throws Exception {
    ack(
        ADMIN,
        UUID.randomUUID().toString(),
        "iam:GrantScope",
        accountVersion(userId),
        "{\"userId\":\"%s\",%s}".formatted(userId, target),
        200);
  }

  /** The account the test database was created with, which is what migrate logs in as. */
  private static Connection ownerConnection() throws SQLException {
    String url = TestDatabase.url();
    return DriverManager.getConnection(
        DataConfig.toJdbcUrl(url), DataConfig.username(url), DataConfig.password(url));
  }

  private static String scalar(Statement statement, String sql) throws SQLException {
    try (ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getString(1);
    }
  }

  /** The application's settings with other database logins or another migrations directory. */
  private AppProperties deployed(String runtimeUrl, String ownerUrl, String migrationsDir) {
    return new AppProperties(
        runtimeUrl,
        properties.dataDir(),
        migrationsDir,
        ownerUrl,
        properties.cookieSecure(),
        properties.allowedOrigins(),
        properties.problemTypeBase(),
        properties.session(),
        properties.loginThrottle(),
        properties.http(),
        properties.observability());
  }

  /** Every real migration, unchanged, plus one more that sorts after them all. */
  private void migrationsWith(Path dir, String name, String sql) throws Exception {
    Path real = DirectoryLocator.resolve(properties.migrationsDir(), "migrations");
    try (var files = Files.list(real)) {
      for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".sql")).toList()) {
        Files.copy(file, dir.resolve(file.getFileName()));
      }
    }
    Files.writeString(dir.resolve(name), sql);
  }

  private Cookie sessionOf(String email) {
    return new Cookie(AuthController.COOKIE, login.login(email, PASSWORD, null, "127.0.0.1"));
  }

  private JsonNode deviceRow(String deviceId) throws Exception {
    String after = null;
    while (true) {
      JsonNode page =
          mapper.readTree(
              read(ADMIN, "/api/devices?limit=100" + (after == null ? "" : "&after=" + after), 200));
      for (JsonNode device : page.get("items")) {
        if (deviceId.equals(device.get("deviceId").asText())) {
          return device;
        }
      }
      assertFalse(page.get("nextCursor").isNull(), "device " + deviceId + " is not listed");
      after = page.get("nextCursor").asText();
    }
  }

  private UUID userIdOf(String email) {
    return (UUID)
        asIdentity(() -> database.queryOne("SELECT user_id FROM iam.users WHERE email = ?", email))
            .get("user_id");
  }

  private long accountVersion(UUID userId) {
    return ((Number)
            asIdentity(
                    () ->
                        database.queryOne(
                            "SELECT row_version FROM iam.users WHERE user_id = ?", userId))
                .get("row_version"))
        .longValue();
  }

  private long policyRowVersion(String name) {
    return ((Number)
            asIdentity(
                    () ->
                        database.queryOne(
                            "SELECT row_version FROM iam.policies WHERE name = ?", name))
                .get("row_version"))
        .longValue();
  }

  private long policyVersionCount(String name) {
    return ((Number)
            asIdentity(
                    () ->
                        database.queryOne(
                            "SELECT count(*) AS versions FROM iam.policy_versions v"
                                + " JOIN iam.policies p ON p.policy_id = v.policy_id WHERE p.name = ?",
                            name))
                .get("versions"))
        .longValue();
  }

  private long failures(String email, String address) {
    return ((Number)
            asIdentity(
                    () ->
                        database.queryOne(
                            "SELECT count(*) AS failures FROM iam.login_attempts"
                                + " WHERE email = ? AND source_ip = ?::inet AND NOT succeeded",
                            email,
                            address))
                .get("failures"))
        .longValue();
  }

  private Instant lastSeen(String token) {
    return ((java.sql.Timestamp)
            asIdentity(
                    () ->
                        database.queryOne(
                            "SELECT last_seen_at FROM iam.sessions"
                                + " WHERE token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')",
                            token))
                .get("last_seen_at"))
        .toInstant();
  }

  private long deniedSignIns(UUID userId) {
    return denials(userId, "iam:Login");
  }

  private long denials(UUID actorId, String action) {
    return ((Number)
            asIntegration(
                    () ->
                        database.queryOne(
                            "SELECT count(*) AS denied FROM integration.audit_log"
                                + " WHERE actor_id = ? AND action = ? AND decision = 'DENY'",
                            actorId,
                            action))
                .get("denied"))
        .longValue();
  }

  private void setImplemented(String action, boolean implemented) {
    asIdentity(
        () ->
            database.update(
                "UPDATE iam.action_catalogue SET implemented = ? WHERE action = ?",
                implemented,
                action));
  }

  /** {@code app.actor_drives}, called the way a module's row policy calls it. */
  private boolean drives(UUID actorId, String vehicleId, LocalDate date) {
    return (Boolean)
        database
            .asModule(
                ModuleRole.EXECUTION,
                actorId,
                () ->
                    database.queryOne(
                        "SELECT app.actor_drives(?, ?) AS drives",
                        vehicleId,
                        java.sql.Date.valueOf(date)))
            .get("drives");
  }

  private <T> T asIdentity(java.util.function.Supplier<T> work) {
    return database.asModule(ModuleRole.IAM, null, work);
  }

  private <T> T asIntegration(java.util.function.Supplier<T> work) {
    return database.asModule(ModuleRole.INTEGRATION, null, work);
  }

  private double total(String counter) {
    return meters.find(counter).counters().stream()
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }

  private static String sqlStateOf(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
    }
    return null;
  }
}
