package com.waypoint.dispatch.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Planning's commands end to end through {@code POST /api/commands}: the bus
 * authorizes, the handler decides, and the draft or the publication, its
 * events, the receipt and the audit row commit together.
 *
 * <p>Demand is written straight into Ordering's table as the system, because
 * placing an order needs the warehouse and that is Ordering's test to make.
 */
class PlanningCommandIntegrationTest extends PlanningIntegrationSupport {

  @Test
  void aGeneratedDraftIsPublishedOnceWithItsEvents() throws Exception {
    UUID first = demand("ambient");
    UUID second = demand("ambient");

    JsonNode draft = generate(dispatcher, 200);
    UUID planId = UUID.fromString(draft.get("planId").asText());
    assertEquals("DRAFT", draft.get("status").asText());
    JsonNode view = mapper.readTree(read(dispatcher, "/api/plans/" + planId, 200));
    assertEquals(Set.of(first, second), allocatedOrders(view), "every order has exactly one decision");

    JsonNode published = mapper.readTree(send(dispatcher, publish(planId, 1L), 200)).get("result");
    assertEquals("PUBLISHED", published.get("status").asText());
    assertEquals(2, published.get("rowVersion").asInt());
    assertEquals(1, outbox(planId.toString(), "plan.published"), "plan.published travels with the plan");
    JsonNode current =
        mapper.readTree(read(dispatcher, "/api/plans/published?depot=" + depot + "&date=" + serviceDate, 200));
    assertEquals(planId.toString(), current.get("planId").asText());

    String again = send(dispatcher, envelope("plan:Generate", null, generatePayload()), 409);
    assertTrue(again.contains("R-PLN-28"), "a published day is revised, not regenerated: " + again);
  }

  @Test
  void theOpenDraftIsFoundByDepotAndDayThroughEveryEdit() throws Exception {
    String path = "/api/plans/draft?depot=" + depot + "&date=" + serviceDate;
    UUID first = demand("ambient");
    demand("ambient");
    read(dispatcher, path, 404);

    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    assertEquals(planId.toString(), mapper.readTree(read(dispatcher, path, 200)).get("planId").asText());

    JsonNode next =
        mapper.readTree(send(dispatcher, defer(planId, 1L, first, "outlet asked to skip today"), 200)).get("result");
    JsonNode current = mapper.readTree(read(dispatcher, path, 200));
    assertEquals(next.get("planId").asText(), current.get("planId").asText(), "an edit moves the draft on");
    assertEquals("DRAFT", current.get("status").asText());

    send(dispatcher, publish(UUID.fromString(next.get("planId").asText()), 1L), 200);
    read(dispatcher, path, 404);

    UUID actor = userId(elsewhereEmail);
    long before = denials(actor, "plan:Read");
    read(elsewhere, path, 403);
    assertEquals(before + 1, denials(actor, "plan:Read"), "another depot's draft is 403 plus audit, not 404");
  }

  @Test
  void aNonOperatingDayIsNotPlanned() throws Exception {
    serviceDate = serviceDate.plusDays(1);
    for (int i = 0; i < 30 && reference.isOperating(serviceDate); i++) {
      serviceDate = serviceDate.plusDays(1);
    }
    assertFalse(reference.isOperating(serviceDate), "the calendar has no closed day within a month");

    String refused = send(dispatcher, envelope("plan:Generate", null, generatePayload()), 409);

    assertTrue(refused.contains("PLN-13"), refused);
  }

  @Test
  void generatingForAnotherDepotIsForbiddenAndAudited() throws Exception {
    UUID actor = userId(elsewhereEmail);
    long before = denials(actor, "plan:Generate");

    generate(elsewhere, 403);

    assertEquals(before + 1, denials(actor, "plan:Generate"), "403 plus audit, never an empty draft");
  }

  @Test
  void theSecondOfTwoEditsOnOneDraftIsRefusedWithWhatChanged() throws Exception {
    UUID first = demand("ambient");
    demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    String vehicle = vehicleOf(planId, first);

    JsonNode deferred =
        mapper.readTree(send(dispatcher, defer(planId, 1L, first, "outlet closed for stocktake"), 200)).get("result");
    UUID successor = UUID.fromString(deferred.get("planId").asText());
    assertEquals(2, deferred.get("planVersion").asInt(), "an edit is the next version of the draft");

    String stale = send(dispatcher, override(planId, 1L, first, vehicle, 1, "keep it on the truck"), 409);
    assertTrue(stale.contains("VERSION_CONFLICT"), stale);
    assertTrue(stale.contains(successor.toString()), "names the current draft: " + stale);
    assertTrue(stale.contains(first + ": served on " + vehicle + " trip 1 -> deferred"), "shows the diff: " + stale);

    JsonNode view = mapper.readTree(read(dispatcher, "/api/plans/" + planId, 200));
    assertEquals("CANCELLED", view.get("status").asText(), "the edited version is closed, never rewritten");
  }

  @Test
  void anOverrideThatBreaksARuleIsRefusedWithTheRule() throws Exception {
    UUID chilled = demand("chilled");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    Set<String> used = vehiclesOf(planId);
    String ambientTruck =
        reference.availableVehicles(depot, serviceDate, null).stream()
            .filter(v -> !v.refrigerated() && !used.contains(v.vehicleId()))
            .map(VehicleView::vehicleId)
            .sorted()
            .findFirst()
            .orElseThrow();

    // The preview names each place as the override will, and says which rule refuses it.
    JsonNode places = mapper.readTree(read(dispatcher, "/api/plans/preview/placements?order=" + chilled, 200));
    JsonNode onAmbient = null;
    boolean refusedSeen = false;
    for (JsonNode place : places) {
      assertFalse(refusedSeen && place.get("feasible").asBoolean(), "feasible places come first");
      refusedSeen |= !place.get("feasible").asBoolean();
      if (place.get("vehicleId").asText().equals(ambientTruck) && place.get("tripNumber").asInt() == 1) {
        onAmbient = place;
      }
    }
    assertTrue(onAmbient != null, "every vehicle of the depot is offered: " + places);
    assertFalse(onAmbient.get("feasible").asBoolean());
    assertEquals("R-PLN-02", onAmbient.get("bindingRule").asText());
    read(elsewhere, "/api/plans/preview/placements?order=" + chilled, 404);

    String refused = send(dispatcher, override(planId, 1L, chilled, ambientTruck, 1, "no reefer free"), 409);

    assertTrue(refused.contains("R-PLN-02"), "a chilled order needs a reefer: " + refused);
    JsonNode view = mapper.readTree(read(dispatcher, "/api/plans/" + planId, 200));
    assertEquals("DRAFT", view.get("status").asText(), "a refused override changes nothing");
  }

  @Test
  void publicationIsRefusedWhenTheDemandChanged() throws Exception {
    demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    demand("ambient");

    String refused = send(dispatcher, publish(planId, 1L), 409);

    assertTrue(refused.contains("PLN-07"), refused);
    read(dispatcher, "/api/plans/published?depot=" + depot + "&date=" + serviceDate, 404);
  }

  @Test
  void aDispatcherDeferralIsPublishedWithItsRuleAndReason() throws Exception {
    UUID first = demand("ambient");
    demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    JsonNode next =
        mapper.readTree(send(dispatcher, defer(planId, 1L, first, "outlet asked to skip today"), 200)).get("result");
    UUID successor = UUID.fromString(next.get("planId").asText());

    send(dispatcher, publish(successor, 1L), 200);

    List<Map<String, Object>> events = outboxRows(first.toString(), "order.deferred");
    assertEquals(1, events.size());
    String payload = String.valueOf(events.get(0).get("payload"));
    assertTrue(payload.contains("R-PLN-19") && payload.contains("outlet asked to skip today"), payload);
    JsonNode deferrals =
        mapper.readTree(read(dispatcher, "/api/plans/deferrals?depot=" + depot + "&date=" + serviceDate, 200));
    assertEquals(first.toString(), deferrals.get(0).get("orderId").asText());
    assertEquals(1, deferrals.get(0).get("skipCount").asInt());
  }

}
