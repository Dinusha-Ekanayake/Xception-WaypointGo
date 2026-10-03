package com.waypoint.dispatch.identity.domain;

import java.util.Set;
import java.util.regex.Pattern;

/** Fixed credential boundary, independent of a user's business permissions. */
public final class McpReadPolicy {
  private static final Set<String> LISTS = Set.of(
      "/api/mcp/context", "/api/orders", "/api/plans/draft", "/api/plans/published",
      "/api/plans/draft/summary", "/api/plans/published/summary",
      "/api/issues", "/api/audit", "/api/policies",
      "/api/loading/trips", "/api/execution/run-sheets", "/api/receipts/pending");
  private static final String ID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
  private static final Pattern DETAIL = Pattern.compile(
      "(?:/api/(?:orders|issues|receipts)/" + ID
          + "|/api/receipts/" + ID + "/custody"
          + "|/api/execution/deliveries/" + ID
          + "|/api/loading/trips/" + ID + "/manifest"
          + "|/api/plans/" + ID + "/allocations"
          + "|/api/audit/decisions/" + ID + ")");

  /** The only two writes, both into Identity, never {@code /api/commands} (R-IAM-35). */
  public static final String PREPARE_WRITE = "/api/mcp/writes";
  public static final String CONFIRM_WRITE = "/api/mcp/writes/confirm";

  private McpReadPolicy() {}

  public static boolean permits(String method, String path) {
    return ("POST".equals(method) && ("/api/mcp/session/end".equals(path) || isWrite(method, path)))
        || ("GET".equals(method) && (LISTS.contains(path) || DETAIL.matcher(path).matches()));
  }

  /** A request that prepares or confirms an MCP write, which counts against the write limit too. */
  public static boolean isWrite(String method, String path) {
    return "POST".equals(method) && (PREPARE_WRITE.equals(path) || CONFIRM_WRITE.equals(path));
  }

  /**
   * The MCP tool a request serves, named from its path rather than from anything
   * the client sends, so a metric label can never be invented by a caller. A path
   * outside the boundary is {@code other}.
   */
  public static String toolOf(String method, String path) {
    if (!permits(method, path)) {
      return "other";
    }
    if (isWrite(method, path)) {
      return PREPARE_WRITE.equals(path) ? "prepare_write" : "confirm_write";
    }
    if ("POST".equals(method)) {
      return "disconnect";
    }
    return switch (path) {
      case "/api/mcp/context" -> "my_context";
      case "/api/orders" -> "list_orders";
      case "/api/plans/draft", "/api/plans/published",
          "/api/plans/draft/summary", "/api/plans/published/summary" -> "get_plan";
      case "/api/issues" -> "list_issues";
      case "/api/audit" -> "list_audit";
      case "/api/policies" -> "list_policies";
      case "/api/loading/trips" -> "list_ready_trips";
      case "/api/execution/run-sheets" -> "list_run_sheets";
      case "/api/receipts/pending" -> "list_pending_receipts";
      default -> detailTool(path);
    };
  }

  private static String detailTool(String path) {
    if (path.endsWith("/custody")) {
      return "get_custody";
    }
    if (path.endsWith("/allocations")) {
      return "list_plan_allocations";
    }
    if (path.endsWith("/manifest")) {
      return "get_manifest";
    }
    if (path.startsWith("/api/orders/")) {
      return "get_order";
    }
    if (path.startsWith("/api/issues/")) {
      return "get_issue";
    }
    if (path.startsWith("/api/receipts/")) {
      return "get_receipt";
    }
    if (path.startsWith("/api/execution/deliveries/")) {
      return "get_delivery";
    }
    return "get_command_decision";
  }

  /** How a request ended, in the few words an operator filters on. */
  public static String outcomeOf(int status) {
    if (status >= 200 && status < 300) {
      return "ok";
    }
    return switch (status) {
      case 401 -> "unauthenticated";
      case 403 -> "denied";
      case 404 -> "not_found";
      case 429 -> "rate_limited";
      default -> status >= 500 ? "error" : "rejected";
    };
  }
}
