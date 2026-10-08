package com.waypoint.dispatch.execution;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The dispatcher's Live read at the scale it was designed for: 1,280 trips on
 * one day, 8 stops each, spread over every depot in the database (two in the
 * reference data, so 640 trips a depot) (B-04). The Live screen asks for each depot's run sheets
 * at once, every 15 s with the change stream up, so one dispatcher overseeing
 * every depot sends one read per depot together.
 *
 * <p>The rows are written directly, as the release handler would leave them:
 * what is measured is the read, its row-level security per row and its JSON,
 * not the seeding. The ceilings are regression guards set well above what this
 * measured on a laptop (recorded in the roadmap's B-04 table), not SLOs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ExecutionTestConfig.class)
@ExtendWith(TestDatabase.class)
class LiveReadLoadTest {
  static final int TRIPS = 1_280;
  static final int STOPS_PER_TRIP = 8;
  static final int ROUNDS = 5;
  static final String PASSWORD = "load-test-only-1";

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
  void twelveHundredAndEightyTripsReadTogetherWithinTheCeiling() throws Exception {
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    LocalDate day = LocalDate.of(2031, 1, 6).plusDays((long) (Math.random() * 3000));
    List<String> depots = depots();
    int tripsPerDepot = (TRIPS + depots.size() - 1) / depots.size();
    seed(day, depots, tripsPerDepot);

    String dispatcher = "load-" + UUID.randomUUID().toString().substring(0, 8) + "@execution.test";
    accounts.createAccount(dispatcher, "Load Dispatcher", PASSWORD, "dispatcher");
    for (String depot : depots) accounts.grantDepot(dispatcher, depot);
    Cookie session = new Cookie(AuthController.COOKIE, login.login(dispatcher, PASSWORD, null, "127.0.0.1"));

    // One warm-up round: the first read of each depot pays for plans and caches.
    readAll(session, depots, day);
    List<Long> single = Collections.synchronizedList(new ArrayList<>());
    List<Long> rounds = new ArrayList<>();
    long bytes = 0;
    int stops = 0;
    for (int round = 0; round < ROUNDS; round++) {
      long started = System.nanoTime();
      List<MvcResult> results = readAll(session, depots, day, single);
      rounds.add((System.nanoTime() - started) / 1_000_000);
      if (round == 0) {
        for (MvcResult r : results) {
          byte[] body = r.getResponse().getContentAsByteArray();
          bytes += body.length;
          for (JsonNode sheet : mapper.readTree(body)) stops += sheet.get("stops").size();
        }
      }
    }
    assertEquals(depots.size() * tripsPerDepot * STOPS_PER_TRIP, stops, "every stop is read, none twice");

    long singleP95 = percentile(single, 95);
    long roundP95 = percentile(rounds, 95);
    System.out.printf(
        "Live read load: %d trips over %d depots, %d stops, %d KB a round; one depot p95 %d ms, max %d ms;"
            + " every depot together p95 %d ms%n",
        depots.size() * tripsPerDepot, depots.size(), stops, bytes / 1024, singleP95, Collections.max(single), roundP95);
    assertTrue(singleP95 < 3_000, "one depot's " + tripsPerDepot + " trips, p95 " + singleP95 + " ms");
    assertTrue(roundP95 < 5_000, "every depot read together, p95 " + roundP95 + " ms");
  }

  private List<MvcResult> readAll(Cookie session, List<String> depots, LocalDate day) throws Exception {
    return readAll(session, depots, day, new ArrayList<>());
  }

  /** The Live screen's read: every depot at once, as `Promise.all` sends them. */
  private List<MvcResult> readAll(Cookie session, List<String> depots, LocalDate day, List<Long> times)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(depots.size());
    try {
      List<Future<MvcResult>> pending = new ArrayList<>();
      for (String depot : depots) {
        pending.add(
            pool.submit(
                () -> {
                  long started = System.nanoTime();
                  MvcResult r =
                      http.perform(get("/api/execution/run-sheets?depot=" + depot + "&date=" + day).cookie(session))
                          .andReturn();
                  times.add((System.nanoTime() - started) / 1_000_000);
                  assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
                  return r;
                }));
      }
      List<MvcResult> results = new ArrayList<>();
      for (Future<MvcResult> f : pending) results.add(f.get());
      return results;
    } finally {
      pool.shutdown();
    }
  }

  private List<String> depots() {
    List<Map<String, Object>> rows =
        database.asSystem(
            ModuleRole.EXECUTION,
            () -> database.query("SELECT DISTINCT depot_code FROM ref.depots ORDER BY depot_code"));
    List<String> depots = rows.stream().map(r -> (String) r.get("depot_code")).toList();
    assertTrue(!depots.isEmpty(), "the reference data has depots");
    return depots;
  }

  /** The trips and their stops, each stop with two lines, in three statements. */
  private void seed(LocalDate day, List<String> depots, int tripsPerDepot) {
    Date date = Date.valueOf(day);
    String[] depotArray = depots.toArray(String[]::new);
    database.asSystem(
        ModuleRole.EXECUTION,
        () -> {
          database.update(
              "INSERT INTO execution.trips (trip_id, plan_id, plan_version, depot_code, vehicle_id, service_date, released_at)"
                  + " SELECT gen_random_uuid(), gen_random_uuid(), 1, (?::text[])[d], v.vehicle_id, ?, now()"
                  + " FROM generate_series(1, ?) d CROSS JOIN generate_series(1, ?) t"
                  + " CROSS JOIN LATERAL (SELECT vehicle_id FROM ref.vehicle_registry"
                  + "   ORDER BY vehicle_id OFFSET ((d * ? + t) % (SELECT count(*) FROM ref.vehicle_registry)) LIMIT 1) v",
              depotArray, date, depots.size(), tripsPerDepot, tripsPerDepot);
          database.update(
              "INSERT INTO execution.delivery_records (delivery_id, trip_id, order_id, outlet_id, depot_code, vehicle_id,"
                  + " service_date, stop_sequence, item_count, planned_arrival, window_open, window_close, released_at,"
                  + " server_recorded_at, trip_stop_count)"
                  + " SELECT gen_random_uuid(), t.trip_id, gen_random_uuid(), o.outlet_id, t.depot_code, t.vehicle_id,"
                  + " t.service_date, s, 3, time '05:00' + s * interval '20 minutes', time '05:00', time '11:00', now(), now(), ?"
                  + " FROM execution.trips t CROSS JOIN generate_series(1, ?) s"
                  + " CROSS JOIN LATERAL (SELECT outlet_id FROM ref.outlet_registry"
                  + "   ORDER BY outlet_id OFFSET (abs(hashtext(t.trip_id::text || s)) % (SELECT count(*) FROM ref.outlet_registry)) LIMIT 1) o"
                  + " WHERE t.service_date = ?",
              STOPS_PER_TRIP, STOPS_PER_TRIP, date);
          database.update(
              "INSERT INTO execution.delivery_lines (delivery_id, product_id, ordered_units)"
                  + " SELECT d.delivery_id, p, 4 FROM execution.delivery_records d"
                  + " CROSS JOIN unnest(ARRAY['P-1', 'P-2']) p WHERE d.service_date = ?",
              date);
          return null;
        });
  }

  private static long percentile(List<Long> values, int p) {
    List<Long> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
  }
}
