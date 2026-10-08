package com.waypoint.dispatch.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.support.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * R-PLN-47: the reload time a depot actually takes, measured from Loading's
 * released second trips, sits beside the rule on the administrator's screen,
 * and only for the depots the reader may see (row-level security in Loading,
 * read as the same person through its contract).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(TestDatabase.class)
class ReloadMeasureIntegrationTest {
  static final String PASSWORD = "reload-test-only-1";

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired ImportReferenceDataHandler referenceImport;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @Test
  void theMeasuredReloadOfTheAdministratorsDepotSitsBesideTheRuleAndNoOtherDepot() throws Exception {
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    List<String> depots = database.asSystem(ModuleRole.LOADING, () -> database.query(
            "SELECT DISTINCT depot_code FROM ref.depots ORDER BY depot_code"))
        .stream().map(r -> (String) r.get("depot_code")).toList();
    assertTrue(depots.size() >= 2, "two depots in the reference data");
    String mine = depots.get(0);
    String other = depots.get(1);

    // Fifteen reloads at each depot this week: 30 minutes at mine, 90 at the other.
    LocalDate day = LocalDate.now(ZoneId.of("Asia/Colombo")).minusDays(2);
    reloads(mine, day, 30, 15);
    reloads(other, day, 90, 15);

    String admin = "reload-" + UUID.randomUUID().toString().substring(0, 8) + "@planning.test";
    accounts.createAccount(admin, "Reload Admin", PASSWORD, "admin");
    accounts.grantDepot(admin, mine);
    Cookie session = new Cookie(AuthController.COOKIE, login.login(admin, PASSWORD, null, "127.0.0.1"));

    MvcResult result = http.perform(get("/api/admin/constraints/planning").cookie(session)).andReturn();
    assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    JsonNode measured = mapper.readTree(result.getResponse().getContentAsString()).get("measured");

    List<String> seen = new ArrayList<>();
    measured.forEach(m -> seen.add(m.get("depotCode").asText()));
    assertTrue(seen.contains(mine), "the administrator's depot is measured: " + measured);
    assertTrue(!seen.contains(other), "a depot outside the administrator's scope is not read: " + measured);
    JsonNode m = measured.get(seen.indexOf(mine));
    assertEquals("trip.reload.min", m.get("key").asText());
    assertTrue(m.get("samples").asInt() >= 15);
    assertEquals(30, m.get("median").asLong(), "every reload here took 30 minutes");
  }

  /** {@code count} released second trips at a depot on a day, each loaded in {@code minutes}. */
  private void reloads(String depot, LocalDate day, int minutes, int count) {
    List<Map<String, Object>> vehicles = database.asSystem(ModuleRole.LOADING, () -> database.query(
        "SELECT vehicle_id FROM ref.vehicle_registry ORDER BY vehicle_id LIMIT ?", count));
    Instant released = day.atTime(9, 0).atZone(ZoneId.of("Asia/Colombo")).toInstant();
    database.asSystem(ModuleRole.LOADING, () -> {
      for (Map<String, Object> v : vehicles) {
        UUID trip = UUID.randomUUID();
        database.update(
            "INSERT INTO loading.trips (trip_id, plan_version, plan_id, depot_code, service_date, vehicle_id,"
                + " trip_number, trips_for_vehicle, brand_code, district_name, temperature, planned_departure,"
                + " dock_code, weight_cap_kg, volume_cap_m3, received_at)"
                + " VALUES (?, 1, ?, ?, ?, ?, 2, 2, 'Fresh', 'Colombo', 'ambient', '09:00', 'Dock 1', 1000, 10, now())",
            trip, UUID.randomUUID(), depot, Date.valueOf(day), v.get("vehicle_id"));
        database.update(
            "INSERT INTO loading.sessions (trip_id, depot_code, plan_version, status, started_at, released_at,"
                + " released_by, seal_number, updated_at)"
                + " VALUES (?, ?, 1, 'released', ?, ?, ?, 'SEAL-1', now())",
            trip, depot, Timestamp.from(released.minusSeconds(minutes * 60L)), Timestamp.from(released), UUID.randomUUID());
      }
      return null;
    });
  }
}
