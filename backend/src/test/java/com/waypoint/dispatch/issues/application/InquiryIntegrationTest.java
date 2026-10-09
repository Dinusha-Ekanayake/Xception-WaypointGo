package com.waypoint.dispatch.issues.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.loading.contract.LoadingEvents.LoadingShortfall;
import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.support.ReceiptIssuesSupport;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FF-03 end to end: Operations records an inquiry into a depot's issue, with a
 * write-off, and someone else approves it within P-DG-1; the role sees issues
 * and nothing else of the depot, and another depot's ops person sees nothing.
 */
class InquiryIntegrationTest extends ReceiptIssuesSupport {
  Person ops;
  Person otherOps;
  Person head;
  Person farOps;

  @BeforeEach
  void people() {
    String run = UUID.randomUUID().toString().substring(0, 8);
    ops = person("ops-" + run, "ops_inquiry", Optional.of(depot), Optional.empty());
    otherOps = person("op2-" + run, "ops_inquiry", Optional.of(depot), Optional.empty());
    head = person("hod-" + run, "ops_head", Optional.of(depot), Optional.empty());
    farOps = person("opf-" + run, "ops_inquiry", Optional.of(otherDepot), Optional.empty());
  }

  @Test
  void aWriteOffAboveTheLimitIsRecordedByOneAndApprovedOnlyByTheHead() throws Exception {
    UUID issueId = shortfallIssue();
    JsonNode recorded = send(ops, inquiry(issueId, "Pallet crushed by the forklift", "30000"), 200).get("result");
    assertTrue(recorded.get("needsHead").asBoolean());
    UUID writeOffId = UUID.fromString(recorded.get("writeOffId").asText());

    String self = send(ops, decide(writeOffId, 1L, true, "approving my own"), 409).toString();
    assertTrue(self.contains("P-DG-1"), self);
    String manager = send(otherOps, decide(writeOffId, 1L, true, "looks right"), 409).toString();
    assertTrue(manager.contains("Head of Distribution"), manager);
    send(head, decide(writeOffId, 9L, true, "stale screen"), 409);
    send(head, decide(writeOffId, 1L, true, "photos confirm the damage"), 200);

    JsonNode writeOffs = read(ops, "/api/issues/" + issueId + "/write-offs", 200);
    assertEquals("approved", writeOffs.get(0).get("status").asText());
    JsonNode history = read(ops, "/api/issues/" + issueId + "/history", 200);
    assertTrue(actions(history).contains("inquiry"));
    assertTrue(actions(history).contains("write_off_approved"));
  }

  @Test
  void aWriteOffWithinTheLimitIsApprovedByAnotherManager() throws Exception {
    UUID issueId = shortfallIssue();
    JsonNode recorded = send(ops, inquiry(issueId, "Two cartons wet", "4500"), 200).get("result");
    UUID writeOffId = UUID.fromString(recorded.get("writeOffId").asText());
    send(otherOps, decide(writeOffId, 1L, true, "agreed"), 200);
  }

  @Test
  void theRoleSeesItsDepotsIssuesAndNothingElse() throws Exception {
    UUID issueId = shortfallIssue();
    read(ops, "/api/issues/" + issueId, 200);
    // Another depot's ops person, and the dispatcher (inquiries are Operations', P-DG-4), are refused.
    send(farOps, inquiry(issueId, "Not my depot", null), 403);
    send(dispatcher, inquiry(issueId, "Dispatcher tries", null), 403);
    // Policy decides actions: the role cannot resolve the issue or read the depot's plans.
    send(ops, envelope("issue:Resolve", 1L, "{\"issueId\":\"" + issueId + "\",\"action\":\"NO_ACTION\",\"note\":\"try\"}"), 403);
    read(ops, "/api/plans/published?depot=" + depot + "&date=2026-10-08", 403);
  }

  // ---- helpers ----

  private UUID shortfallIssue() {
    Order order = deliveredOrder(outlet);
    UUID shortfall = UUID.randomUUID();
    deliver(
        "issues.on-loading-shortfall",
        new LoadingShortfall(shortfall, UUID.randomUUID(), order.orderId(), depot, CheckStatus.SHORT, 2, "crushed"));
    return (UUID) database.asSystem(
            ModuleRole.ISSUES,
            () -> database.queryOne("SELECT issue_id FROM issues.issues WHERE source_key = ?", "loading.shortfall:" + shortfall))
        .get("issue_id");
  }

  private static String inquiry(UUID issueId, String outcome, String value) {
    return envelope(
        "issue:RecordInquiry", null,
        "{\"issueId\":\"" + issueId + "\",\"outcome\":\"" + outcome + "\""
            + (value == null ? "" : ",\"writeOffLkr\":\"" + value + "\"") + "}");
  }

  private static String decide(UUID writeOffId, Long version, boolean approve, String note) {
    return envelope(
        "issue:ApproveWriteOff", version,
        "{\"writeOffId\":\"" + writeOffId + "\",\"approve\":" + approve + ",\"note\":\"" + note + "\"}");
  }

  private static java.util.List<String> actions(JsonNode history) {
    return StreamSupport.stream(history.spliterator(), false).map(h -> h.get("action").asText()).toList();
  }
}
