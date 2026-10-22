package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryCompleted;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryFailed;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryProblemReported;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliverySkipped;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.RoadDisruptionReported;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.VehicleFaultReported;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryStarted;
import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryRecordView;
import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.issues.contract.IssueEvents.DecisionAnswered;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
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
 *   <li>The driver's first start on a trip, after loading, opens its channel (R-MSG-08).
 *   <li>A delivered or failed order finishes its store's part of the trip; once
 *       all its orders are done the store's channel goes read only (R-MSG-08).
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
      Map<String, List<UUID>> orders = new TreeMap<>();
      trip.stops().forEach(s -> orders.computeIfAbsent(s.outletId(), k -> new ArrayList<>()).add(s.orderId()));
      threads.joinChannel(trip.tripId().toString(), orders, now);
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

  /**
   * FF-09: a store said Yes; the driver is told on the trip's thread, written by
   * the system from the answer, so nobody types the store or the time. A No is
   * the dispatcher's to act on. Once per event: the event id is kept on the
   * message. The driver's bell is rung by Notification from the same event.
   */
  /** R-ML-43: what Intelligence read a message as, kept on the message for its readers' suggested replies. */
  @Component
  static class OnMessageUnderstood
      implements EventSubscriber<com.waypoint.dispatch.intelligence.contract.ChatEvents.MessageUnderstood> {
    private final JdbcThreadRepository threads;

    OnMessageUnderstood(JdbcThreadRepository threads) {
      this.threads = threads;
    }

    @Override
    public String consumerName() {
      return "messaging.on-message-understood";
    }

    @Override
    public Class<com.waypoint.dispatch.intelligence.contract.ChatEvents.MessageUnderstood> eventType() {
      return com.waypoint.dispatch.intelligence.contract.ChatEvents.MessageUnderstood.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<com.waypoint.dispatch.intelligence.contract.ChatEvents.MessageUnderstood> envelope) {
      var e = envelope.payload();
      threads.understood(e.messageId(), e.act(), e.sure());
    }
  }

  @Component
  static class OnDecisionAnswered implements EventSubscriber<DecisionAnswered> {
    private final JdbcThreadRepository threads;
    private final Clock clock;
    private final Metrics metrics;
    private final SecureRandom random = new SecureRandom();

    OnDecisionAnswered(JdbcThreadRepository threads, Clock clock, Metrics metrics) {
      this.threads = threads;
      this.clock = clock;
      this.metrics = metrics;
    }

    @Override
    public String consumerName() {
      return "messaging.on-decision-answered";
    }

    @Override
    public Class<DecisionAnswered> eventType() {
      return DecisionAnswered.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<DecisionAnswered> envelope) {
      DecisionAnswered e = envelope.payload();
      if (!"yes".equals(e.answer())) {
        return;
      }
      Optional<Thread> thread = threads.bySubject("trip", e.tripId().toString());
      if (thread.isEmpty()) {
        return;
      }
      String body = e.driverNote();
      Instant now = clock.now();
      UUID messageId = UuidV7.generate(now, random);
      boolean posted =
          threads.insert(
              messageId, thread.get().threadId(), Actor.SYSTEM_ID, Role.DISPATCHER.code(), "message", Optional.empty(),
              "driver", Optional.empty(), body, Optional.empty(), Optional.empty(), Optional.of(envelope.eventId()),
              Optional.empty(), Optional.empty(), Optional.empty(), now);
      if (posted) {
        metrics.increment("waypoint.message.decision_told", "kind", e.kind());
      }
    }
  }

  /** R-MSG-08: the driver started the trip, after loading: its channel opens. Later starts change nothing. */
  @Component
  static class OnDeliveryStarted implements EventSubscriber<DeliveryStarted> {
    private final JdbcThreadRepository threads;
    private final Clock clock;

    OnDeliveryStarted(JdbcThreadRepository threads, Clock clock) {
      this.threads = threads;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "messaging.on-delivery-started";
    }

    @Override
    public Class<DeliveryStarted> eventType() {
      return DeliveryStarted.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<DeliveryStarted> envelope) {
      threads.openChannel(envelope.payload().tripId().toString(), clock.now());
    }
  }

  /** R-MSG-08: a delivered order is done for the trip's channel. */
  @Component
  static class OnDeliveryCompleted implements EventSubscriber<DeliveryCompleted> {
    private final JdbcThreadRepository threads;
    private final Clock clock;

    OnDeliveryCompleted(JdbcThreadRepository threads, Clock clock) {
      this.threads = threads;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "messaging.on-delivery-completed";
    }

    @Override
    public Class<DeliveryCompleted> eventType() {
      return DeliveryCompleted.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<DeliveryCompleted> envelope) {
      DeliveryCompleted e = envelope.payload();
      threads.orderDone(e.tripId().toString(), e.outletId(), e.orderId(), clock.now());
    }
  }

  /** R-MSG-08: a failed order is done for the trip's channel too: nothing more is coming on this trip. */
  @Component
  static class OnDeliveryFailed implements EventSubscriber<DeliveryFailed> {
    private final JdbcThreadRepository threads;
    private final Clock clock;

    OnDeliveryFailed(JdbcThreadRepository threads, Clock clock) {
      this.threads = threads;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "messaging.on-delivery-failed";
    }

    @Override
    public Class<DeliveryFailed> eventType() {
      return DeliveryFailed.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<DeliveryFailed> envelope) {
      DeliveryFailed e = envelope.payload();
      threads.orderDone(e.tripId().toString(), e.outletId(), e.orderId(), clock.now());
    }
  }

  /**
   * R-EXE-31: the dispatcher took a store off the trip. The store reads why on
   * the trip's thread, for it alone, and its channel goes read only as for a
   * stop done. Once per event: a redelivery posts nothing new.
   */
  @Component
  static class OnDeliverySkipped implements EventSubscriber<DeliverySkipped> {
    private final JdbcThreadRepository threads;
    private final Clock clock;
    private final Metrics metrics;
    private final SecureRandom random = new SecureRandom();

    OnDeliverySkipped(JdbcThreadRepository threads, Clock clock, Metrics metrics) {
      this.threads = threads;
      this.clock = clock;
      this.metrics = metrics;
    }

    @Override
    public String consumerName() {
      return "messaging.on-delivery-skipped";
    }

    @Override
    public Class<DeliverySkipped> eventType() {
      return DeliverySkipped.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<DeliverySkipped> envelope) {
      DeliverySkipped e = envelope.payload();
      Instant now = clock.now();
      threads.orderDone(e.tripId().toString(), e.outletId(), e.orderId(), now);
      Optional<Thread> thread = threads.bySubject("trip", e.tripId().toString());
      if (thread.isEmpty()) {
        return;
      }
      String body = MessagePolicy.skipNotice(e.then(), e.wholeTrip(), e.reason());
      boolean posted =
          threads.insert(
              UuidV7.generate(now, random), thread.get().threadId(), Actor.SYSTEM_ID, Role.DISPATCHER.code(),
              "message", Optional.empty(), "outlet", Optional.of(e.outletId()), body, Optional.empty(),
              Optional.empty(), Optional.of(envelope.eventId()), Optional.empty(), Optional.empty(), Optional.empty(),
              now);
      if (posted) {
        metrics.increment("waypoint.message.skip_told", "then", e.then().toLowerCase(java.util.Locale.ROOT));
      }
    }
  }

  /**
   * R-MSG-17: a breakdown or a blocked road is said plainly in the trip's room, for
   * everyone on the trip, never in the driver's words; the stores still waiting
   * are also pushed (R-NOT-22). Once per event.
   */
  static void postDelay(
      JdbcThreadRepository threads, Clock clock, Metrics metrics, SecureRandom random, UUID eventId, String vehicleId,
      java.time.LocalDate day, String delay) {
    Optional<Thread> thread = threads.tripOfVehicle(vehicleId, day);
    if (thread.isEmpty()) {
      metrics.increment("waypoint.message.delay_without_trip", "delay", delay);
      return;
    }
    Instant now = clock.now();
    boolean posted =
        threads.insert(
            UuidV7.generate(now, random), thread.get().threadId(), Actor.SYSTEM_ID, Role.DISPATCHER.code(), "message",
            Optional.empty(), "all", Optional.empty(), MessagePolicy.delayNotice(vehicleId, delay), Optional.empty(),
            Optional.empty(), Optional.of(eventId), Optional.empty(), Optional.empty(), Optional.empty(), now);
    if (posted) metrics.increment("waypoint.message.delay_told", "delay", delay);
  }

  @Component
  static class OnVehicleFaultReported implements EventSubscriber<VehicleFaultReported> {
    private final JdbcThreadRepository threads;
    private final Clock clock;
    private final Metrics metrics;
    private final SecureRandom random = new SecureRandom();

    OnVehicleFaultReported(JdbcThreadRepository threads, Clock clock, Metrics metrics) {
      this.threads = threads;
      this.clock = clock;
      this.metrics = metrics;
    }

    @Override
    public String consumerName() {
      return "messaging.on-vehicle-fault-reported";
    }

    @Override
    public Class<VehicleFaultReported> eventType() {
      return VehicleFaultReported.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<VehicleFaultReported> envelope) {
      VehicleFaultReported e = envelope.payload();
      postDelay(threads, clock, metrics, random, envelope.eventId(), e.vehicleId(), e.serviceDate(), "breakdown");
    }
  }

  @Component
  static class OnRoadDisruptionReported implements EventSubscriber<RoadDisruptionReported> {
    private final JdbcThreadRepository threads;
    private final Clock clock;
    private final Metrics metrics;
    private final SecureRandom random = new SecureRandom();

    OnRoadDisruptionReported(JdbcThreadRepository threads, Clock clock, Metrics metrics) {
      this.threads = threads;
      this.clock = clock;
      this.metrics = metrics;
    }

    @Override
    public String consumerName() {
      return "messaging.on-road-disruption-reported";
    }

    @Override
    public Class<RoadDisruptionReported> eventType() {
      return RoadDisruptionReported.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<RoadDisruptionReported> envelope) {
      RoadDisruptionReported e = envelope.payload();
      postDelay(threads, clock, metrics, random, envelope.eventId(), e.vehicleId(),
          e.at().atZone(Clock.OPERATING_ZONE).toLocalDate(), "road blocked");
    }
  }

  /**
   * R-EXE-32: the driver reports a closed store or a stop that cannot unload. The
   * dispatcher reads it as a report on the trip's thread, beside which the
   * suggested decisions follow (R-ML-26); the store reads, for it alone, that the
   * dispatcher decides. Each post is once per event: a redelivery adds nothing.
   */
  @Component
  static class OnDeliveryProblemReported implements EventSubscriber<DeliveryProblemReported> {
    private final JdbcThreadRepository threads;
    private final Clock clock;
    private final Metrics metrics;
    private final SecureRandom random = new SecureRandom();

    OnDeliveryProblemReported(JdbcThreadRepository threads, Clock clock, Metrics metrics) {
      this.threads = threads;
      this.clock = clock;
      this.metrics = metrics;
    }

    @Override
    public String consumerName() {
      return "messaging.on-delivery-problem-reported";
    }

    @Override
    public Class<DeliveryProblemReported> eventType() {
      return DeliveryProblemReported.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<DeliveryProblemReported> envelope) {
      DeliveryProblemReported e = envelope.payload();
      Optional<Thread> thread = threads.bySubject("trip", e.tripId().toString());
      if (thread.isEmpty()) {
        metrics.increment("waypoint.message.report_without_trip", "type", e.problem());
        return;
      }
      Instant now = clock.now();
      UUID driver = envelope.actorId().orElse(Actor.SYSTEM_ID);
      String type = e.problem().toLowerCase(java.util.Locale.ROOT);
      boolean reported =
          threads.insert(
              UuidV7.generate(now, random), thread.get().threadId(), driver, Role.DRIVER.code(), "report",
              Optional.of(type), "dispatch", Optional.empty(), MessagePolicy.stopProblemReport(e.problem()),
              Optional.empty(), Optional.empty(), Optional.of(envelope.eventId()), Optional.empty(),
              Optional.of(e.outletId()), Optional.empty(), now);
      // A second id of its own, derived from the event's, so the store's notice is once per event too.
      UUID noticeSource = UUID.nameUUIDFromBytes((envelope.eventId() + ":store").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      threads.insert(
          UuidV7.generate(now, random), thread.get().threadId(), Actor.SYSTEM_ID, Role.DISPATCHER.code(), "message",
          Optional.empty(), "outlet", Optional.of(e.outletId()), MessagePolicy.stopProblemNotice(e.problem()),
          Optional.empty(), Optional.empty(), Optional.of(noticeSource), Optional.empty(), Optional.empty(),
          Optional.empty(), now);
      if (reported) {
        metrics.increment("waypoint.message.reported", "type", type, "role", Role.DRIVER.code());
      }
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
