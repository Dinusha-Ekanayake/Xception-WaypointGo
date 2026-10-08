package com.waypoint.dispatch.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
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
 * FF-07 end to end: an administrator uploads a driver's photo with consent on
 * record; the driver, the depot's dispatcher and the store the driver delivers
 * to today see it; anyone else is refused and audited; removing it deletes the
 * image and keeps who, when and why (P-PD-1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ExecutionTestConfig.class)
@ExtendWith(TestDatabase.class)
class ProfilePhotoIntegrationTest {
  static final String PASSWORD = "FaceToFace2026!";
  static final String CONSENT = "Signed the photo consent form at the depot";

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
  String depot;
  String vehicleId;
  UUID driverId;
  UUID deliveryId;
  String admin;
  String driver;
  String otherDriver;
  String dispatcher;
  String store;
  String otherStore;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @BeforeEach
  void setUp() {
    clock.reset();
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    depot = reference.outlet("OUT001", null).orElseThrow().depotCode();
    do {
      day = TestDates.unusedDay(reference::nextOperatingDay);
      vehicleId = reference.availableVehicles(depot, day, null).get((int) (Math.random() * 5)).vehicleId();
    } while (assigned(vehicleId, day.minusDays(1), day.plusDays(2)));
    clock.set(day.atTime(LocalTime.of(6, 0)).atZone(Clock.OPERATING_ZONE).toInstant());

    String run = UUID.randomUUID().toString().substring(0, 8);
    admin = "pa-" + run + "@photo.test";
    driver = "pd-" + run + "@photo.test";
    otherDriver = "po-" + run + "@photo.test";
    dispatcher = "pp-" + run + "@photo.test";
    store = "ps-" + run + "@photo.test";
    otherStore = "px-" + run + "@photo.test";
    accounts.createAccount(admin, "Admin", PASSWORD, "admin");
    driverId = accounts.createAccount(driver, "Driver", PASSWORD, "driver");
    accounts.createAccount(otherDriver, "Other Driver", PASSWORD, "driver");
    accounts.createAccount(dispatcher, "Dispatcher", PASSWORD, "dispatcher");
    accounts.grantDepot(dispatcher, depot);
    accounts.createAccount(store, "Store", PASSWORD, "store_manager");
    accounts.grantOutlet(store, "OUT001");
    accounts.createAccount(otherStore, "Other Store", PASSWORD, "store_manager");
    accounts.grantOutlet(otherStore, "OUT002");
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          database.update(
              "INSERT INTO iam.vehicle_driver_assignments (vehicle_id, driver_user_id, validity)"
                  + " VALUES (?, ?, daterange(?::date, ?::date))",
              vehicleId, driverId, Date.valueOf(day), Date.valueOf(day.plusDays(1)));
        });
    deliveryId = delivery("OUT001");
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  @Test
  void thePeopleWhoWorkWithTheDriverTodaySeeThePhotoAndNobodyElse() throws Exception {
    byte[] image = png(400);
    UUID photoId = UUID.randomUUID();
    JsonNode receipt = json(upload(admin, photoId, 0, CONSENT, image, 200));
    assertTrue(receipt.get("stored").asBoolean());
    assertEquals(1, receipt.get("version").asLong());
    // The same upload again is the same photo; another image under its id is not.
    assertFalse(json(upload(admin, photoId, 0, CONSENT, image, 200)).get("stored").asBoolean());
    upload(admin, photoId, 1, CONSENT, png(300), 409);
    // A screen that was out of date collides instead of silently replacing.
    upload(admin, UUID.randomUUID(), 0, CONSENT, png(300), 409);
    // No consent on record, no photo; nobody but the administrator uploads.
    upload(admin, UUID.randomUUID(), 1, "", png(300), 422);
    upload(driver, UUID.randomUUID(), 1, CONSENT, png(300), 403);

    assertArrayEquals(image, photo(driver, "/api/accounts/" + driverId + "/photo", 200));
    assertArrayEquals(image, photo(dispatcher, "/api/accounts/" + driverId + "/photo", 200));
    assertArrayEquals(image, photo(store, "/api/execution/deliveries/" + deliveryId + "/driver-photo", 200));

    photo(otherDriver, "/api/accounts/" + driverId + "/photo", 403);
    photo(store, "/api/accounts/" + driverId + "/photo", 403);
    assertTrue(audited("iam:ReadProfilePhoto", "DENY"), "a refused read is audited");
    // Another store cannot read this delivery at all.
    photo(otherStore, "/api/execution/deliveries/" + deliveryId + "/driver-photo", 404);

    // The next day the store no longer sees who delivered yesterday.
    clock.set(day.plusDays(1).atTime(LocalTime.of(6, 0)).atZone(Clock.OPERATING_ZONE).toInstant());
    photo(store, "/api/execution/deliveries/" + deliveryId + "/driver-photo", 404);
  }

  @Test
  void removingAPhotoDeletesTheImageAndKeepsWhoRemovedItAndWhy() throws Exception {
    UUID photoId = UUID.randomUUID();
    upload(admin, photoId, 0, CONSENT, png(400), 200);
    long rowVersion = status().get("photoRowVersion").asLong();
    String payload = "{\"userId\":\"" + driverId + "\",\"reason\":\"The driver asked for it to be removed\"}";

    command(dispatcher, "iam:RemoveProfilePhoto", rowVersion, payload, 403);
    command(admin, "iam:RemoveProfilePhoto", rowVersion + 5, payload, 409);
    command(admin, "iam:RemoveProfilePhoto", rowVersion, "{\"userId\":\"" + driverId + "\",\"reason\":\"\"}", 422);
    command(admin, "iam:RemoveProfilePhoto", rowVersion, payload, 200);

    photo(driver, "/api/accounts/" + driverId + "/photo", 404);
    // Still listed, with nothing current, so the next upload's expected version is 1.
    assertTrue(status().get("photoId").isNull());
    assertEquals(1, status().get("version").asLong());
    upload(admin, UUID.randomUUID(), 0, CONSENT, png(300), 409);
    photo(store, "/api/execution/deliveries/" + deliveryId + "/driver-photo", 404);
    Map<String, Object> row =
        database.asSystemSeparately(
            ModuleRole.IAM,
            () -> database.queryOne(
                "SELECT content, removed_by, removal_reason FROM iam.profile_photos WHERE photo_id = ?", photoId));
    assertNull(row.get("content"), "the image itself is deleted");
    assertEquals("The driver asked for it to be removed", row.get("removal_reason"));
    assertTrue(row.get("removed_by") != null);
  }

  private JsonNode status() throws Exception {
    MvcResult r = http.perform(get("/api/accounts/photos").cookie(session(admin))).andReturn();
    assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    for (JsonNode s : json(r.getResponse().getContentAsString())) {
      if (s.get("userId").asText().equals(driverId.toString())) return s;
    }
    throw new AssertionError("the driver's photo is listed");
  }

  private String upload(String who, UUID photoId, long expected, String consent, byte[] content, int status)
      throws Exception {
    MvcResult r =
        http.perform(
                put("/api/accounts/" + driverId + "/photo/" + photoId + "?expectedVersion=" + expected + "&consent="
                        + URLEncoder.encode(consent, StandardCharsets.UTF_8))
                    .cookie(session(who))
                    .contentType(MediaType.IMAGE_PNG)
                    .content(content))
            .andReturn();
    assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private byte[] photo(String who, String path, int status) throws Exception {
    MvcResult r = http.perform(get(path).cookie(session(who))).andReturn();
    assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsByteArray();
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

  /** A released stop at an outlet on the test day, carried by the test vehicle, as the release handler leaves it. */
  private UUID delivery(String outletId) {
    UUID trip = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    database.asSystem(
        ModuleRole.EXECUTION,
        () -> {
          database.update(
              "INSERT INTO execution.trips (trip_id, plan_id, plan_version, depot_code, vehicle_id, service_date, released_at)"
                  + " VALUES (?, ?, 1, ?, ?, ?, now())",
              trip, UUID.randomUUID(), depot, vehicleId, Date.valueOf(day));
          database.update(
              "INSERT INTO execution.delivery_records (delivery_id, trip_id, order_id, outlet_id, depot_code, vehicle_id,"
                  + " service_date, stop_sequence, item_count, planned_arrival, window_open, window_close, released_at,"
                  + " server_recorded_at, trip_stop_count)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, 1, 3, time '07:00', time '05:00', time '11:00', now(), now(), 1)",
              id, trip, UUID.randomUUID(), outletId, depot, vehicleId, Date.valueOf(day));
          return null;
        });
    return id;
  }

  private Cookie session(String email) {
    return new Cookie(AuthController.COOKIE, login.login(email, PASSWORD, null, "127.0.0.1"));
  }

  private JsonNode json(String body) throws Exception {
    return mapper.readTree(body);
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
                action, decision, "wpt:iam:user:" + driverId))
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
