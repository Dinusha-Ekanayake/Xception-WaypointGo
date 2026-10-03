package com.waypoint.dispatch.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The dispatcher's decisions beyond placing and deferring, end to end through
 * {@code POST /api/commands}: lock, swap, keep deferred, fix the stop order and
 * tell a store. Each edits the open draft into its next version, so each is
 * refused when the caller saw an older one.
 */
class PlanningDecisionsIntegrationTest extends PlanningIntegrationSupport {

  // ---- envelopes ---------------------------------------------------------------

  static String planOrder(String kind, UUID planId, UUID orderId) {
    return envelope(kind, 1L, "{\"planId\":\"" + planId + "\",\"orderId\":\"" + orderId + "\"}");
  }

  static String swap(UUID planId, UUID out, UUID in, String reason) {
    return envelope(
        "plan:Swap", 1L,
        "{\"planId\":\"" + planId + "\",\"outOrderId\":\"" + out + "\",\"inOrderId\":\"" + in + "\",\"reason\":\""
            + reason + "\"}");
  }

  static String keepDeferred(UUID planId, UUID order, String reason) {
    return envelope(
        "plan:KeepDeferred", 1L,
        "{\"planId\":\"" + planId + "\",\"orderIds\":[\"" + order + "\"],\"reason\":\"" + reason + "\"}");
  }

  static String reorder(UUID planId, UUID tripId, List<UUID> orders, String reason) {
    String ids = String.join(",", orders.stream().map(o -> "\"" + o + "\"").toList());
    return envelope(
        "plan:ReorderStops", 1L,
        "{\"planId\":\"" + planId + "\",\"tripId\":\"" + tripId + "\",\"orderIds\":[" + ids + "],\"reason\":\""
            + reason + "\"}");
  }

  static String contactStore(UUID planId, UUID order, String message) {
    return envelope(
        "plan:ContactStore", null,
        "{\"planId\":\"" + planId + "\",\"orderId\":\"" + order + "\",\"message\":\"" + message + "\"}");
  }

  // ---- helpers -----------------------------------------------------------------

  UUID nextPlan(String response) throws Exception {
    return UUID.fromString(mapper.readTree(response).get("result").get("planId").asText());
  }

  JsonNode draft() throws Exception {
    return mapper.readTree(read(dispatcher, "/api/plans/draft?depot=" + depot + "&date=" + serviceDate, 200));
  }

  static JsonNode allocation(JsonNode plan, UUID orderId) {
    for (JsonNode a : plan.get("allocations")) {
      if (a.get("orderId").asText().equals(orderId.toString())) {
        return a;
      }
    }
    throw new AssertionError("order " + orderId + " is not in the plan");
  }

  // ---- lock --------------------------------------------------------------------

  @Test
  void aLockIsRecordedWithWhoHeldItAndAnUnlockLetsItGo() throws Exception {
    UUID first = demand("ambient");
    demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());

    UUID locked = nextPlan(send(dispatcher, planOrder("plan:Lock", planId, first), 200));
    JsonNode held = allocation(draft(), first);
    assertTrue(held.get("locked").asBoolean());
    assertEquals("ENGINE", held.get("source").asText(), "the engine placed it, a dispatcher holds it");
    assertFalse(held.get("decidedBy").isNull(), "rule 8: who held it");

    String stale = send(dispatcher, planOrder("plan:Lock", planId, first), 409);
    assertTrue(stale.contains("VERSION_CONFLICT"), stale);

    send(dispatcher, planOrder("plan:Unlock", locked, first), 200);
    assertFalse(allocation(draft(), first).get("locked").asBoolean());
  }

  @Test
  void anotherDepotsDispatcherCannotLockInThisPlan() throws Exception {
    UUID first = demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());

    send(elsewhere, planOrder("plan:Lock", planId, first), 404);

    assertFalse(allocation(draft(), first).get("locked").asBoolean());
  }

  // ---- swap --------------------------------------------------------------------

  @Test
  void aSwapTradesAServedOrderForADeferredOneAndSaysWho() throws Exception {
    UUID a = demand("ambient");
    UUID b = demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    UUID withADeferred =
        nextPlan(send(dispatcher, defer(planId, 1L, a, "outlet closed for stocktake"), 200));

    JsonNode preview =
        mapper.readTree(read(dispatcher, "/api/plans/preview/swap?out=" + b + "&in=" + a, 200));
    assertTrue(preview.get("feasible").asBoolean(), preview.toString());
    List<String> stops = new ArrayList<>();
    preview.get("stops").forEach(s -> stops.add(s.get("orderId").asText()));
    assertTrue(stops.contains(a.toString()) && !stops.contains(b.toString()), "the deferred order takes the trip: " + stops);

    send(dispatcher, swap(withADeferred, b, a, "the stocktake moved"), 200);

    JsonNode after = draft();
    assertEquals("SERVED", allocation(after, a).get("decision").asText());
    assertEquals("SWAP", allocation(after, a).get("source").asText());
    assertEquals("DEFERRED", allocation(after, b).get("decision").asText());
    assertEquals("MANUAL_DEFER", allocation(after, b).get("source").asText());
    assertFalse(allocation(after, b).get("decidedBy").isNull());
  }

  @Test
  void aSwapOfTwoServedOrdersIsRefused() throws Exception {
    UUID a = demand("ambient");
    UUID b = demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());

    String refused = send(dispatcher, swap(planId, a, b, "both are on the trip"), 422);

    assertTrue(refused.contains("only a deferred order is swapped in"), refused);
    assertEquals("SERVED", allocation(draft(), a).get("decision").asText(), "nothing half done");
  }

  // ---- keep deferred -----------------------------------------------------------

  @Test
  void keepingAnOrderDeferredRecordsTheDecisionAndThePlanStillPublishes() throws Exception {
    UUID a = demand("ambient");
    demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    UUID withADeferred = nextPlan(send(dispatcher, defer(planId, 1L, a, "outlet closed for stocktake"), 200));

    UUID kept = nextPlan(send(dispatcher, keepDeferred(withADeferred, a, "stocktake runs all week"), 200));

    JsonNode allocation = allocation(draft(), a);
    assertEquals("KEPT", allocation.get("source").asText());
    assertTrue(allocation.get("reason").asText().contains("Kept deferred by the dispatcher: stocktake runs all week"));
    send(dispatcher, publish(kept, 1L), 200);
  }

  @Test
  void onlyADeferredOrderCanBeKeptDeferred() throws Exception {
    UUID a = demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());

    String refused = send(dispatcher, keepDeferred(planId, a, "it is on a trip"), 422);

    assertTrue(refused.contains("only a deferred order is kept deferred"), refused);
  }

  // ---- stop order --------------------------------------------------------------

  @Test
  void aDispatchersStopOrderIsKeptAndPreviewedBeforeItIsSent() throws Exception {
    UUID a = demand("ambient");
    UUID b = demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    JsonNode plan = draft();
    JsonNode trip = plan.get("trips").get(0);
    assertEquals(2, trip.get("stops").size(), "the fixture serves both on one trip");
    UUID tripId = UUID.fromString(trip.get("tripId").asText());
    List<UUID> engineOrder = new ArrayList<>();
    trip.get("stops").forEach(s -> engineOrder.add(UUID.fromString(s.get("orderId").asText())));
    List<UUID> reversed = new ArrayList<>(engineOrder);
    java.util.Collections.reverse(reversed);

    JsonNode preview =
        mapper.readTree(
            read(
                dispatcher,
                "/api/plans/preview/sequence?trip=" + tripId + "&orders=" + reversed.get(0) + "," + reversed.get(1),
                200));
    assertEquals(reversed.get(0).toString(), preview.get("stops").get(0).get("orderId").asText(), "timed in the order asked");

    send(dispatcher, reorder(planId, tripId, reversed, "the second outlet opens first"), 200);

    JsonNode after = draft().get("trips").get(0);
    assertEquals(reversed.get(0).toString(), after.get("stops").get(0).get("orderId").asText());
    assertEquals(reversed.get(1).toString(), after.get("stops").get(1).get("orderId").asText());
    assertEquals(a.toString().length(), b.toString().length());
  }

  @Test
  void aStopOrderThatLeavesAnOrderOutIsRefused() throws Exception {
    demand("ambient");
    demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    JsonNode trip = draft().get("trips").get(0);
    UUID tripId = UUID.fromString(trip.get("tripId").asText());
    UUID only = UUID.fromString(trip.get("stops").get(0).get("orderId").asText());

    String refused = send(dispatcher, reorder(planId, tripId, List.of(only), "forgot one"), 422);

    assertTrue(refused.contains("exactly once"), refused);
  }

  // ---- contact the store -------------------------------------------------------

  @Test
  void aDispatcherCanTellTheStoreAboutADeferredOrderAndItIsRecordedOnTheEvent() throws Exception {
    UUID a = demand("ambient");
    demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    UUID withADeferred = nextPlan(send(dispatcher, defer(planId, 1L, a, "outlet closed for stocktake"), 200));

    send(dispatcher, contactStore(withADeferred, a, "Please confirm you can take this on Wednesday"), 200);

    List<java.util.Map<String, Object>> events = outboxRows(a.toString(), "plan.store_contacted");
    assertEquals(1, events.size());
    String payload = String.valueOf(events.get(0).get("payload"));
    assertTrue(payload.contains("Please confirm you can take this on Wednesday"), payload);
    assertTrue(payload.contains(userId(dispatcherEmail).toString()), "rule 8: who asked");
  }

  @Test
  void aStoreIsNotToldAboutAnOrderThatIsOnATrip() throws Exception {
    UUID a = demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());

    String refused = send(dispatcher, contactStore(planId, a, "Nothing to say really"), 422);

    assertTrue(refused.contains("nothing to tell the store"), refused);
    assertEquals(0, outboxRows(a.toString(), "plan.store_contacted").size());
  }
}
