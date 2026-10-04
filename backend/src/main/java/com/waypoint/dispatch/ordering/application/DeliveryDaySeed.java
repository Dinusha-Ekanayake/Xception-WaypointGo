package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.ordering.domain.DeliveryDate;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.domain.OrderLine;
import com.waypoint.dispatch.ordering.domain.OrderRef;
import com.waypoint.dispatch.ordering.domain.Reservation;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * One realistic delivery day for a fresh installation (#114), so the judge
 * walkthrough starts from confirmed orders instead of an empty day.
 *
 * <p>The day is the Task 2B peak day (scenario S1): 85 orders for Peliyagoda,
 * more than the available fleet can carry, so the dispatcher's plan has to
 * defer some. It lands on the first operating day still open for ordering.
 *
 * <p>A stand-in for the warehouse, like {@code LoadingFixture} is for Planning:
 * a fresh install has no warehouse key, and an order the warehouse never
 * reserved is {@code STOCK_UNKNOWN}, which is not demand. Each order is saved
 * {@code CONFIRMED} with a reservation built from the scenario's own weight,
 * volume and temperature, referenced {@code SEED-WH-...}, and {@code order.placed}
 * is published as a placement would. Cancelling one asks the real warehouse about
 * a reservation it never made.
 *
 * <p>Runs once per database: when any seeded order exists, it does nothing.
 */
@Component
public class DeliveryDaySeed {
  static final String SCENARIO = "S1";
  static final String WAREHOUSE_PREFIX = "SEED-WH-";

  private final Database database;
  private final JdbcOrderRepository orders;
  private final ReferenceQuery reference;
  private final DeliveryDateResolver dates;
  private final EventPublisher events;
  private final Clock clock;

  public DeliveryDaySeed(
      Database database,
      JdbcOrderRepository orders,
      ReferenceQuery reference,
      DeliveryDateResolver dates,
      EventPublisher events,
      Clock clock) {
    this.database = database;
    this.orders = orders;
    this.reference = reference;
    this.dates = dates;
    this.events = events;
    this.clock = clock;
  }

  /** What was seeded; {@code placed} is 0 when an earlier run already seeded a day. */
  public record Outcome(String depotCode, LocalDate serviceDate, int placed) {}

  public Outcome seed(Path dataDir) {
    List<Map<String, String>> rows =
        read(dataDir.resolve("Test Data").resolve("task2b_peak_day_scenarios.csv"));
    String depot = rows.get(0).get("depot");
    Instant now = clock.now();
    return database.asSystem(
        ModuleRole.ORDERING,
        () -> {
          Map<String, Object> earlier =
              database.queryOne(
                  "SELECT delivery_date::text AS delivery_date FROM ordering.orders WHERE warehouse_order_ref LIKE ? LIMIT 1",
                  WAREHOUSE_PREFIX + "%");
          if (earlier != null) {
            return new Outcome(depot, LocalDate.parse((String) earlier.get("delivery_date")), 0);
          }
          DeliveryDate date = dates.resolve(depot, now.atZone(Clock.OPERATING_ZONE).toLocalDate(), now);
          int placed = 0;
          for (Map<String, String> row : rows) {
            place(row, date, now);
            placed++;
          }
          return new Outcome(depot, date.delivery(), placed);
        });
  }

  private void place(Map<String, String> row, DeliveryDate date, Instant now) {
    String ref = row.get("order_ref");
    OutletView outlet =
        reference
            .outlet(row.get("outlet_id"), null)
            .orElseThrow(() -> new IllegalStateException("Unknown outlet in the seed: " + row.get("outlet_id")));
    // Derived, so the same scenario row on the same day is always the same order.
    UUID commandId = UUID.nameUUIDFromBytes((date.delivery() + "/" + ref).getBytes(StandardCharsets.UTF_8));
    int units = Integer.parseInt(row.get("order_units"));
    Reservation reservation =
        new Reservation(
            WAREHOUSE_PREFIX + ref,
            new BigDecimal(row.get("order_weight_kg")),
            new BigDecimal(row.get("order_volume_m3")),
            row.get("temp_requirement"),
            units);
    // The scenario gives totals, not products: one descriptive line per order.
    OrderLine line = new OrderLine("SEED-" + outlet.brandCode() + "-" + reservation.temperature(), units);
    Order order =
        Order.place(
            commandId,
            OrderRef.derive(Actor.SYSTEM_ID, commandId),
            outlet.outletId(),
            outlet.depotCode(),
            outlet.brandCode(),
            outlet.districtName(),
            date,
            Optional.of(reservation),
            List.of(line));
    orders.insert(order, Actor.SYSTEM_ID, now, commandId, Optional.empty());
    orders.recordStatus(
        order.orderId(),
        Optional.empty(),
        order.status(),
        "placed by the delivery-day seed (Task 2B " + SCENARIO + " " + ref + ")",
        Actor.SYSTEM_ID,
        Optional.empty(),
        now);
    events.publish(Actor.SYSTEM, OrderMessages.placed(order));
  }

  static List<Map<String, String>> read(Path file) {
    try {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      String[] header = lines.get(0).replace("﻿", "").split(",", -1);
      List<Map<String, String>> rows = new ArrayList<>();
      for (String line : lines.subList(1, lines.size())) {
        if (line.isBlank()) {
          continue;
        }
        String[] cells = line.split(",", -1);
        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
          row.put(header[i].trim(), i < cells.length ? cells[i].trim() : "");
        }
        rows.add(row);
      }
      return rows;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
