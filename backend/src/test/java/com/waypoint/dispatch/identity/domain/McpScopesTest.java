package com.waypoint.dispatch.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** R-IAM-34 and R-IAM-35 as pure rules: what a scope string grants and what a grant covers. */
class McpScopesTest {

  @Test
  void askingForNothingGrantsEveryReadAndTheSafeWrites() {
    assertEquals(Set.of("waypoint.read", "issues.write"), McpScopes.granted(null).orElseThrow());
    assertEquals(Set.of("waypoint.read", "issues.write"), McpScopes.granted("  ").orElseThrow());
  }

  @Test
  void unknownWordsAreIgnoredButAScopeOfNothingKnownIsRefused() {
    assertEquals(Set.of("orders.read"), McpScopes.granted("openid orders.read profile").orElseThrow());
    assertTrue(McpScopes.granted("openid profile").isEmpty());
    assertTrue(McpScopes.granted("waypoint.write").isEmpty(), "there is no blanket write scope");
  }

  @Test
  void anAreaCoversOnlyItsOwnReadAndTheFullReadCoversEvery() {
    assertTrue(McpScopes.coversRead(Set.of("orders.read"), "order:Read"));
    assertFalse(McpScopes.coversRead(Set.of("orders.read"), "receipt:Read"));
    assertFalse(McpScopes.coversRead(Set.of("issues.write"), "issue:Read"), "a write scope reads nothing");
    assertFalse(McpScopes.coversRead(Set.of("orders.read"), "made:Up"));
    for (String action : List.of("order:Read", "plan:Read", "loading:Read", "delivery:Read", "receipt:Read",
        "issue:Read", "audit:Read", "iam:ReadPolicy")) {
      assertTrue(McpScopes.coversRead(Set.of("waypoint.read"), action), action);
    }
  }

  @Test
  void aConnectionMadeBeforeScopesKeepsExactlyItsReads() {
    assertEquals(Set.of("waypoint.read"), McpScopes.stored(null));
    assertEquals(Set.of(), McpScopes.stored(List.of()));
  }

  @Test
  void onlyTheSafeWritesExistAndOnlyTheReassignmentCarriesAVersion() {
    assertEquals(List.of("assign_issue", "raise_issue"),
        McpScopes.writeTools().stream().map(McpScopes.WriteTool::name).toList());
    assertTrue(McpScopes.writeTool("generate_draft_plan").isEmpty(), "generating cancels the open draft");
    assertTrue(McpScopes.writeTool("assign_issue").orElseThrow().versioned());
    assertFalse(McpScopes.writeTool("raise_issue").orElseThrow().versioned());
    assertTrue(McpScopes.supported().containsAll(List.of("waypoint.read", "orders.read", "issues.write")));
    assertEquals("issues.write waypoint.read", McpScopes.format(Set.of("waypoint.read", "issues.write")));
  }
}
