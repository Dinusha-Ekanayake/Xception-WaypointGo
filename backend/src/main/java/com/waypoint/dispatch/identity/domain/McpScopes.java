package com.waypoint.dispatch.identity.domain;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What an MCP connection was granted (R-IAM-34, issue #177). A scope only
 * narrows: effective access is client scope AND user policy AND row scope, so a
 * scope never lets anyone do what their own policy forbids.
 *
 * <p>{@code waypoint.read} is every read, as the single scope was before areas
 * existed, so a client that asks for it keeps working; the area scopes narrow
 * reading to one module. Write scopes are separate: a connection granted only
 * reads cannot be made to write by anything it sends. Asking for nothing grants
 * {@link #DEFAULT}.
 */
public final class McpScopes {
  public static final String READ_ALL = "waypoint.read";
  public static final String ISSUES_WRITE = "issues.write";
  public static final Set<String> DEFAULT = Set.of(READ_ALL, ISSUES_WRITE);

  /** Read action to the area scope that covers it. */
  private static final Map<String, String> READ_AREAS = Map.of(
      "order:Read", "orders.read",
      "plan:Read", "plans.read",
      "loading:Read", "loading.read",
      "delivery:Read", "deliveries.read",
      "receipt:Read", "receipts.read",
      "issue:Read", "issues.read",
      "audit:Read", "audit.read",
      "iam:ReadPolicy", "policies.read");

  /**
   * The write tools there are, the command each one submits and the scope it
   * needs. Only writes that add or reassign, never ones that cancel, publish or
   * replace someone's work (decision 2026-10-03: {@code plan:Generate} cancels
   * the open draft, so it is not here).
   */
  public record WriteTool(String name, String kind, String scope, boolean versioned) {}

  private static final Map<String, WriteTool> WRITES = Map.of(
      "raise_issue", new WriteTool("raise_issue", "issue:Raise", ISSUES_WRITE, false),
      "assign_issue", new WriteTool("assign_issue", "issue:Assign", ISSUES_WRITE, true));

  private McpScopes() {}

  /** Every scope a client may ask for, for discovery metadata and the consent page. */
  public static List<String> supported() {
    var all = new LinkedHashSet<String>();
    all.add(READ_ALL);
    READ_AREAS.values().stream().sorted().forEach(all::add);
    all.add(ISSUES_WRITE);
    return List.copyOf(all);
  }

  /**
   * The scopes granted for a requested scope string. Blank is the default
   * (decision 2026-10-03): every read and the safe writes, each write still
   * confirmed by the person and authorized by their own policy. Unknown words
   * are ignored, because generic assistants send their own (such as
   * {@code openid}); a request naming no known scope is refused.
   */
  public static Optional<Set<String>> granted(String requested) {
    if (requested == null || requested.isBlank()) {
      return Optional.of(DEFAULT);
    }
    Set<String> known = Set.copyOf(supported());
    var granted = new LinkedHashSet<String>();
    for (String word : requested.trim().split("\\s+")) {
      if (known.contains(word)) {
        granted.add(word);
      }
    }
    return granted.isEmpty() ? Optional.empty() : Optional.of(Set.copyOf(granted));
  }

  /** A stored grant; null is a connection made before scopes, which had every read. */
  public static Set<String> stored(Collection<String> scopes) {
    return scopes == null ? Set.of(READ_ALL) : Set.copyOf(scopes);
  }

  /** Scopes as the OAuth {@code scope} field writes them: space separated, sorted. */
  public static String format(Set<String> scopes) {
    return String.join(" ", new java.util.TreeSet<>(scopes));
  }

  /** Whether a grant covers a read action. An action with no area is covered only by the full read scope. */
  public static boolean coversRead(Set<String> granted, String readAction) {
    if (granted.contains(READ_ALL)) {
      return true;
    }
    String area = READ_AREAS.get(readAction);
    return area != null && granted.contains(area);
  }

  public static Optional<WriteTool> writeTool(String name) {
    return Optional.ofNullable(name == null ? null : WRITES.get(name));
  }

  public static List<WriteTool> writeTools() {
    return WRITES.values().stream().sorted(java.util.Comparator.comparing(WriteTool::name)).toList();
  }
}
