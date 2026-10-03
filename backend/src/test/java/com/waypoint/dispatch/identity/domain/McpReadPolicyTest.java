package com.waypoint.dispatch.identity.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class McpReadPolicyTest {
  @Test
  void onlyExactCuratedReadsAndOwnRevocationArePermitted() {
    assertTrue(McpReadPolicy.permits("GET", "/api/orders"));
    assertTrue(McpReadPolicy.permits("GET", "/api/loading/trips"));
    assertTrue(McpReadPolicy.permits("GET", "/api/execution/run-sheets"));
    assertTrue(McpReadPolicy.permits("GET", "/api/receipts/pending"));
    assertTrue(McpReadPolicy.permits("POST", "/api/mcp/session/end"));
    assertTrue(McpReadPolicy.permits("GET", "/api/loading/trips/00000000-0000-4000-8000-000000000001/manifest"));
    assertTrue(McpReadPolicy.permits("GET", "/api/receipts/00000000-0000-4000-8000-000000000001/custody"));
    for (String path : java.util.List.of("/api/commands", "/api/accounts", "/api/devices", "/api/session", "/api/execution/attachments/id/content", "/api/orders/../commands", "/api/orders%2f..%2fcommands", "/api/orders/")) {
      assertFalse(McpReadPolicy.permits("GET", path), path);
      assertFalse(McpReadPolicy.permits("POST", path), path);
    }
    assertFalse(McpReadPolicy.permits("POST", "/api/orders"));
    assertFalse(McpReadPolicy.permits("TRACE", "/api/orders"));
  }

  @Test
  void everyPermittedPathNamesItsToolAndEverythingElseIsOther() {
    String id = "00000000-0000-4000-8000-000000000001";
    java.util.Map<String, String> tools = new java.util.LinkedHashMap<>();
    tools.put("/api/mcp/context", "my_context");
    tools.put("/api/orders", "list_orders");
    tools.put("/api/orders/" + id, "get_order");
    tools.put("/api/plans/draft", "get_plan");
    tools.put("/api/plans/published", "get_plan");
    tools.put("/api/plans/draft/summary", "get_plan");
    tools.put("/api/plans/published/summary", "get_plan");
    tools.put("/api/plans/" + id + "/allocations", "list_plan_allocations");
    tools.put("/api/loading/trips/" + id + "/manifest", "get_manifest");
    tools.put("/api/loading/trips", "list_ready_trips");
    tools.put("/api/execution/deliveries/" + id, "get_delivery");
    tools.put("/api/execution/run-sheets", "list_run_sheets");
    tools.put("/api/receipts/" + id, "get_receipt");
    tools.put("/api/receipts/pending", "list_pending_receipts");
    tools.put("/api/receipts/" + id + "/custody", "get_custody");
    tools.put("/api/issues", "list_issues");
    tools.put("/api/issues/" + id, "get_issue");
    tools.put("/api/audit", "list_audit");
    tools.put("/api/audit/decisions/" + id, "get_command_decision");
    tools.put("/api/policies", "list_policies");
    tools.forEach((path, tool) -> org.junit.jupiter.api.Assertions.assertEquals(tool, McpReadPolicy.toolOf("GET", path), path));
    org.junit.jupiter.api.Assertions.assertEquals(17, new java.util.HashSet<>(tools.values()).size(), "the backend-facing catalogue tools");
    org.junit.jupiter.api.Assertions.assertEquals("disconnect", McpReadPolicy.toolOf("POST", "/api/mcp/session/end"));
    org.junit.jupiter.api.Assertions.assertEquals("other", McpReadPolicy.toolOf("GET", "/api/commands"));
    org.junit.jupiter.api.Assertions.assertEquals("other", McpReadPolicy.toolOf("GET", "/api/orders/not-an-id"), "a caller cannot invent a label");
    org.junit.jupiter.api.Assertions.assertEquals("other", McpReadPolicy.toolOf("GET", "/api/plans/not-an-id/allocations"));
    org.junit.jupiter.api.Assertions.assertEquals("other", McpReadPolicy.toolOf("POST", "/api/plans/" + id + "/allocations"), "a page is read only");
  }

  @Test
  void outcomesAreTheFewAnOperatorFiltersOn() {
    org.junit.jupiter.api.Assertions.assertEquals("ok", McpReadPolicy.outcomeOf(200));
    org.junit.jupiter.api.Assertions.assertEquals("unauthenticated", McpReadPolicy.outcomeOf(401));
    org.junit.jupiter.api.Assertions.assertEquals("denied", McpReadPolicy.outcomeOf(403));
    org.junit.jupiter.api.Assertions.assertEquals("not_found", McpReadPolicy.outcomeOf(404));
    org.junit.jupiter.api.Assertions.assertEquals("rate_limited", McpReadPolicy.outcomeOf(429));
    org.junit.jupiter.api.Assertions.assertEquals("rejected", McpReadPolicy.outcomeOf(409));
    org.junit.jupiter.api.Assertions.assertEquals("error", McpReadPolicy.outcomeOf(503));
  }
}
