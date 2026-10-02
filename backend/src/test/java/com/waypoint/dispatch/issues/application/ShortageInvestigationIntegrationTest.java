package com.waypoint.dispatch.issues.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryCompleted;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptConfirmed;
import com.waypoint.dispatch.support.ReceiptIssuesSupport;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A short receipt against the loading check of the same order (R-RCP-07, Figma
 * store manager "06-5"). The loader's flags are written into Loading the way its
 * handlers would leave them; the receipt goes through the API.
 *
 * <p>The order has two lines, P-1 x10 and P-2 x4. The loader flags P-1 as missing
 * at the dock, so all ten units of it stay behind.
 */
class ShortageInvestigationIntegrationTest extends ReceiptIssuesSupport {
  Order order;
  UUID tripId;

  @BeforeEach
  void loadedWithOneLineMissing() {
    order = deliveredOrder(outlet);
    tripId = UUID.randomUUID();
    LocalDate day = reference.nextOperatingDay(
        LocalDate.of(2040, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 15_000)));
    String vehicle = reference.availableVehicles(depot, day, null).get(0).vehicleId();
    UUID shortfall = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    database.asSystem(
        ModuleRole.LOADING,
        () -> {
          database.update(
              "INSERT INTO loading.trips (trip_id, plan_version, plan_id, depot_code, service_date, vehicle_id,"
                  + " trip_number, trips_for_vehicle, brand_code, district_name, temperature, planned_departure,"
                  + " dock_code, weight_cap_kg, volume_cap_m3, received_at)"
                  + " VALUES (?, 1, ?, ?, ?, ?, 1, 1, ?, ?, 'ambient', ?, 'Dock 1', 5000, 20, ?)",
              tripId, UUID.randomUUID(), depot, Date.valueOf(day), vehicle, outlet.brandCode(),
              outlet.districtName(), Time.valueOf(LocalTime.of(4, 30)), now);
          database.update(
              "INSERT INTO loading.stops (trip_id, plan_version, order_id, stop_sequence, order_ref, outlet_id,"
                  + " temperature, item_count, weight_kg, volume_m3, planned_arrival)"
                  + " VALUES (?, 1, ?, 1, ?, ?, 'ambient', 14, 80, 0.6, ?)",
              tripId, order.orderId(), order.orderRef(), outlet.outletId(), Time.valueOf(LocalTime.of(5, 30)));
          // The dock work on the trip: checks and shortfalls hang off it.
          database.update(
              "INSERT INTO loading.sessions (trip_id, depot_code, plan_version, status, started_at, updated_at)"
                  + " VALUES (?, ?, 1, 'in_progress', ?, ?)",
              tripId, depot, now, now);
          database.update(
              "INSERT INTO loading.items (trip_id, plan_version, order_id, line_no, product_id, units)"
                  + " VALUES (?, 1, ?, 1, 'P-1', 10), (?, 1, ?, 2, 'P-2', 4)",
              tripId, order.orderId(), tripId, order.orderId());
          database.update(
              "INSERT INTO loading.shortfalls (shortfall_id, trip_id, plan_version, order_id, line_no, kind,"
                  + " missing_units, reason, reported_by, reported_at)"
                  + " VALUES (?, ?, 1, ?, 1, 'missing', 10, 'not on the shelf', ?, ?)",
              shortfall, tripId, order.orderId(), loader.id(), now);
          database.update(
              "INSERT INTO loading.item_checks (check_id, trip_id, plan_version, order_id, line_no, attempt, status,"
                  + " units, reason, shortfall_id, actor_user_id, command_id, recorded_at)"
                  + " VALUES (?, ?, 1, ?, 1, 1, 'missing', 0, 'not on the shelf', ?, ?, ?, ?),"
                  + "        (?, ?, 1, ?, 2, 1, 'loaded', 4, NULL, NULL, ?, ?, ?)",
              UUID.randomUUID(), tripId, order.orderId(), shortfall, loader.id(), UUID.randomUUID(), now,
              UUID.randomUUID(), tripId, order.orderId(), loader.id(), UUID.randomUUID(), now);
        });
    deliver(
        "receipt.on-delivery-completed",
        new DeliveryCompleted(
            UUID.randomUUID(), order.orderId(), tripId, order.outletId(), DeliveryOutcome.PARTIAL, Optional.of(4),
            Instant.now(), Optional.empty()),
        driver.id());
  }

  @Test
  void theStoreSeesWhatTheLoaderFlaggedOnItsOwnOrderAndNothingElseOfTheTrip() throws Exception {
    JsonNode check = read(manager, "/api/receipts/" + order.orderId() + "/custody", 200).get("loadingCheck");
    assertEquals("MISSING", check.get("status").asText());
    assertEquals("MISSING", check.get("items").get(0).get("status").asText());
    assertEquals("P-1", check.get("items").get(0).get("productId").asText());
    assertEquals("LOADED", check.get("items").get(1).get("status").asText());

    // Row by row: the outlet's own stop, never the trip header or another outlet's stop.
    assertEquals(1, rowsAs(manager.id(), "SELECT 1 FROM loading.stops WHERE trip_id = ?"));
    assertEquals(0, rowsAs(manager.id(), "SELECT 1 FROM loading.trips WHERE trip_id = ?"));
    assertEquals(0, rowsAs(manager.id(), "SELECT 1 FROM loading.sessions WHERE trip_id = ?"));
    assertEquals(0, rowsAs(stranger.id(), "SELECT 1 FROM loading.stops WHERE trip_id = ?"));
    assertEquals(0, rowsAs(stranger.id(), "SELECT 1 FROM loading.items WHERE trip_id = ?"));
    assertEquals(0, rowsAs(stranger.id(), "SELECT 1 FROM loading.item_checks WHERE trip_id = ?"));
    read(stranger, "/api/receipts/" + order.orderId() + "/custody", 404);
  }

  @Test
  void aShortageTheLoaderAlreadyFlaggedIsNotInvestigatedASecondTime() throws Exception {
    UUID receiptId = answerPartial("[{\"productId\":\"P-1\",\"receivedQuantity\":0}]", null);

    assertEquals(0, read(dispatcher, "/api/issues/by-subject?type=receipt&id=" + receiptId, 200).size(),
        "the shortage is already the loader's issue; a second would report the same goods twice");
  }

  @Test
  void aShortageBeyondWhatTheLoaderFlaggedIsInvestigated() throws Exception {
    UUID receiptId =
        answerPartial(
            "[{\"productId\":\"P-1\",\"receivedQuantity\":0},{\"productId\":\"P-2\",\"receivedQuantity\":2}]", null);

    JsonNode found = read(dispatcher, "/api/issues/by-subject?type=receipt&id=" + receiptId, 200);
    assertEquals(1, found.size(), "two units of P-2 left the dock and did not arrive (R-RCP-07)");
    assertEquals("RECEIPT_DISPUTE", found.get(0).get("type").asText());
  }

  @Test
  void whatTheStoreSaysIsInvestigatedEvenWhenTheLoaderExplainsTheCount() throws Exception {
    UUID receiptId = answerPartial("[{\"productId\":\"P-1\",\"receivedQuantity\":0}]", "Damaged: P-2 x1 carton wet");

    JsonNode found = read(dispatcher, "/api/issues/by-subject?type=receipt&id=" + receiptId, 200);
    assertEquals(1, found.size());
    assertTrue(found.get(0).get("description").asText().contains("Store's note: Damaged: P-2 x1 carton wet"),
        found.get(0).get("description").asText());
  }

  // ---- fixtures ------------------------------------------------------------

  private UUID answerPartial(String lines, String note) throws Exception {
    String payload =
        "{\"orderId\":\"" + order.orderId() + "\",\"lines\":" + lines
            + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    JsonNode result = send(manager, envelope("receipt:ConfirmPartial", 1L, payload), 200).get("result");
    UUID receiptId = UUID.fromString(result.get("receiptId").asText());
    deliver(
        "issues.on-receipt-confirmed",
        new ReceiptConfirmed(receiptId, order.orderId(), order.outletId(), true, Instant.now()));
    return receiptId;
  }

  private int rowsAs(UUID actor, String sql) {
    return database.asModule(ModuleRole.LOADING, actor, () -> database.query(sql, tripId).size());
  }
}
