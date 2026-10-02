package com.waypoint.dispatch.identity.domain;

import java.util.Set;
import java.util.regex.Pattern;

/** Fixed credential boundary, independent of a user's business permissions. */
public final class McpReadPolicy {
  private static final Set<String> LISTS = Set.of(
      "/api/mcp/context", "/api/orders", "/api/plans/draft", "/api/plans/published",
      "/api/issues", "/api/audit", "/api/policies");
  private static final String ID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
  private static final Pattern DETAIL = Pattern.compile(
      "(?:/api/(?:orders|issues|receipts)/" + ID
          + "|/api/execution/deliveries/" + ID
          + "|/api/loading/trips/" + ID + "/manifest"
          + "|/api/audit/decisions/" + ID + ")");

  private McpReadPolicy() {}

  public static boolean permits(String method, String path) {
    return ("POST".equals(method) && "/api/mcp/session/end".equals(path))
        || ("GET".equals(method) && (LISTS.contains(path) || DETAIL.matcher(path).matches()));
  }
}
