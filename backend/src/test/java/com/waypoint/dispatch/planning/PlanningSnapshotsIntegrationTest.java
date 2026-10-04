package com.waypoint.dispatch.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Regenerating without losing the dispatcher's decisions, saved plans, going
 * back to one and comparing two, end to end through the API.
 */
class PlanningSnapshotsIntegrationTest extends PlanningIntegrationSupport {

  String generatePayload(boolean keepDecisions) {
    return "{\"depotCode\":\"" + depot + "\",\"serviceDate\":\"" + serviceDate + "\",\"keepDecisions\":" + keepDecisions + "}";
  }

  UUID regenerate(boolean keepDecisions) throws Exception {
    String body = send(dispatcher, envelope("plan:Generate", null, generatePayload(keepDecisions)), 200);
    return UUID.fromString(finishJob(dispatcher, mapper.readTree(body).get("result")).get("planId").asText());
  }

  JsonNode draft() throws Exception {
    return mapper.readTree(read(dispatcher, "/api/plans/draft?depot=" + depot + "&date=" + serviceDate, 200));
  }

  JsonNode snapshots(Cookie who, int expected) throws Exception {
    return mapper.readTree(read(who, "/api/plans/snapshots?depot=" + depot + "&date=" + serviceDate, expected));
  }

  static JsonNode allocation(JsonNode plan, UUID orderId) {
    for (JsonNode a : plan.get("allocations")) {
      if (a.get("orderId").asText().equals(orderId.toString())) {
        return a;
      }
    }
    throw new AssertionError("order " + orderId + " is not in the plan");
  }

  static String vehicleOf(JsonNode plan, UUID orderId) {
    String tripId = allocation(plan, orderId).get("tripId").asText();
    for (JsonNode trip : plan.get("trips")) {
      if (trip.get("tripId").asText().equals(tripId)) {
        return trip.get("vehicleId").asText();
      }
    }
    throw new AssertionError("order " + orderId + " is on no trip");
  }

  // ---- regenerate keeps decisions ----------------------------------------------

  @Test
  void aRegenerateThatKeepsDecisionsPutsBackWhatTheDispatcherLockedAndDeferred() throws Exception {
    UUID a = demand("ambient");
    UUID b = demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    UUID afterDefer =
        UUID.fromString(
            mapper.readTree(send(dispatcher, defer(planId, 1L, b, "outlet closed for stocktake"), 200))
                .get("result").get("planId").asText());
    send(
        dispatcher,
        envelope("plan:Lock", 1L, "{\"planId\":\"" + afterDefer + "\",\"orderId\":\"" + a + "\"}"),
        200);
    String vehicleBefore = vehicleOf(draft(), a);

    regenerate(true);

    JsonNode after = draft();
    assertEquals("DEFERRED", allocation(after, b).get("decision").asText(), "kept deferred, though there is room");
    assertEquals("MANUAL_DEFER", allocation(after, b).get("source").asText());
    assertFalse(allocation(after, b).get("decidedBy").isNull(), "still says who");
    assertTrue(allocation(after, a).get("locked").asBoolean());
    assertEquals(vehicleBefore, vehicleOf(after, a), "the locked order stayed where it was");
  }

  @Test
  void aRegenerateFromScratchLetsTheEngineDecideAgain() throws Exception {
    UUID a = demand("ambient");
    UUID b = demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    send(dispatcher, defer(planId, 1L, b, "outlet closed for stocktake"), 200);

    regenerate(false);

    JsonNode after = draft();
    assertEquals("SERVED", allocation(after, b).get("decision").asText(), "the engine placed it again");
    assertEquals("ENGINE", allocation(after, b).get("source").asText());
    assertFalse(allocation(after, a).get("locked").asBoolean());
  }

  // ---- saved plans -------------------------------------------------------------

  @Test
  void aGenerateSavesTheEnginesPlanAndARegenerateSavesTheDraftItReplaced() throws Exception {
    demand("ambient");
    generate(dispatcher, 200);
    JsonNode first = snapshots(dispatcher, 200);
    assertEquals(1, first.size());
    assertEquals("AUTO", first.get(0).get("kind").asText());
    assertEquals("Auto plan", first.get(0).get("label").asText());

    regenerate(false);

    JsonNode second = snapshots(dispatcher, 200);
    assertEquals(3, second.size(), "the replaced draft, then the new auto plan");
    assertEquals("AUTO", second.get(0).get("kind").asText(), "newest first");
    assertEquals("REGENERATED", second.get(1).get("kind").asText());
    assertEquals(3, second.get(0).get("number").asInt());
  }

  @Test
  void aSavedPlanIsReadOnlyAndAnotherDepotCannotSeeIt() throws Exception {
    UUID a = demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    String saved =
        send(
            dispatcher,
            envelope("plan:SaveSnapshot", 1L, "{\"planId\":\"" + planId + "\",\"label\":\"Before the stocktake\"}"),
            200);
    UUID snapshotId = UUID.fromString(mapper.readTree(saved).get("result").get("snapshotId").asText());

    JsonNode detail = mapper.readTree(read(dispatcher, "/api/plans/snapshots/" + snapshotId, 200));
    assertEquals("Before the stocktake", detail.get("snapshot").get("label").asText());
    assertEquals("MANUAL", detail.get("snapshot").get("kind").asText());
    assertEquals("SERVED", allocation(detail.get("plan"), a).get("decision").asText());

    read(elsewhere, "/api/plans/snapshots/" + snapshotId, 404);
    read(elsewhere, "/api/plans/snapshots?depot=" + depot + "&date=" + serviceDate, 403);
  }

  @Test
  void savingAPlanThatMovedOnIsRefusedLikeAnyStaleEdit() throws Exception {
    demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    regenerate(false);

    String stale =
        send(dispatcher, envelope("plan:SaveSnapshot", 1L, "{\"planId\":\"" + planId + "\"}"), 409);

    assertTrue(stale.contains("VERSION_CONFLICT"), stale);
  }

  // ---- restore and compare -----------------------------------------------------

  @Test
  void returningToASavedPlanBringsBackWhatItPlacedAndComparingShowsTheDifference() throws Exception {
    UUID a = demand("ambient");
    UUID b = demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    UUID snapshotId =
        UUID.fromString(
            mapper.readTree(
                    send(dispatcher, envelope("plan:SaveSnapshot", 1L, "{\"planId\":\"" + planId + "\"}"), 200))
                .get("result").get("snapshotId").asText());
    UUID deferredPlan =
        UUID.fromString(
            mapper.readTree(send(dispatcher, defer(planId, 1L, a, "outlet closed for stocktake"), 200))
                .get("result").get("planId").asText());
    assertEquals("DEFERRED", allocation(draft(), a).get("decision").asText());

    JsonNode comparison =
        mapper.readTree(read(dispatcher, "/api/plans/compare?a=" + snapshotId + "&b=" + deferredPlan, 200));
    List<String> dropped = new ArrayList<>();
    comparison.get("changes").forEach(c -> {
      if (c.get("kind").asText().equals("DROPPED")) {
        dropped.add(c.get("orderId").asText());
      }
    });
    assertEquals(List.of(a.toString()), dropped, "the order that was taken off is the one difference");
    assertEquals(comparison.get("a").get("served").asInt() - 1, comparison.get("b").get("served").asInt());

    send(
        dispatcher,
        envelope("plan:RestoreSnapshot", 1L, "{\"planId\":\"" + deferredPlan + "\",\"snapshotId\":\"" + snapshotId + "\"}"),
        200);

    JsonNode restored = draft();
    assertEquals("SERVED", allocation(restored, a).get("decision").asText(), "back on its trip");
    assertEquals("RESTORED", allocation(restored, a).get("source").asText());
    assertEquals("SERVED", allocation(restored, b).get("decision").asText());
  }

  @Test
  void plansOfAnotherDayCannotBeCompared() throws Exception {
    demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());

    String refused = read(dispatcher, "/api/plans/compare?a=" + planId + "&b=" + UUID.randomUUID(), 404);

    assertTrue(refused.contains("No plan or saved plan"), refused);
  }
}
