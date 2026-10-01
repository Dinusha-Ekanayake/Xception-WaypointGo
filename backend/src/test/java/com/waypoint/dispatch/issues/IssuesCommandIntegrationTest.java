package com.waypoint.dispatch.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.issues.contract.IssueEvents.RedeliveryRequested;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.support.ReceiptIssuesSupport;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Issues' commands end to end through {@code POST /api/commands}: the bus
 * authorizes against the role policies (raise rights are policy data), the
 * handler checks scope and decides, and the issue, its history and its events
 * commit together.
 */
class IssuesCommandIntegrationTest extends ReceiptIssuesSupport {

  @Test
  void anIssueIsRaisedAssignedResolvedAndClosedWithEveryDecisionRecorded() throws Exception {
    UUID issueId = raise(dispatcher, "DAMAGED_GOODS", Optional.of(outlet.outletId()), "order", UUID.randomUUID(), 200);
    assertEquals(1, outbox(issueId.toString(), "issue.raised").size());

    send(dispatcher, envelope("issue:Assign", 1L, "{\"issueId\":\"" + issueId + "\",\"assigneeUserId\":\"" + dispatcher.id() + "\"}"), 200);
    JsonNode resolved =
        send(dispatcher,
            envelope("issue:Resolve", 2L,
                "{\"issueId\":\"" + issueId + "\",\"action\":\"write_off\",\"note\":\"supplier credit agreed\"}"),
            200).get("result");
    assertEquals("RESOLVED", resolved.get("status").asText());
    assertEquals(1, outbox(issueId.toString(), "issue.resolved").size());
    send(dispatcher, envelope("issue:Close", 3L, "{\"issueId\":\"" + issueId + "\"}"), 200);

    JsonNode view = read(dispatcher, "/api/issues/" + issueId, 200);
    assertEquals("CLOSED", view.get("status").asText());
    assertEquals("WRITE_OFF", view.get("resolutionAction").asText());
    JsonNode history = read(dispatcher, "/api/issues/" + issueId + "/history", 200);
    assertEquals(4, history.size(), "raised, assigned, resolved, closed: rule 8");
    assertEquals(dispatcher.id().toString(), history.get(2).get("actorId").asText());
  }

  @Test
  void aDriverCannotResolveAnIssue() throws Exception {
    UUID issueId = raise(dispatcher, "OTHER", Optional.empty(), "order", UUID.randomUUID(), 200);
    long before = denials(driver.id(), "issue:Resolve");

    send(driver, envelope("issue:Resolve", 1L,
        "{\"issueId\":\"" + issueId + "\",\"action\":\"other\",\"note\":\"drove past it\"}"), 403);

    assertEquals(before + 1, denials(driver.id(), "issue:Resolve"), "403 plus audit");
  }

  @Test
  void raiseRightsArePolicyDataByType() throws Exception {
    assignToday(driver);
    raise(manager, "VEHICLE_FAULT", Optional.of(outlet.outletId()), "vehicle", "VEH001", 403);
    raise(manager, "DAMAGED_GOODS", Optional.of(outlet.outletId()), "order", UUID.randomUUID(), 200);
    raise(loader, "LOADING_SHORTFALL", Optional.empty(), "order", UUID.randomUUID(), 200);
    raise(loader, "FAILED_DELIVERY", Optional.empty(), "order", UUID.randomUUID(), 403);
    raise(driver, "VEHICLE_FAULT", Optional.empty(), "vehicle", "VEH001", 200);
  }

  @Test
  void aDriverRaisesOnlyForTheDepotOfTheVehicleTheyDriveToday() throws Exception {
    long before = denials(roamingDriver.id(), "issue:Raise");
    raise(roamingDriver, "VEHICLE_FAULT", Optional.empty(), "vehicle", "VEH001", 403);
    assertEquals(before + 1, denials(roamingDriver.id(), "issue:Raise"), "no vehicle today, no scope (R-IAM-13)");

    String vehicle = assignToday(roamingDriver);
    raise(roamingDriver, "VEHICLE_FAULT", Optional.empty(), "vehicle", vehicle, 200);
    send(roamingDriver, envelope("issue:Raise", null,
        "{\"type\":\"VEHICLE_FAULT\",\"severity\":\"HIGH\",\"depotCode\":\"" + otherDepot + "\","
            + "\"subjects\":[{\"type\":\"vehicle\",\"id\":\"" + vehicle + "\"}],\"description\":\"elsewhere\"}"), 403);
  }

  @Test
  void anIssueIsAssignedOnlyToSomeoneWhoWorksItsDepot() throws Exception {
    UUID issueId = raise(dispatcher, "OTHER", Optional.empty(), "order", UUID.randomUUID(), 200);

    JsonNode refused = send(dispatcher, envelope("issue:Assign", 1L,
        "{\"issueId\":\"" + issueId + "\",\"assigneeUserId\":\"" + stranger.id() + "\"}"), 422);
    assertTrue(refused.toString().contains("R-ISS-08"), refused.toString());

    send(dispatcher, envelope("issue:Assign", 1L,
        "{\"issueId\":\"" + issueId + "\",\"assigneeUserId\":\"" + loader.id() + "\"}"), 200);
  }

  @Test
  void anIssueIsRaisedOnlyWhereTheActorWorks() throws Exception {
    long before = denials(manager.id(), "issue:Raise");
    send(manager, raiseBody("DAMAGED_GOODS", otherDepot, Optional.of(otherOutlet.outletId()), "order",
        UUID.randomUUID().toString()), 403);
    assertEquals(before + 1, denials(manager.id(), "issue:Raise"));

    JsonNode wrongDepot =
        send(dispatcher, raiseBody("DAMAGED_GOODS", depot, Optional.of(otherOutlet.outletId()), "order",
            UUID.randomUUID().toString()), 422);
    assertTrue(wrongDepot.toString().contains("belongs to depot"), wrongDepot.toString());
  }

  @Test
  void aRedeliveryIsRequestedExactlyOnceAndOrderingCreatesOneOrder() throws Exception {
    Order failed = deliveredOrder(outlet);
    assignToday(driver);
    UUID issueId = raise(driver, "FAILED_DELIVERY", Optional.of(outlet.outletId()), "order", failed.orderId(), 200);
    LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(1);
    String body =
        "{\"issueId\":\"" + issueId + "\",\"orderId\":\"" + failed.orderId() + "\",\"requestedDate\":\"" + tomorrow
            + "\",\"note\":\"outlet closed, retry tomorrow\"}";

    send(dispatcher, envelope("issue:ScheduleRedelivery", 1L, body), 200);
    JsonNode second = send(dispatcher, envelope("issue:ScheduleRedelivery", 2L, body), 409);
    assertTrue(second.toString().contains("R-ISS"), second.toString());

    assertEquals(1, outbox(issueId.toString(), "redelivery.requested").size());
    RedeliveryRequested requested =
        new RedeliveryRequested(issueId, failed.orderId(), tomorrow, "outlet closed, retry tomorrow");
    deliver("ordering.on-redelivery-requested", requested);
    deliver("ordering.on-redelivery-requested", requested);
    assertEquals(
        1L,
        ((Number) database.asSystem(ModuleRole.ORDERING, () -> database.queryOne(
            "SELECT count(*) AS n FROM ordering.orders WHERE source_issue_id = ?", issueId).get("n"))).longValue(),
        "exactly one new order linked to the original");
    assertEquals(
        1L,
        ((Number) database.asSystem(ModuleRole.ORDERING, () -> database.queryOne(
            "SELECT deferral_count AS n FROM ordering.orders WHERE source_issue_id = ?", issueId).get("n"))).longValue(),
        "the redelivery carries a skip, so the next plan serves the outlet first (R-PLN-20)");
  }

  @Test
  void aReplacementResolvesTheShortfallAndTellsLoading() throws Exception {
    UUID trip = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    JsonNode raised =
        send(loader,
            envelope("issue:Raise", null,
                "{\"type\":\"LOADING_SHORTFALL\",\"severity\":\"HIGH\",\"depotCode\":\"" + depot + "\","
                    + "\"subjects\":[{\"type\":\"trip\",\"id\":\"" + trip + "\"},{\"type\":\"order\",\"id\":\"" + order
                    + "\"}],\"description\":\"two cartons short at the dock\"}"),
            200);
    UUID issueId = UUID.fromString(raised.get("result").get("issueId").asText());

    send(dispatcher,
        envelope("issue:RecordReplacement", 1L,
            "{\"issueId\":\"" + issueId + "\",\"tripId\":\"" + trip + "\",\"orderId\":\"" + order
                + "\",\"note\":\"cartons from the reserve\"}"),
        200);

    assertEquals(1, outbox(issueId.toString(), "shortfall.resolved").size());
    assertTrue(
        mapper.readTree(String.valueOf(outbox(issueId.toString(), "shortfall.resolved").get(0).get("payload")))
            .get("shortfallId").isNull(),
        "raised by hand, so no single shortfall is named");
    assertEquals("REPLACEMENT", read(dispatcher, "/api/issues/" + issueId, 200).get("resolutionAction").asText());
  }

  @Test
  void aStaleVersionIsRefusedAndAnIssueRaisedInErrorIsCancelledWithAReason() throws Exception {
    UUID issueId = raise(dispatcher, "OTHER", Optional.empty(), "order", UUID.randomUUID(), 200);

    JsonNode stale = send(dispatcher, envelope("issue:Cancel", 5L,
        "{\"issueId\":\"" + issueId + "\",\"reason\":\"duplicate report\"}"), 409);
    assertEquals("VERSION_CONFLICT", stale.get("code").asText());

    send(dispatcher, envelope("issue:Cancel", 1L, "{\"issueId\":\"" + issueId + "\",\"reason\":\"duplicate report\"}"), 200);
    assertEquals("CANCELLED", read(dispatcher, "/api/issues/" + issueId, 200).get("status").asText());
  }

  // ---- fixtures ------------------------------------------------------------

  private UUID raise(Person who, String type, Optional<String> outletId, String subjectType, Object subjectId,
      int expected) throws Exception {
    JsonNode response = send(who, raiseBody(type, depot, outletId, subjectType, String.valueOf(subjectId)), expected);
    return expected == 200 ? UUID.fromString(response.get("result").get("issueId").asText()) : null;
  }

  private static String raiseBody(String type, String depotCode, Optional<String> outletId, String subjectType,
      String subjectId) {
    return envelope(
        "issue:Raise", null,
        "{\"type\":\"" + type + "\",\"severity\":\"MEDIUM\",\"depotCode\":\"" + depotCode + "\","
            + outletId.map(o -> "\"outletId\":\"" + o + "\",").orElse("")
            + "\"subjects\":[{\"type\":\"" + subjectType + "\",\"id\":\"" + subjectId + "\"}],"
            + "\"description\":\"reported in a test\"}");
  }
}
