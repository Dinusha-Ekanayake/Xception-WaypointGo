package com.waypoint.dispatch.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.execution.ExecutionTestConfig.MovableClock;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.support.TestDatabase;
import com.waypoint.dispatch.support.TestDates;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * FF-06b end to end: an administrator uploads a vehicle's National Fuel Pass,
 * only the driver assigned to it today or tomorrow reads it, every read and
 * refusal is audited, and a replaced or withdrawn pass stops being served.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ExecutionTestConfig.class)
@ExtendWith(TestDatabase.class)
class FuelPassIntegrationTest {
  static final String PASSWORD = "OnTheRoad2026!";

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;
  @Autowired MovableClock clock;

  LocalDate day;
  String vehicleId;
  String admin;
  String driver;
  String otherDriver;
  String dispatcher;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @BeforeEach
  void setUp() {
    clock.reset();
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    String depot = reference.outlet("OUT001", null).orElseThrow().depotCode();
    // A vehicle and day nobody else uses, so the pass count starts at zero.
    do {
      day = TestDates.unusedDay(reference::nextOperatingDay);
      vehicleId = reference.availableVehicles(depot, day, null).get((int) (Math.random() * 5)).vehicleId();
    } while (passCount(vehicleId) > 0 || assigned(vehicleId, day.minusDays(1), day.plusDays(2)));
    clock.set(day.atTime(LocalTime.of(4, 0)).atZone(Clock.OPERATING_ZONE).toInstant());

    String run = UUID.randomUUID().toString().substring(0, 8);
    admin = "fa-" + run + "@fuel.test";
    driver = "fd-" + run + "@fuel.test";
    otherDriver = "fo-" + run + "@fuel.test";
    dispatcher = "fp-" + run + "@fuel.test";
    accounts.createAccount(admin, "Admin", PASSWORD, "admin");
    UUID driverId = accounts.createAccount(driver, "Driver", PASSWORD, "driver");
    accounts.createAccount(otherDriver, "Other Driver", PASSWORD, "driver");
    accounts.createAccount(dispatcher, "Dispatcher", PASSWORD, "dispatcher");
    accounts.grantDepot(dispatcher, depot);
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          database.update(
              "INSERT INTO iam.vehicle_driver_assignments (vehicle_id, driver_user_id, validity)"
                  + " VALUES (?, ?, daterange(?::date, ?::date))",
              vehicleId, driverId, Date.valueOf(day), Date.valueOf(day.plusDays(1)));
        });
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  @Test
  void theAssignedDriverReadsThePassAnAdministratorUploadedAndEveryReadIsAudited() throws Exception {
    read(driver, 404);
    byte[] image = png(300);
    UUID passId = UUID.randomUUID();
    JsonNode receipt = json(upload(admin, passId, 0, image, 200));
    assertTrue(receipt.get("stored").asBoolean());
    assertEquals(1, receipt.get("version").asLong());

    // The same upload again, from a retry, is the same pass.
    JsonNode again = json(upload(admin, passId, 0, image, 200));
    assertFalse(again.get("stored").asBoolean());

    JsonNode pass = json(read(driver, 200));
    assertEquals(passId.toString(), pass.get("passId").asText());
    assertEquals("image/png", pass.get("contentType").asText());
    assertArrayEquals(image, Base64.getDecoder().decode(pass.get("image").asText()));
    assertEquals(day.toString(), pass.get("validUntil").asText(), "driven today only, so kept to the end of today");
    assertTrue(audited("delivery:ReadFuelPass", "ALLOW"));

    // The administrator's list never carries the image.
    JsonNode statuses = json(fetch(admin, "/api/execution/fuel-passes", 200));
    JsonNode mine = null;
    for (JsonNode s : statuses) {
      if (s.get("vehicleId").asText().equals(vehicleId)) {
        mine = s;
      }
    }
    assertEquals(passId.toString(), mine.get("passId").asText());
    assertFalse(mine.has("image"));
  }

  @Test
  void theEveningBeforeTheDriverMayFetchTomorrowsPass() throws Exception {
    upload(admin, UUID.randomUUID(), 0, png(200), 200);
    clock.set(day.minusDays(1).atTime(LocalTime.of(19, 0)).atZone(Clock.OPERATING_ZONE).toInstant());
    assertEquals(day.toString(), json(read(driver, 200)).get("validUntil").asText());
  }

  @Test
  void nobodyElseReadsThePassAndEachRefusalIsAudited() throws Exception {
    upload(admin, UUID.randomUUID(), 0, png(200), 200);
    read(otherDriver, 403);
    read(dispatcher, 403);
    read(admin, 403);
    assertTrue(audited("delivery:ReadFuelPass", "DENY"));
    // A day after the assignment ends, the driver is refused too.
    clock.set(day.plusDays(2).atTime(LocalTime.of(4, 0)).atZone(Clock.OPERATING_ZONE).toInstant());
    read(driver, 403);
  }

  @Test
  void onlyAnAdministratorUploadsAndAStaleScreenCannotReplaceAPass() throws Exception {
    upload(driver, UUID.randomUUID(), 0, png(200), 403);
    upload(dispatcher, UUID.randomUUID(), 0, png(200), 403);
    upload(admin, UUID.randomUUID(), 0, "not an image".getBytes(), 422);

    upload(admin, UUID.randomUUID(), 0, png(200), 200);
    String stale = upload(admin, UUID.randomUUID(), 0, png(210), 409);
    assertTrue(stale.contains("VERSION_CONFLICT"), stale);

    UUID second = UUID.randomUUID();
    upload(admin, second, 1, png(220), 200);
    assertEquals(second.toString(), json(read(driver, 200)).get("passId").asText(), "the replacement is served");
    assertEquals(2, passCount(vehicleId), "the first pass is revoked, never deleted");
  }

  @Test
  void aWithdrawnPassIsNoLongerServed() throws Exception {
    UUID passId = UUID.randomUUID();
    upload(admin, passId, 0, png(200), 200);
    String payload = "{\"vehicleId\":\"" + vehicleId + "\",\"passId\":\"" + passId + "\",\"reason\":\"QR reissued\"}";
    command(driver, "vehicle:RevokeFuelPass", 1L, payload, 403);
    command(admin, "vehicle:RevokeFuelPass", 9L, payload, 409);
    command(admin, "vehicle:RevokeFuelPass", 1L, payload, 200);
    read(driver, 404);
    command(admin, "vehicle:RevokeFuelPass", 2L, payload, 409);
  }

  // ---- helpers ----

  private String upload(String who, UUID passId, long expected, byte[] content, int status) throws Exception {
    MvcResult r =
        http.perform(
                put("/api/execution/vehicles/" + vehicleId + "/fuel-pass/" + passId + "?expectedVersion=" + expected)
                    .cookie(session(who))
                    .contentType(MediaType.IMAGE_PNG)
                    .content(content))
            .andReturn();
    assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private String read(String who, int status) throws Exception {
    return fetch(who, "/api/execution/vehicles/" + vehicleId + "/fuel-pass", status);
  }

  private String fetch(String who, String path, int status) throws Exception {
    MvcResult r = http.perform(get(path).cookie(session(who))).andReturn();
    assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private String command(String who, String kind, Long version, String payload, int status) throws Exception {
    String body =
        "{\"commandId\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + kind + "\",\"expectedVersion\":" + version
            + ",\"payload\":" + payload + ",\"clientRecordedAt\":\"" + clock.now() + "\"}";
    MvcResult r =
        http.perform(post("/api/commands").cookie(session(who)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn();
    assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private Cookie session(String email) {
    return new Cookie(AuthController.COOKIE, login.login(email, PASSWORD, null, "127.0.0.1"));
  }

  private JsonNode json(String body) throws Exception {
    return mapper.readTree(body);
  }

  private long passCount(String vehicle) {
    return ((Number)
            database
                .asSystemSeparately(
                    ModuleRole.EXECUTION,
                    () -> database.queryOne("SELECT count(*) AS n FROM execution.fuel_passes WHERE vehicle_id = ?", vehicle))
                .get("n"))
        .longValue();
  }

  private boolean assigned(String vehicle, LocalDate from, LocalDate until) {
    return database.asSystemSeparately(
        ModuleRole.IAM,
        () -> database.queryOne(
            "SELECT 1 FROM iam.vehicle_driver_assignments WHERE vehicle_id = ?"
                + " AND validity && daterange(?::date, ?::date) LIMIT 1",
            vehicle, Date.valueOf(from), Date.valueOf(until)) != null);
  }

  private boolean audited(String action, String decision) {
    return !database
        .asSystemSeparately(
            ModuleRole.INTEGRATION,
            () -> database.query(
                "SELECT 1 FROM integration.audit_log WHERE action = ? AND decision = ? AND resource = ?",
                action, decision, "wpt:execution:vehicle:" + vehicleId))
        .isEmpty();
  }

  private static byte[] png(int size) {
    byte[] out = new byte[size];
    byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(header, 0, out, 0, header.length);
    for (int i = header.length; i < size; i++) {
      out[i] = (byte) (i * 31 + size);
    }
    return out;
  }
}
