package com.waypoint.dispatch.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.domain.policy.AccessRequest;
import com.waypoint.dispatch.identity.domain.policy.Decision;
import com.waypoint.dispatch.identity.domain.policy.PolicyEvaluator;
import com.waypoint.dispatch.identity.domain.policy.Statement;
import com.waypoint.dispatch.identity.infrastructure.PolicyDocumentParser;
import com.waypoint.dispatch.platform.config.DirectoryLocator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests the policies actually shipped in {@code 006_iam_policies.sql}.
 *
 * <p>Deliberately parses the migration rather than restating the documents in
 * the test. A copy would pass forever while the shipped policy quietly drifted,
 * which is the failure this is meant to catch. These are the permissions every
 * role gets on a fresh installation, so they deserve more than a review.
 */
class SeededPolicyTest {
  private static final PolicyDocumentParser PARSER = new PolicyDocumentParser(new ObjectMapper());
  private static Map<String, List<Statement>> policies;

  @BeforeAll
  static void loadShippedPolicies() throws IOException {
    Path migration =
        DirectoryLocator.resolve("../migrations", "migrations").resolve("006_iam_policies.sql");
    String sql = Files.readString(migration, StandardCharsets.UTF_8);

    // Each seeded policy appears as ('Name', '{ ... }'::jsonb)
    Matcher matcher =
        Pattern.compile("\\('(Waypoint\\w+)',\\s*'(\\{.*?\\})'::jsonb\\)", Pattern.DOTALL)
            .matcher(sql);
    Map<String, List<Statement>> found = new HashMap<>();
    while (matcher.find()) {
      // SQL escapes a single quote by doubling it.
      String json = matcher.group(2).replace("''", "'");
      found.put(matcher.group(1), PARSER.parse(json).statements());
    }
    policies = found;
  }

  @Test
  void everyRoleHasASeededPolicyThatParses() {
    assertEquals(
        List.of(
            "WaypointAdministrator",
            "WaypointAuditor",
            "WaypointDispatcher",
            "WaypointDriver",
            "WaypointLoader",
            "WaypointStoreManager"),
        policies.keySet().stream().sorted().toList());
  }

  @Test
  void theAdministratorCanImportReferenceData() {
    assertTrue(allows("WaypointAdministrator", "reference:Import"));
  }

  @Test
  void aDriverCannotImportReferenceData() {
    assertFalse(allows("WaypointDriver", "reference:Import"));
  }

  @Test
  void aDriverCanRecordDeliveriesAndReadThePlan() {
    assertTrue(allows("WaypointDriver", "delivery:Record"));
    assertTrue(allows("WaypointDriver", "delivery:CaptureProof"));
    assertTrue(allows("WaypointDriver", "plan:Read"));
  }

  @Test
  void aDriverCannotPublishAPlan() {
    assertFalse(allows("WaypointDriver", "plan:Publish"));
  }

  @Test
  void aStoreManagerCanOrderButNotPlan() {
    assertTrue(allows("WaypointStoreManager", "order:Place"));
    assertTrue(allows("WaypointStoreManager", "receipt:Confirm"));
    assertFalse(allows("WaypointStoreManager", "plan:Publish"));
  }

  @Test
  void aLoaderCanWorkTheDockButNotDeliver() {
    assertTrue(allows("WaypointLoader", "loading:Release"));
    assertFalse(allows("WaypointLoader", "delivery:Record"));
  }

  @Test
  void aDispatcherIsFencedOutOfAdministrationByAnExplicitDeny() {
    // The dispatcher policy allows plan:* broadly, then denies the iam verbs.
    assertTrue(allows("WaypointDispatcher", "plan:Publish"));

    Decision decision = decide("WaypointDispatcher", "iam:CreateUser");
    assertFalse(decision.allowed());
    assertEquals(
        "NeverAdminister",
        decision.matchedStatementSid(),
        "the denial must name the statement responsible, not fall through to default deny");
  }

  @Test
  void theAuditorReadsEverythingAndWritesNothing() {
    assertTrue(allows("WaypointAuditor", "order:Read"));
    assertTrue(allows("WaypointAuditor", "plan:Read"));
    assertTrue(allows("WaypointAuditor", "audit:Read"));

    for (String write :
        List.of("order:Place", "plan:Publish", "delivery:Record", "receipt:Confirm",
            "reference:Import", "iam:CreateUser")) {
      Decision decision = decide("WaypointAuditor", write);
      assertFalse(decision.allowed(), write + " must be denied for an auditor");
      assertEquals("NeverWrite", decision.matchedStatementSid(), write);
    }
  }

  @Test
  void anActionNoPolicyMentionsIsDeniedByDefault() {
    Decision decision = decide("WaypointDriver", "warehouse:Reconcile");

    assertFalse(decision.allowed());
    assertTrue(decision.reason().contains("no policy allows"), decision.reason());
  }

  private static boolean allows(String policy, String action) {
    return decide(policy, action).allowed();
  }

  private static Decision decide(String policy, String action) {
    return PolicyEvaluator.evaluate(
        policies.get(policy), new AccessRequest(action, "*", Map.of()));
  }
}
