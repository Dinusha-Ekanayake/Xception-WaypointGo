package com.waypoint.dispatch.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.waypoint.dispatch.shared.event.DomainEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The event catalogue in docs/architecture/MODULES.md, held against the code.
 *
 * <p>Modules connect through these types, so the catalogue must be complete and
 * unambiguous: every documented event exists as a contract record, no two
 * records claim the same type, and every type is spelled the same way.
 */
class EventCatalogueTest {

  /** The catalogue as documented. Adding an event means adding it here and to MODULES.md. */
  private static final Set<String> CATALOGUE =
      Set.of(
          "order.placed",
          "order.amended",
          "order.cancelled",
          "order.auto_deferred",
          "orders.closed",
          "plan.published",
          "plan.revised",
          "order.deferred",
          "order.unservable",
          "plan.store_contacted",
          "loading.started",
          "loading.shortfall",
          "loading.interchange_requested",
          "trip.released",
          "delivery.started",
          "delivery.completed",
          "delivery.failed",
          "eta.changed",
          "vehicle.fault_reported",
          "road.disruption_reported",
          "receipt.confirmed",
          "receipt.disputed",
          "receipt.handover_confirmed",
          "receipt.auto_closed",
          "issue.raised",
          "issue.resolved",
          "issue.escalated",
          "shortfall.resolved",
          "redelivery.requested",
          "warehouse.order_status_changed",
          "warehouse.discrepancy_found",
          "catalogue.synced",
          "reference.version_published",
          "vehicle.status_changed",
          "calendar.overridden");

  @Test
  void everyEventTypeIsUniqueWellFormedAndCatalogued() throws Exception {
    Map<String, String> typeToClass = new HashMap<>();
    for (JavaClass event :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.waypoint.dispatch")
            .that(JavaClass.Predicates.implement(DomainEvent.class))) {
      String type = (String) Class.forName(event.getName()).getField("TYPE").get(null);

      assertTrue(
          type.matches("^[a-z]+(\\.[a-z_]+)+$"),
          event.getName() + " has a malformed type " + type);
      String clash = typeToClass.put(type, event.getName());
      assertTrue(clash == null, type + " is declared by both " + clash + " and " + event.getName());
    }

    assertEquals(
        new TreeSet<>(CATALOGUE),
        new TreeSet<>(typeToClass.keySet()),
        "the event records must match the documented catalogue exactly");
  }
}
