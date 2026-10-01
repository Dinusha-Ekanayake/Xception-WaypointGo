package com.waypoint.dispatch.issues.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.issues.contract.IssueViews.IssueSeverity;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueStatus;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueType;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The issue lifecycle and its rules, with no database and the time passed in. */
class IssueTest {
  static final Instant RAISED = Instant.parse("2026-10-05T05:00:00Z");
  static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
  static final UUID DISPATCHER = UUID.randomUUID();
  final UUID order = UUID.randomUUID();
  final UUID trip = UUID.randomUUID();

  Issue raise(IssueType type, boolean investigation) {
    return Issue.raise(
        UUID.randomUUID(), type, IssueSeverity.HIGH, "Peliyagoda", Optional.of("OUT001"),
        List.of(new SubjectRef("order", order.toString()), new SubjectRef("trip", trip.toString())),
        "two cartons missing", investigation, Optional.empty(), DISPATCHER, RAISED);
  }

  @Test
  void anIssueIsRaisedOpenAboutAtLeastOneKnownSubject() {
    Issue issue = raise(IssueType.DAMAGED_GOODS, false);
    assertEquals(IssueStatus.OPEN, issue.status());

    DomainException none =
        assertThrows(
            DomainException.class,
            () -> Issue.raise(UUID.randomUUID(), IssueType.OTHER, IssueSeverity.LOW, "Peliyagoda", Optional.empty(),
                List.of(), "something", false, Optional.empty(), DISPATCHER, RAISED));
    assertEquals(List.of("R-ISS-03"), none.rules());
    assertThrows(
        DomainException.class,
        () -> Issue.raise(UUID.randomUUID(), IssueType.OTHER, IssueSeverity.LOW, "Peliyagoda", Optional.empty(),
            List.of(new SubjectRef("allocation", "x")), "something", false, Optional.empty(), DISPATCHER, RAISED));
  }

  @Test
  void aVehicleAloneIsAValidSubject() {
    Issue fault =
        Issue.raise(UUID.randomUUID(), IssueType.VEHICLE_FAULT, IssueSeverity.CRITICAL, "Peliyagoda", Optional.empty(),
            List.of(new SubjectRef("vehicle", "VEH001")), "brakes", false, Optional.empty(), Actor.SYSTEM_ID, RAISED);
    assertEquals(1, fault.subjects().size());
  }

  @Test
  void aResolutionRecordsAnActionAReasonAPersonAndATime() {
    Issue resolved =
        raise(IssueType.DAMAGED_GOODS, false).assign(DISPATCHER)
            .resolve(ResolutionAction.WRITE_OFF, " written off with the supplier ", DISPATCHER, RAISED.plusSeconds(60));

    assertEquals(IssueStatus.RESOLVED, resolved.status());
    Issue.Resolution how = resolved.resolution().orElseThrow();
    assertEquals(ResolutionAction.WRITE_OFF, how.action());
    assertEquals("written off with the supplier", how.note());
    assertEquals(DISPATCHER, how.by());

    assertThrows(
        DomainException.class,
        () -> raise(IssueType.OTHER, false).resolve(ResolutionAction.OTHER, "", DISPATCHER, RAISED),
        "rule 8: no resolution without a reason");
  }

  @Test
  void thereIsNoReturnsWorkflow() {
    DomainException refused = assertThrows(DomainException.class, () -> ResolutionAction.parse("return"));
    assertEquals(List.of("R-EXE-12"), refused.rules());
    assertEquals(ResolutionAction.NO_FAULT_FOUND, ResolutionAction.parse(" no_fault_found "));
  }

  @Test
  void aShortageInvestigationIsNeverResolvedByTheSystem() {
    Issue investigation = raise(IssueType.RECEIPT_DISPUTE, true);

    for (Runnable attempt :
        List.<Runnable>of(
            () -> investigation.resolve(ResolutionAction.NO_FAULT_FOUND, "auto", Actor.SYSTEM_ID, RAISED),
            () -> investigation.cancel("auto", Actor.SYSTEM_ID, RAISED))) {
      DomainException refused = assertThrows(DomainException.class, attempt::run);
      assertEquals(List.of("R-RCP-07"), refused.rules());
    }
    assertEquals(
        IssueStatus.RESOLVED,
        investigation.resolve(ResolutionAction.NO_FAULT_FOUND, "photo shows a full load", DISPATCHER, RAISED).status(),
        "a person may");
  }

  @Test
  void aReplacementOnlyAnswersALoadingShortfallAboutThatTripAndOrder() {
    Issue shortfall = raise(IssueType.LOADING_SHORTFALL, false);
    Issue done = shortfall.recordReplacement(trip, order, "replacement carton loaded", DISPATCHER, RAISED);
    assertEquals(ResolutionAction.REPLACEMENT, done.resolution().orElseThrow().action());

    assertEquals(
        ErrorCode.CONFLICT,
        assertThrows(DomainException.class,
            () -> raise(IssueType.DAMAGED_GOODS, false).recordReplacement(trip, order, "x x", DISPATCHER, RAISED))
            .code());
    assertThrows(
        DomainException.class,
        () -> shortfall.recordReplacement(UUID.randomUUID(), order, "wrong trip", DISPATCHER, RAISED));
  }

  @Test
  void aRedeliveryIsRequestedAtMostOnceForAnOrderTheIssueIsAbout() {
    Issue failed = raise(IssueType.FAILED_DELIVERY, false);
    Issue requested = failed.scheduleRedelivery(order, TODAY.plusDays(1), TODAY, "outlet closed", DISPATCHER, RAISED);
    assertTrue(requested.redeliveryRequestedAt().isPresent());
    assertEquals(ResolutionAction.REDELIVERY, requested.resolution().orElseThrow().action());

    assertThrows(
        DomainException.class,
        () -> requested.scheduleRedelivery(order, TODAY.plusDays(2), TODAY, "again", DISPATCHER, RAISED),
        "exactly one redelivery.requested per issue");
    assertThrows(
        DomainException.class,
        () -> failed.scheduleRedelivery(UUID.randomUUID(), TODAY, TODAY, "other order", DISPATCHER, RAISED));
    assertThrows(
        DomainException.class,
        () -> failed.scheduleRedelivery(order, TODAY.minusDays(1), TODAY, "yesterday", DISPATCHER, RAISED));
    assertThrows(
        DomainException.class,
        () -> raise(IssueType.VEHICLE_FAULT, false)
            .scheduleRedelivery(order, TODAY, TODAY, "not that kind", DISPATCHER, RAISED));
  }

  @Test
  void aRedeliveryAnswersOnlyAnIssueWhereNothingArrived() {
    for (IssueType arrived : List.of(IssueType.RECEIPT_DISPUTE, IssueType.DAMAGED_GOODS, IssueType.LATE_DELIVERY)) {
      DomainException refused =
          assertThrows(
              DomainException.class,
              () -> raise(arrived, false).scheduleRedelivery(order, TODAY, TODAY, "resend", DISPATCHER, RAISED),
              arrived + ": goods reached the outlet; a whole-order redelivery would ship them twice (A-24)");
      assertEquals(List.of("R-ISS-04"), refused.rules());
    }
    assertEquals(
        ResolutionAction.REDELIVERY,
        raise(IssueType.STOCK_DISCREPANCY, false)
            .scheduleRedelivery(order, TODAY, TODAY, "cancelled at the warehouse", DISPATCHER, RAISED)
            .resolution().orElseThrow().action());
  }

  @Test
  void anUnassignedIssueEscalatesOnceAtItsDeadline() {
    Issue issue = raise(IssueType.DAMAGED_GOODS, false);
    Duration hour = Duration.ofHours(1);
    assertFalse(issue.isOverdue(RAISED.plus(hour).minusSeconds(1), hour));

    Issue escalated = issue.escalate(RAISED.plus(hour), hour);
    assertEquals(Optional.of(RAISED.plus(hour)), escalated.escalatedAt());
    assertFalse(escalated.isOverdue(RAISED.plus(Duration.ofDays(1)), hour), "escalates once");
    assertFalse(issue.assign(DISPATCHER).isOverdue(RAISED.plus(Duration.ofDays(1)), hour), "someone owns it");
  }

  @Test
  void theLifecycleEndsInClosedOrCancelled() {
    assertEquals(
        EnumSet.of(IssueStatus.ASSIGNED, IssueStatus.RESOLVED, IssueStatus.CANCELLED),
        IssueLifecycle.next(IssueStatus.OPEN));
    assertTrue(IssueLifecycle.isEdge(IssueStatus.ASSIGNED, IssueStatus.ASSIGNED), "reassign");
    assertTrue(IssueLifecycle.next(IssueStatus.CLOSED).isEmpty());
    assertTrue(IssueLifecycle.next(IssueStatus.CANCELLED).isEmpty());
    Issue resolved = raise(IssueType.OTHER, false).resolve(ResolutionAction.OTHER, "done here", DISPATCHER, RAISED);
    assertEquals(IssueStatus.CLOSED, resolved.close(DISPATCHER).status());
    assertThrows(DomainException.class, () -> resolved.cancel("too late", DISPATCHER, RAISED));
  }

  @Test
  void theSeverityPolicyIsCompleteOrRefused() {
    Map<String, String> parameters = new HashMap<>();
    for (IssueType t : IssueType.values()) {
      parameters.put(SeverityPolicy.DEFAULT_PREFIX + t.name(), "medium");
    }
    for (IssueSeverity s : IssueSeverity.values()) {
      parameters.put(SeverityPolicy.DEADLINE_PREFIX + s.name(), "60");
    }
    parameters.put(SeverityPolicy.DEFAULT_PREFIX + "VEHICLE_FAULT", "CRITICAL");
    SeverityPolicy policy = SeverityPolicy.from(parameters);
    assertEquals(IssueSeverity.CRITICAL, policy.defaultFor(IssueType.VEHICLE_FAULT));
    assertEquals(Duration.ofMinutes(60), policy.deadline(IssueSeverity.LOW));

    parameters.remove(SeverityPolicy.DEADLINE_PREFIX + "LOW");
    DomainException refused = assertThrows(DomainException.class, () -> SeverityPolicy.from(parameters));
    assertEquals(List.of("POL-10"), refused.rules());
  }
}
