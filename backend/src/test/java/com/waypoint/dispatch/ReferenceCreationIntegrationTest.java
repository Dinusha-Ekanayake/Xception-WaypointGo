package com.waypoint.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandBus;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.support.TestDatabase;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ExtendWith(TestDatabase.class)
class ReferenceCreationIntegrationTest {
  @Autowired Migrator migrator;
  @Autowired AccountAdminUseCase accounts;
  @Autowired CommandBus bus;
  @Autowired ObjectMapper mapper;
  @Autowired ImportReferenceDataHandler importer;
  @Autowired ReferenceQuery reference;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @Test
  void adminAdditionsSurviveImportAndDuplicateCodesAreRejected() throws Exception {
    migrator.migrate();
    importer.importFrom(Path.of("../data"), null);
    String email = "reference-create-" + UUID.randomUUID() + "@waypoint.test";
    UUID adminId = accounts.createAccount(email, "Reference Admin", "IntegrationTest2026!", "admin");
    accounts.grantDepot(email, "Peliyagoda");
    Actor admin = new Actor(adminId, null);
    String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String depot = "NEW" + suffix;
    String vehicle = "VEH" + suffix;
    String outlet = "OUT" + suffix;
    UUID before = reference.currentVersionId().orElseThrow();

    command(admin, "reference:CreateDepot", "{\"code\":\"" + depot
        + "\",\"name\":\"New depot\",\"timezone\":\"Asia/Colombo\","
        + "\"latitude\":\"6.0535\",\"longitude\":\"80.2210\","
        + "\"locationPrecision\":\"approximate\"}");
    assertTrue(reference.depot(depot, null).isPresent());
    assertTrue(reference.depot(depot, before).isEmpty());

    command(admin, "reference:CreateVehicle", "{\"vehicleId\":\"" + vehicle
        + "\",\"depotCode\":\"Peliyagoda\",\"type\":\"van\","
        + "\"temperature\":\"reefer\",\"weightCapKg\":\"1200\","
        + "\"volumeCapM3\":\"8.5\",\"fuelType\":\"diesel\","
        + "\"kmPerL\":\"11.2\",\"weeklyFuelQuotaL\":\"280\"}");
    assertTrue(reference.vehicle(vehicle, null).isPresent());

    command(admin, "reference:CreateOutlet", "{\"outletId\":\"" + outlet
        + "\",\"brand\":\"Fresh\",\"district\":\"Colombo\","
        + "\"depotCode\":\"Peliyagoda\",\"dockType\":\"rear_dock\","
        + "\"parking\":\"normal\",\"windowOpen\":\"05:00\","
        + "\"windowClose\":\"08:00\"}");
    assertTrue(reference.outlet(outlet, null).isPresent());

    importer.importFrom(Path.of("../data"), null);
    assertTrue(reference.depot(depot, null).isPresent());
    assertTrue(reference.vehicle(vehicle, null).isPresent());
    assertTrue(reference.outlet(outlet, null).isPresent());
    DomainException duplicate = assertThrows(DomainException.class,
        () -> command(admin, "reference:CreateDepot", "{\"code\":\"" + depot
            + "\",\"name\":\"Again\",\"latitude\":\"6.0535\","
            + "\"longitude\":\"80.2210\",\"locationPrecision\":\"approximate\"}"));
    assertEquals(ErrorCode.CONFLICT, duplicate.code());
  }

  private void command(Actor actor, String kind, String payload) throws Exception {
    bus.dispatch(actor, new Command(UUID.randomUUID(), kind, null, mapper.readTree(payload), null));
  }
}
