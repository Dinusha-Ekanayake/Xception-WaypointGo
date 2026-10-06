package com.waypoint.dispatch.platform.web;

import java.util.List;

/**
 * Which reads the browser may keep and ask about again (roadmap F-07).
 *
 * <p>A screen polls the same read every few seconds, and most answers have not
 * changed. Such a read is sent {@code private, no-cache} with an ETag, so the
 * browser revalidates it every time and an unchanged answer comes back as a 304
 * with no body. Nothing is ever served from the cache without asking, so a
 * revoked permission is seen on the next read.
 *
 * <p>Everything else stays {@code no-store}: sign-in and sessions, credentials
 * such as the Fuel Pass, evidence and voice notes, the live streams, the
 * assistant (MCP) and OAuth endpoints, and the administrator's access and audit
 * reads. A path is kept out by default when in doubt.
 */
public final class CacheablePaths {
  private CacheablePaths() {}

  private static final List<String> NEVER_PREFIX =
      List.of(
          "/api/session",
          "/api/oauth",
          "/api/mcp",
          "/api/audit",
          "/api/accounts",
          "/api/policies",
          "/api/profile",
          "/api/devices",
          "/api/platform",
          "/api/admin");

  private static final List<String> NEVER_PART =
      List.of("/content", "/voice/", "/proof", "/fuel-pass", "/stream", "/attachments/");

  /** True for a GET of an API read that may carry an ETag and be revalidated. */
  public static boolean cacheable(String method, String path) {
    if (!"GET".equals(method) || path == null || !path.startsWith("/api/")) {
      return false;
    }
    for (String prefix : NEVER_PREFIX) {
      if (path.startsWith(prefix)) {
        return false;
      }
    }
    for (String part : NEVER_PART) {
      if (path.contains(part)) {
        return false;
      }
    }
    return true;
  }
}
