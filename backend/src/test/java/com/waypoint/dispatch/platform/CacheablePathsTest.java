package com.waypoint.dispatch.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.platform.web.CacheablePaths;
import org.junit.jupiter.api.Test;

/** F-07: which reads may be revalidated, and which are never kept. */
class CacheablePathsTest {

  @Test
  void polledReadsMayBeRevalidated() {
    for (String path :
        new String[] {
          "/api/execution/run-sheets", "/api/execution/vehicles", "/api/reference/outlets/OUT001",
          "/api/plans/published", "/api/loading/trips", "/api/notifications", "/api/orders"
        }) {
      assertTrue(CacheablePaths.cacheable("GET", path), path);
    }
  }

  @Test
  void writesSessionsCredentialsEvidenceStreamsAndAdministrationAreNeverKept() {
    assertFalse(CacheablePaths.cacheable("POST", "/api/commands"));
    for (String path :
        new String[] {
          "/api/session", "/api/oauth/authorize", "/api/mcp/tools", "/api/audit", "/api/accounts",
          "/api/policies", "/api/execution/vehicles/VEH001/fuel-pass", "/api/execution/fuel-passes",
          "/api/execution/attachments/a/content", "/api/threads/t/voice/v", "/api/execution/deliveries/d/proof",
          "/api/notifications/stream", "/api/execution/positions/stream", "/api/admin/orders", "/health/liveness"
        }) {
      assertFalse(CacheablePaths.cacheable("GET", path), path);
    }
  }
}
