package com.waypoint.dispatch.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.ordering.application.DeliveryDaySeed;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.application.FleetDaySeed;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The delivery-day seed (#114) leaves a fresh database with the Task 2B peak day
 * as demand Planning can allocate, the scenario's workshop vehicles out for that
 * day, and does nothing the second time.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(
    named = "TEST_DATABASE_URL",
    matches = ".+",
    disabledReason = "Set TEST_DATABASE_URL to a dedicated database to run integration tests")
class DeliveryDaySeedIntegrationTest {
  static final Path DATA = Path.of("../data");

  @Autowired Migrator migrator;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;
  @Autowired DeliveryDaySeed seed;
  @Autowired FleetDaySeed fleet;
  @Autowired OrderQuery orders;
  @Autowired Database database;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", () -> System.getenv("TEST_DATABASE_URL"));
  }

  @BeforeAll
  static void guardAgainstTheApplicationDatabase() {
    String application = System.getenv("DATABASE_URL");
    if (application != null && application.equals(System.getenv("TEST_DATABASE_URL"))) {
      throw new IllegalStateException("TEST_DATABASE_URL must differ from DATABASE_URL");
    }
  }

  @Test
  void seedsThePeakDayOnceAsPlannableDemand() {
    migrator.migrate();
    referenceImport.importFrom(DATA, null);

    DeliveryDaySeed.Outcome first = seed.seed(DATA);
    fleet.seed(DATA, first.serviceDate());
    DeliveryDaySeed.Outcome again = seed.seed(DATA);

    assertEquals("Peliyagoda", first.depotCode());
    assertTrue(reference.isOperating(first.serviceDate()), "seeded on an operating day");
    assertEquals(0, again.placed(), "a second run places nothing");
    assertEquals(first.serviceDate(), again.serviceDate());

    long seeded =
        database.asSystem(
            ModuleRole.ORDERING,
            () -> {
              Map<String, Object> row =
                  database.queryOne(
                      "SELECT count(*) AS n FROM ordering.orders"
                          + " WHERE warehouse_order_ref LIKE 'SEED-WH-%' AND status = 'confirmed'"
                          + " AND delivery_date = ?",
                      java.sql.Date.valueOf(first.serviceDate()));
              return ((Number) row.get("n")).longValue();
            });
    assertEquals(85, seeded, "every scenario order is confirmed demand");
    assertTrue(
        database.asSystem(
                ModuleRole.ORDERING,
                () -> orders.confirmedDemand(first.depotCode(), first.serviceDate()))
            .size()
            >= 85,
        "Planning sees the seeded orders");

    long workshop =
        database.asSystem(
            ModuleRole.REF,
            () -> {
              Map<String, Object> row =
                  database.queryOne(
                      "SELECT count(*) AS n FROM ref.vehicle_day_status"
                          + " WHERE service_date = ? AND status = 'in_workshop'",
                      java.sql.Date.valueOf(first.serviceDate()));
              return ((Number) row.get("n")).longValue();
            });
    assertEquals(10, workshop, "the scenario's workshop vehicles are out that day");
  }
}
