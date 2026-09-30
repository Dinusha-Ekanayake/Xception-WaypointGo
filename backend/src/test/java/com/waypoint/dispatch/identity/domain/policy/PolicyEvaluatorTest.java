package com.waypoint.dispatch.identity.domain.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The evaluation contract, exhaustively.
 *
 * <p>This is the most security-sensitive code in the system and it runs with no
 * database, no clock and no framework, so there is no excuse for testing it
 * lightly. Every case here is a way authorization could silently go wrong.
 */
class PolicyEvaluatorTest {

  // ---- the four ordering rules ----

  @Test
  void nothingIsAllowedByDefault() {
    Decision decision = evaluate(List.of(), request("order:Place", "wpt:ref:outlet:OUT001"));

    assertFalse(decision.allowed());
    assertTrue(decision.reason().contains("no policy allows"), decision.reason());
  }

  @Test
  void aMatchingAllowGrants() {
    Decision decision =
        evaluate(
            List.of(allow("Place", "order:Place", "*")),
            request("order:Place", "wpt:ref:outlet:OUT001"));

    assertTrue(decision.allowed());
    assertEquals("Place", decision.matchedStatementSid());
  }

  @Test
  void anExplicitDenyBeatsAnAllow() {
    // The whole point of Deny: fencing a broad grant without rewriting it.
    Decision decision =
        evaluate(
            List.of(allow("Broad", "order:*", "*"), deny("Fence", "order:Cancel", "*")),
            request("order:Cancel", "wpt:ref:outlet:OUT001"));

    assertFalse(decision.allowed());
    assertEquals("Fence", decision.matchedStatementSid());
  }

  @Test
  void denyWinsRegardlessOfStatementOrder() {
    Decision denyFirst =
        evaluate(
            List.of(deny("Fence", "order:Cancel", "*"), allow("Broad", "order:*", "*")),
            request("order:Cancel", null));

    assertFalse(denyFirst.allowed(), "a Deny written first must still win");
  }

  @Test
  void anUnrelatedStatementDoesNotGrant() {
    Decision decision =
        evaluate(List.of(allow("Other", "plan:Publish", "*")), request("order:Place", null));

    assertFalse(decision.allowed());
  }

  // ---- wildcards ----

  @Test
  void aTrailingWildcardMatchesEveryVerbInTheModule() {
    assertTrue(evaluate(List.of(allow("All", "order:*", "*")), request("order:Amend", null)).allowed());
  }

  @Test
  void aModuleWildcardDoesNotLeakIntoAnotherModule() {
    assertFalse(
        evaluate(List.of(allow("All", "order:*", "*")), request("plan:Publish", null)).allowed());
  }

  @Test
  void resourceWildcardsMatchByPrefix() {
    Decision decision =
        evaluate(
            List.of(allow("Depot", "plan:Publish", "wpt:ref:depot:PELIYAGODA*")),
            request("plan:Publish", "wpt:ref:depot:PELIYAGODA"));

    assertTrue(decision.allowed());
  }

  @Test
  void aResourceOutsideThePatternIsNotGranted() {
    Decision decision =
        evaluate(
            List.of(allow("Depot", "plan:Publish", "wpt:ref:depot:PELIYAGODA")),
            request("plan:Publish", "wpt:ref:depot:KANDY"));

    assertFalse(decision.allowed(), "a Kandy plan must not be covered by a Peliyagoda grant");
  }

  @Test
  void aStatementWithNoResourceCoversTheWholeAction() {
    Statement anyResource =
        new Statement("Any", Effect.ALLOW, List.of(new Pattern("order:Read")), List.of(), List.of());

    assertTrue(evaluate(List.of(anyResource), request("order:Read", "wpt:ops:order:1")).allowed());
  }

  // ---- conditions ----

  @Test
  void aConditionThatHoldsAllowsTheStatement() {
    Statement statement =
        withCondition(
            ConditionOperator.STRING_EQUALS, "wpt:depot", "Peliyagoda");

    Decision decision =
        PolicyEvaluator.evaluate(
            List.of(statement),
            new AccessRequest("plan:Publish", "*", Map.of("wpt:depot", "Peliyagoda")));

    assertTrue(decision.allowed());
  }

  @Test
  void aConditionThatFailsBlocksTheStatement() {
    Statement statement =
        withCondition(ConditionOperator.STRING_EQUALS, "wpt:depot", "Peliyagoda");

    Decision decision =
        PolicyEvaluator.evaluate(
            List.of(statement),
            new AccessRequest("plan:Publish", "*", Map.of("wpt:depot", "Kandy")));

    assertFalse(decision.allowed());
  }

  @Test
  void aMissingContextKeyFailsTheConditionRatherThanPassingIt() {
    // Treating an absent key as satisfied would grant access whenever a caller
    // simply forgot to supply it. That is the dangerous default.
    Statement statement =
        withCondition(ConditionOperator.STRING_EQUALS, "wpt:depot", "Peliyagoda");

    Decision decision =
        PolicyEvaluator.evaluate(List.of(statement), new AccessRequest("plan:Publish", "*", Map.of()));

    assertFalse(decision.allowed());
  }

  @Test
  void dateConditionsCompareInstants() {
    Statement before =
        withCondition(ConditionOperator.DATE_LESS_THAN, "wpt:now", "2027-01-01T00:00:00Z");

    assertTrue(
        PolicyEvaluator.evaluate(
                List.of(before),
                new AccessRequest(
                    "plan:Publish", "*", Map.of("wpt:now", Instant.parse("2026-09-27T00:00:00Z").toString())))
            .allowed());

    assertFalse(
        PolicyEvaluator.evaluate(
                List.of(before),
                new AccessRequest(
                    "plan:Publish", "*", Map.of("wpt:now", Instant.parse("2028-01-01T00:00:00Z").toString())))
            .allowed());
  }

  @Test
  void stringLikeSupportsWildcardsInConditions() {
    Statement statement =
        withCondition(ConditionOperator.STRING_LIKE, "wpt:vehicleId", "VEH0*");

    assertTrue(
        PolicyEvaluator.evaluate(
                List.of(statement),
                new AccessRequest("plan:Publish", "*", Map.of("wpt:vehicleId", "VEH014")))
            .allowed());
  }

  @Test
  void everyConditionClauseMustHold() {
    Statement both =
        new Statement(
            "Both",
            Effect.ALLOW,
            List.of(new Pattern("plan:Publish")),
            List.of(Pattern.ANY),
            List.of(
                new Condition(ConditionOperator.STRING_EQUALS, "wpt:depot", List.of("Peliyagoda")),
                new Condition(ConditionOperator.BOOL, "wpt:onShift", List.of("true"))));

    assertFalse(
        PolicyEvaluator.evaluate(
                List.of(both),
                new AccessRequest("plan:Publish", "*", Map.of("wpt:depot", "Peliyagoda")))
            .allowed(),
        "one failing clause must fail the statement");
  }

  @Test
  void severalValuesForOneKeyAreAnOr() {
    Statement either =
        new Statement(
            "Either",
            Effect.ALLOW,
            List.of(new Pattern("plan:Publish")),
            List.of(Pattern.ANY),
            List.of(
                new Condition(
                    ConditionOperator.STRING_EQUALS, "wpt:depot", List.of("Peliyagoda", "Kandy"))));

    assertTrue(
        PolicyEvaluator.evaluate(
                List.of(either), new AccessRequest("plan:Publish", "*", Map.of("wpt:depot", "Kandy")))
            .allowed());
  }

  // ---- explanation ----

  @Test
  void aDenialNamesTheStatementResponsible() {
    Decision decision =
        evaluate(
            List.of(allow("Broad", "*", "*"), deny("NoCancel", "order:Cancel", "*")),
            request("order:Cancel", null));

    assertEquals("NoCancel", decision.sid().orElseThrow());
    assertTrue(decision.reason().contains("NoCancel"));
  }

  // ---- fixtures ----

  private static Decision evaluate(List<Statement> statements, AccessRequest request) {
    return PolicyEvaluator.evaluate(statements, request);
  }

  private static AccessRequest request(String action, String resource) {
    return AccessRequest.of(action, resource);
  }

  private static Statement allow(String sid, String action, String resource) {
    return new Statement(
        sid, Effect.ALLOW, List.of(new Pattern(action)), List.of(new Pattern(resource)), List.of());
  }

  private static Statement deny(String sid, String action, String resource) {
    return new Statement(
        sid, Effect.DENY, List.of(new Pattern(action)), List.of(new Pattern(resource)), List.of());
  }

  private static Statement withCondition(ConditionOperator operator, String key, String value) {
    return new Statement(
        "Conditional",
        Effect.ALLOW,
        List.of(new Pattern("plan:Publish")),
        List.of(Pattern.ANY),
        List.of(new Condition(operator, key, List.of(value))));
  }
}
