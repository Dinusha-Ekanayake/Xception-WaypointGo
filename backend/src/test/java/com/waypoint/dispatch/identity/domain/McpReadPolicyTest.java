package com.waypoint.dispatch.identity.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class McpReadPolicyTest {
  @Test
  void onlyExactCuratedReadsAndOwnRevocationArePermitted() {
    assertTrue(McpReadPolicy.permits("GET", "/api/orders"));
    assertTrue(McpReadPolicy.permits("POST", "/api/mcp/session/end"));
    assertTrue(McpReadPolicy.permits("GET", "/api/loading/trips/00000000-0000-4000-8000-000000000001/manifest"));
    for (String path : java.util.List.of("/api/commands", "/api/accounts", "/api/devices", "/api/session", "/api/execution/attachments/id/content", "/api/orders/../commands", "/api/orders%2f..%2fcommands", "/api/orders/")) {
      assertFalse(McpReadPolicy.permits("GET", path), path);
      assertFalse(McpReadPolicy.permits("POST", path), path);
    }
    assertFalse(McpReadPolicy.permits("POST", "/api/orders"));
    assertFalse(McpReadPolicy.permits("TRACE", "/api/orders"));
  }
}
