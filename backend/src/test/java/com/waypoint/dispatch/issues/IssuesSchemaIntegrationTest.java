package com.waypoint.dispatch.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.issues.application.IssueDataQuery;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueSeverity;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueStatus;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueType;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.issues.domain.Issue;
import com.waypoint.dispatch.issues.domain.ResolutionAction;
import com.waypoint.dispatch.issues.domain.SeverityPolicy;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.support.ReceiptIssuesSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The issues schema against a real PostgreSQL: who sees which issue, the inbox
 * order and its keyset, deduplication by source event, and the database's own
 * refusal to let the system close an investigation.
 */
class IssuesSchemaIntegrationTest extends ReceiptIssuesSupport {
  @Autowired JdbcIssueRepository issues;

  @Test
  void theInboxIsMostSevereFirstAndPagesOnAKeyset() throws Exception {
    // A depot of its own day: the inbox is shared, so read only what this test raised.
    Issue low = raised(IssueType.LATE_DELIVERY, IssueSeverity.LOW, Optional.empty(), Actor.SYSTEM_ID);
    Issue critical = raised(IssueType.VEHICLE_FAULT, IssueSeverity.CRITICAL, Optional.empty(), Actor.SYSTEM_ID);

    List<String> seen = new ArrayList<>();
    String after = null;
    do {
      JsonNode page =
          read(dispatcher, "/api/issues?depot=" + depot + "&limit=2" + (after == null ? "" : "&after=" + after), 200);
      page.get("items").forEach(i -> seen.add(i.get("issueId").asText()));
      after = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
    } while (after != null);

    assertTrue(seen.indexOf(critical.issueId().toString()) < seen.indexOf(low.issueId().toString()),
        "most severe first");
    assertEquals(seen.size(), seen.stream().distinct().count(), "a keyset page never repeats a row");
  }

  @Test
  void anotherDepotsInboxIsForbiddenAndAudited() throws Exception {
    long before = denials(farDispatcher.id(), IssueDataQuery.READ);
    read(farDispatcher, "/api/issues?depot=" + depot, 403);
    assertEquals(before + 1, denials(farDispatcher.id(), IssueDataQuery.READ));
  }

  @Test
  void anOutletsManagerSeesItsIssuesAndADriverSeesWhatTheyRaised() throws Exception {
    Issue atOutlet = raised(IssueType.DAMAGED_GOODS, IssueSeverity.HIGH, Optional.of(outlet.outletId()), Actor.SYSTEM_ID);
    Issue byDriver = raised(IssueType.ROAD_DISRUPTION, IssueSeverity.MEDIUM, Optional.empty(), roamingDriver.id());

    read(manager, "/api/issues/" + atOutlet.issueId(), 200);
    read(stranger, "/api/issues/" + atOutlet.issueId(), 404);
    read(roamingDriver, "/api/issues/" + byDriver.issueId(), 200);
    read(roamingDriver, "/api/issues/" + atOutlet.issueId(), 404);
    JsonNode bySubject = read(manager, "/api/issues/by-subject?type=order&id=" + orderOf(atOutlet), 200);
    assertEquals(1, bySubject.size());
  }

  @Test
  void anEventRaisesAtMostOneIssue() {
    Issue first = issue(IssueType.LOADING_SHORTFALL, IssueSeverity.HIGH, Optional.empty(), Actor.SYSTEM_ID,
        Optional.of("loading.shortfall:" + UUID.randomUUID()));
    assertTrue(database.asSystem(ModuleRole.ISSUES, () -> issues.insert(first, Instant.now())));
    Issue again = issue(IssueType.LOADING_SHORTFALL, IssueSeverity.HIGH, Optional.empty(), Actor.SYSTEM_ID,
        first.sourceKey());
    assertFalse(database.asSystem(ModuleRole.ISSUES, () -> issues.insert(again, Instant.now())));
  }

  @Test
  void theDatabaseItselfRefusesASystemResolvedInvestigationAndDeletion() {
    Issue investigation =
        Issue.raise(UUID.randomUUID(), IssueType.RECEIPT_DISPUTE, IssueSeverity.HIGH, depot,
            Optional.of(outlet.outletId()), List.of(new SubjectRef("order", UUID.randomUUID().toString())),
            "short by three", true, Optional.empty(), Actor.SYSTEM_ID, Instant.now());
    database.asSystem(ModuleRole.ISSUES, () -> issues.insert(investigation, Instant.now()));
    // Bypass the domain on purpose: the CHECK is the last line of R-RCP-07.
    Issue forced =
        new Issue(investigation.issueId(), investigation.type(), investigation.severity(), IssueStatus.RESOLVED,
            depot, investigation.outletId(), investigation.subjects(), investigation.description(), true,
            Optional.empty(), Optional.empty(),
            Optional.of(new Issue.Resolution(ResolutionAction.NO_FAULT_FOUND, "auto", Actor.SYSTEM_ID, Instant.now())),
            Optional.empty(), Optional.empty(), Actor.SYSTEM_ID, investigation.raisedAt(), 1);
    assertThrows(
        RuntimeException.class,
        () -> database.asSystem(ModuleRole.ISSUES, () -> issues.update(forced, 1, Instant.now())));
    assertThrows(
        RuntimeException.class,
        () -> database.asSystem(
            ModuleRole.ISSUES,
            () -> database.update("DELETE FROM issues.issues WHERE issue_id = ?", investigation.issueId())));
  }

  @Test
  void theSeededSeverityPolicyIsComplete() {
    SeverityPolicy policy =
        database.asSystem(ModuleRole.ISSUES, () -> SeverityPolicy.from(issues.parameters(LocalDate.now())));
    assertEquals(IssueSeverity.CRITICAL, policy.defaultFor(IssueType.VEHICLE_FAULT));
    assertEquals(15, policy.deadline(IssueSeverity.CRITICAL).toMinutes());
  }

  // ---- fixtures ------------------------------------------------------------

  private final java.util.Map<UUID, UUID> orderOfIssue = new java.util.HashMap<>();

  private Issue raised(IssueType type, IssueSeverity severity, Optional<String> outletId, UUID by) {
    Issue issue = issue(type, severity, outletId, by, Optional.empty());
    database.asSystem(ModuleRole.ISSUES, () -> issues.insert(issue, Instant.now()));
    return issue;
  }

  private Issue issue(IssueType type, IssueSeverity severity, Optional<String> outletId, UUID by, Optional<String> key) {
    UUID order = UUID.randomUUID();
    Issue issue =
        Issue.raise(UUID.randomUUID(), type, severity, depot, outletId,
            List.of(new SubjectRef("order", order.toString())), "raised by a test", false, key, by, Instant.now());
    orderOfIssue.put(issue.issueId(), order);
    return issue;
  }

  private UUID orderOf(Issue issue) {
    return orderOfIssue.get(issue.issueId());
  }
}
