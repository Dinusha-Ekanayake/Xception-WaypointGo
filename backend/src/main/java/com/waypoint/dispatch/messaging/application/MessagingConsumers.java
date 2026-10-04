package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryRecordView;
import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.issues.contract.IssueEvents.IssueRaised;
import com.waypoint.dispatch.issues.contract.IssueEvents.IssueResolved;
import com.waypoint.dispatch.issues.contract.IssueQuery;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueView;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.messaging.domain.MessagePolicy;
import com.waypoint.dispatch.messaging.domain.MessagePolicy.ReportOf;
import com.waypoint.dispatch.messaging.domain.MessagePolicy.Role;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository.Thread;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanRevised;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedTrip;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What Messaging hears (issue #136). Each runs as {@code waypoint_messaging} for
 * the system actor and is harmless when delivered twice.
 *
 * <ul>
 *   <li>A published or revised plan opens each trip's thread, before loading, so
 *       a loader's shortfall has a thread to go to.
 *   <li>A raised issue is the field's report: the loader's shortfall, the
 *       driver's fault, road delay or failed delivery, the store's dispute or
 *       missing units. It is posted on its trip's thread for the dispatcher alone
 *       (R-MSG-03), once per event. It publishes nothing: the issue already told
 *       the dispatcher, and nobody hears the same thing twice (R-MSG-05).
 * </ul>
 */
final class MessagingConsumers {
  private MessagingConsumers() {}

  /** Opens or widens the thread of every trip a plan names. */
  static void openTrips(
      JdbcThreadRepository threads, Clock clock, SecureRandom random, String depotCode, LocalDate serviceDate,
      List<PlannedTrip> trips, Metrics metrics) {
    Instant now = clock.now();
    for (PlannedTrip trip : trips) {
      List<String> outlets = trip.stops().stream().map(s -> s.outletId()).distinct().sorted().toList();
      if (threads.openTrip(
          UuidV7.generate(now, random), trip.tripId().toString(), depotCode, trip.vehicleId(), serviceDate, outlets,
          now)) {
        metrics.increment("waypoint.message.thread_opened", "subject", "trip");
      }
    }
  }

  @Component
  static class OnPlanPublished implements EventSubscriber<PlanPublished> {
    private final JdbcThreadRepository threads;
    private final Clock clock;
    private final Metrics metrics;
    private final SecureRandom random = new SecureRandom();

    OnPlanPublished(JdbcThreadRepository threads, Clock clock, Metrics metrics) {
      this.threads = threads;
      this.clock = clock;
      this.metrics = metrics;
    }

    @Override
    public String consumerName() {
      return "messaging.on-plan-published";
    }

    @Override
    public Class<PlanPublished> eventType() {
      return PlanPublished.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<PlanPublished> envelope) {
      PlanPublished e = envelope.payload();
      openTrips(threads, clock, random, e.depotCode(), e.serviceDate(), e.trips(), metrics);
    }
  }

  @Component
  static class OnPlanRevised implements EventSubscriber<PlanRevised> {
    private final JdbcThreadRepository threads;
    private final Clock clock;
    private final Metrics metrics;
    private final SecureRandom random = new SecureRandom();

    OnPlanRevised(JdbcThreadRepository threads, Clock clock, Metrics metrics) {
      this.threads = threads;
      this.clock = clock;
      this.metrics = metrics;
    }

    @Override
    public String consumerName() {
      return "messaging.on-plan-revised";
    }

    @Override
    public Class<PlanRevised> eventType() {
      return PlanRevised.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<PlanRevised> envelope) {
      PlanRevised e = envelope.payload();
      openTrips(threads, clock, random, e.depotCode(), e.serviceDate(), e.trips(), metrics);
    }
  }

  /** R-MSG-03, R-MSG-05: a raised issue becomes a report on its trip's thread, for the dispatcher. */
  /**
   * R-MSG-07: a report made from an issue is resolved when the issue is, so its
   * warning sign leaves the timeline whichever screen settled it. Applying the
   * event twice changes nothing: only open reports are updated.
   */
  @Component
  static class OnIssueResolved implements EventSubscriber<IssueResolved> {
    private final JdbcThreadRepository threads;
    private final Clock clock;
    private final Metrics metrics;

    OnIssueResolved(JdbcThreadRepository threads, Clock clock, Metrics metrics) {
      this.threads = threads;
      this.clock = clock;
      this.metrics = metrics;
    }

    @Override
    public String consumerName() {
      return "messaging.on-issue-resolved";
    }

    @Override
    public Class<IssueResolved> eventType() {
      return IssueResolved.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<IssueResolved> envelope) {
      IssueResolved e = envelope.payload();
      String note = MessagePolicy.excerpt("Issue resolved: " + e.action().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
      int resolved = threads.resolveFromIssue(e.issueId(), Actor.SYSTEM_ID, note, clock.now());
      if (resolved > 0) {
        metrics.count("waypoint.message.report_resolved", resolved, "by", "issue");
      }
    }
  }

  @Component
  static class OnIssueRaised implements EventSubscriber<IssueRaised> {
    private final JdbcThreadRepository threads;
    private final IssueQuery issues;
    private final ExecutionQuery execution;
    private final IdentityQuery identity;
    private final Clock clock;
    private final Metrics metrics;
    private final SecureRandom random = new SecureRandom();

    OnIssueRaised(
        JdbcThreadRepository threads, IssueQuery issues, ExecutionQuery execution, IdentityQuery identity, Clock clock,
        Metrics metrics) {
      this.threads = threads;
      this.issues = issues;
      this.execution = execution;
      this.identity = identity;
      this.clock = clock;
      this.metrics = metrics;
    }

    @Override
    public String consumerName() {
      return "messaging.on-issue-raised";
    }

    @Override
    public Class<IssueRaised> eventType() {
      return IssueRaised.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<IssueRaised> envelope) {
      IssueRaised e = envelope.payload();
      Optional<IssueView> issue = issues.issue(e.issueId());
      Optional<Thread> thread = threadFor(e.subjects());
      if (issue.isEmpty() || thread.isEmpty()) {
        // An issue about no trip on the road, such as a stock problem before planning.
        metrics.increment("waypoint.message.report_without_trip", "type", e.issueType().name());
        return;
      }
      UUID raisedBy = issue.get().raisedBy();
      Optional<Role> byPerson = Actor.SYSTEM_ID.equals(raisedBy) ? Optional.empty() : Optional.of(roleOf(raisedBy));
      Optional<ReportOf> report = MessagePolicy.reportOf(e.issueType().name(), byPerson);
      if (report.isEmpty()) {
        return;
      }
      Instant now = clock.now();
      String body = MessagePolicy.body(cut(issue.get().description()));
      boolean posted =
          threads.insert(
              UuidV7.generate(now, random), thread.get().threadId(), raisedBy, report.get().reporter().code(), "report",
              Optional.of(report.get().reportType()), "dispatch", Optional.empty(), body, Optional.empty(),
              Optional.empty(), Optional.of(envelope.eventId()), Optional.of(e.issueId()), issue.get().outletId(),
              Optional.empty(), now);
      if (posted) {
        metrics.increment("waypoint.message.reported", "type", report.get().reportType(), "role",
            report.get().reporter().code());
      }
    }

    /** The trip an issue is about: named, or through its delivery, its order, or its vehicle today. */
    private Optional<Thread> threadFor(List<SubjectRef> subjects) {
      for (String type : List.of("trip", "delivery", "order", "vehicle")) {
        for (SubjectRef s : subjects) {
          if (!type.equals(s.type())) {
            continue;
          }
          Optional<Thread> found =
              switch (type) {
                case "trip" -> threads.bySubject("trip", s.id());
                case "delivery" -> uuid(s.id()).flatMap(execution::deliveryRecord).flatMap(this::tripOf);
                case "order" -> uuid(s.id()).flatMap(execution::deliveryForOrder).flatMap(this::tripOf);
                default -> threads.tripOfVehicle(s.id(), clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate());
              };
          if (found.isPresent()) {
            return found;
          }
        }
      }
      return Optional.empty();
    }

    private Optional<Thread> tripOf(DeliveryRecordView delivery) {
      return threads.bySubject("trip", delivery.tripId().toString());
    }

    /** The part a person plays: the field roles, else the dispatcher (whose issue is no report). */
    private Role roleOf(UUID userId) {
      List<String> roles = identity.scopeOf(userId).roles();
      if (roles.contains("driver")) return Role.DRIVER;
      if (roles.contains("loader")) return Role.LOADER;
      if (roles.contains("store_manager")) return Role.STORE_MANAGER;
      return Role.DISPATCHER;
    }

    private static Optional<UUID> uuid(String id) {
      try {
        return Optional.of(UUID.fromString(id));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }

    private static String cut(String text) {
      String t = text == null || text.isBlank() ? "Reported a problem" : text.strip();
      return t.length() > MessagePolicy.BODY_MAX ? t.substring(0, MessagePolicy.BODY_MAX) : t;
    }
  }
}
