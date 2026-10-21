package com.waypoint.dispatch.identity.domain.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** R-IAM-42: the editor's "What changes" and its per-statement checks. */
class PolicyChangeTest {
  private static Statement allow(String action, String resource) {
    return new Statement("S", Effect.ALLOW, List.of(new Pattern(action)), List.of(new Pattern(resource)), List.of());
  }

  private static Statement deny(String action) {
    return new Statement("D", Effect.DENY, List.of(new Pattern(action)), List.of(Pattern.ANY), List.of());
  }

  private static final List<String> CATALOGUE = List.of("plan:Publish", "plan:Read", "audit:Read");

  @Test
  void anActionAddedIsGainedOneDroppedIsLostAndANarrowerResourceIsAChangedReach() {
    var delta = PolicyChange.compare(List.of(),
        List.of(allow("plan:Read", "wpt:plan:*"), allow("plan:Publish", "*")),
        List.of(allow("plan:Read", "wpt:plan:depot:Kandy"), allow("audit:Read", "*")), CATALOGUE);
    assertEquals(List.of("audit:Read"), delta.gained());
    assertEquals(List.of("plan:Publish"), delta.lost());
    assertEquals(List.of("plan:Read"), delta.reachChanged());
  }

  @Test
  void whatAnotherPolicyStillGrantsOrDeniesIsNoChange() {
    var granted = PolicyChange.compare(List.of(allow("plan:Publish", "*")),
        List.of(allow("plan:Publish", "*")), List.of(allow("audit:Read", "*")), CATALOGUE);
    assertEquals(List.of(), granted.lost(), "another policy still allows it");
    var denied = PolicyChange.compare(List.of(deny("audit:Read")),
        List.of(), List.of(allow("audit:Read", "*")), CATALOGUE);
    assertTrue(denied.none(), "a Deny elsewhere wins, so nothing is gained");
  }

  @Test
  void anUnknownActionAndAResourceOfTheWrongShapeAreNamedWithTheirStatement() {
    var doc = new PolicyDocument("t", List.of(allow("plan:Read", "wpt:plan:*"), allow("plan:Raed", "plan-everything")));
    var problems = PolicyLint.check(doc, CATALOGUE);
    assertEquals(2, problems.size(), problems.toString());
    assertTrue(problems.stream().allMatch(p -> p.statement() == 2));
    assertTrue(PolicyLint.check(new PolicyDocument("t", List.of(allow("plan:*", "*"))), CATALOGUE).isEmpty());
  }
}
