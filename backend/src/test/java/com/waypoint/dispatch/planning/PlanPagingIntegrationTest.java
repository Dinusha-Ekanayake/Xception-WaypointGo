package com.waypoint.dispatch.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Issue #177: a plan of any size can be read in pieces. The summary carries the
 * header, the decision counts and the trips without stops; the allocations come
 * in keyset pages on the primary key. Both keep the scope of the full read.
 */
class PlanPagingIntegrationTest extends PlanningIntegrationSupport {

  @Test
  void theSummaryCountsTheDecisionsAndThePagesHoldEveryAllocationOnce() throws Exception {
    Set<UUID> orders = new HashSet<>();
    for (int i = 0; i < 5; i++) {
      orders.add(demand("ambient"));
    }
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());

    JsonNode summary =
        mapper.readTree(read(dispatcher, "/api/plans/draft/summary?depot=" + depot + "&date=" + serviceDate, 200));
    assertEquals(planId.toString(), summary.get("planId").asText());
    assertEquals("DRAFT", summary.get("status").asText());
    assertEquals(5, summary.get("served").asInt() + summary.get("deferred").asInt() + summary.get("unservable").asInt());
    assertFalse(summary.has("allocations"), "the summary never carries the allocations");
    int stops = 0;
    for (JsonNode trip : summary.get("trips")) {
      assertFalse(trip.has("stops"));
      stops += trip.get("stopCount").asInt();
    }
    assertEquals(summary.get("served").asInt(), stops, "every served order is one stop");

    List<String> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      String path = "/api/plans/" + planId + "/allocations?limit=2" + (cursor == null ? "" : "&after=" + cursor);
      JsonNode page = mapper.readTree(read(dispatcher, path, 200));
      assertEquals(1, page.get("planVersion").asInt());
      assertTrue(page.get("items").size() <= 2);
      for (JsonNode item : page.get("items")) {
        seen.add(item.get("orderId").asText());
        assertTrue(item.has("checks"), "each allocation keeps its constraint results");
      }
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
      pages++;
    } while (cursor != null && pages < 10);

    assertEquals(3, pages, "five allocations in pages of two");
    assertEquals(orders.size(), seen.size(), "no allocation twice: " + seen);
    assertEquals(orders, new HashSet<>(seen.stream().map(UUID::fromString).toList()));
    assertEquals(seen.stream().sorted().toList(), seen, "keyset order is the order id");
  }

  @Test
  void anotherDepotIsRefusedAndABadCursorOrLimitIsTheCallersMistake() throws Exception {
    demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());

    UUID actor = userId(elsewhereEmail);
    long before = denials(actor, "plan:Read");
    read(elsewhere, "/api/plans/draft/summary?depot=" + depot + "&date=" + serviceDate, 403);
    assertEquals(before + 1, denials(actor, "plan:Read"), "a summary outside scope is 403 plus audit");
    read(elsewhere, "/api/plans/" + planId + "/allocations", 404);

    read(dispatcher, "/api/plans/published/summary?depot=" + depot + "&date=" + serviceDate, 404);
    read(dispatcher, "/api/plans/" + planId + "/allocations?after=not-a-cursor", 422);
    read(dispatcher, "/api/plans/" + planId + "/allocations?limit=0", 422);
    read(dispatcher, "/api/plans/" + planId + "/allocations?limit=101", 422);
    read(dispatcher, "/api/plans/" + UUID.randomUUID() + "/allocations", 404);
  }
}
