package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.identity.contract.PersonQuery;
import com.waypoint.dispatch.messaging.contract.MessagingCommands;
import com.waypoint.dispatch.messaging.contract.MessagingViews.MemberView;
import com.waypoint.dispatch.messaging.contract.MessagingViews.MessagePage;
import com.waypoint.dispatch.messaging.contract.MessagingViews.MessageView;
import com.waypoint.dispatch.messaging.contract.MessagingViews.ReportMarkView;
import com.waypoint.dispatch.messaging.contract.MessagingViews.ThreadView;
import com.waypoint.dispatch.messaging.domain.MessagePolicy;
import com.waypoint.dispatch.messaging.domain.MessagePolicy.Role;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository.Belonging;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository.Message;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository.Thread;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Cursor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Reading threads (issue #136). Everything is read under the actor's row-level
 * security, so a message they may not see is absent, never blanked (R-MSG-01).
 * A thread or a depot outside their scope is a 403 plus an audit row, never an
 * empty answer that reads as "nothing was said".
 */
@Component
public class MessagingQuery {
  private static final String READ = MessagingCommands.READ;

  private final Database database;
  private final JdbcThreadRepository threads;
  private final PersonQuery people;
  private final IdentityQuery identity;
  private final AuditLog audit;
  private final Clock clock;

  public MessagingQuery(
      Database database, JdbcThreadRepository threads, PersonQuery people, IdentityQuery identity, AuditLog audit,
      Clock clock) {
    this.database = database;
    this.threads = threads;
    this.people = people;
    this.identity = identity;
    this.audit = audit;
    this.clock = clock;
  }

  public ThreadView thread(Actor actor, UUID threadId) {
    return read(actor, () -> view(threads.thread(threadId), threadId))
        .orElseThrow(() -> denied(actor, "wpt:message:thread:" + threadId));
  }

  /** The thread of a subject, for a screen that knows the trip, not the thread. */
  public ThreadView bySubject(Actor actor, String subjectType, String subjectId) {
    if (!"trip".equals(subjectType)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "type must be trip");
    }
    // Whether it exists is asked as the system, so that "not yours" is an audited
    // 403 and only "not opened yet" is a 404 (a trip's thread opens with its plan).
    Optional<Thread> exists =
        database.readAs(ModuleRole.MESSAGING, Actor.SYSTEM_ID, () -> threads.bySubject(subjectType, subjectId));
    if (exists.isEmpty()) {
      throw new DomainException(ErrorCode.NOT_FOUND, "no thread for " + subjectType + " " + subjectId);
    }
    return thread(actor, exists.get().threadId());
  }

  public MessagePage messages(Actor actor, UUID threadId, Optional<String> cursor, Integer limit) {
    int size = Page.limit(limit);
    List<String> key = cursor.map(c -> Cursor.decode(c, 2)).orElse(List.of());
    Optional<Instant> beforeAt;
    Optional<UUID> beforeId;
    try {
      beforeAt = key.isEmpty() ? Optional.empty() : Optional.of(Instant.parse(key.get(0)));
      beforeId = key.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(key.get(1)));
    } catch (RuntimeException e) {
      throw Cursor.invalid();
    }
    List<Message> rows =
        read(actor, () -> view(threads.thread(threadId), threadId)
                .map(visible -> threads.page(threadId, beforeAt, beforeId, size + 1)))
            .orElseThrow(() -> denied(actor, "wpt:message:thread:" + threadId));
    Page<Message> page =
        Page.fromOverfetch(rows, size, m -> Cursor.encode(m.createdAt().toString(), m.messageId().toString()));
    Map<UUID, String> names = new HashMap<>();
    List<MessageView> items = new ArrayList<>();
    for (Message m : page.items()) {
      items.add(
          new MessageView(
              m.messageId(), m.threadId(), name(m, names), m.authorRole(), m.kind(), m.reportType(), m.audience(),
              m.audienceOutlet(), m.body(), m.voiceNoteId(), m.voiceDurationMs(), m.createdAt(),
              m.authorUserId().equals(actor.userId())));
    }
    return new MessagePage(items, page.nextCursor());
  }

  /** Who the reader may write to on this thread, as R-MSG-02 allows them. */
  public List<MemberView> members(Actor actor, UUID threadId) {
    ThreadView t = thread(actor, threadId);
    List<MemberView> out = new ArrayList<>();
    switch (t.memberRole()) {
      case "dispatcher" -> {
        String driver =
            t.vehicleId()
                .flatMap(v -> identity.driverOn(v, t.serviceDate().orElse(today())))
                .flatMap(people::person)
                .map(p -> p.displayName() + p.employeeCode().map(c -> " (" + c + ")").orElse(""))
                .orElse("The driver");
        if (t.vehicleId().isPresent()) {
          out.add(new MemberView("driver", Optional.empty(), driver));
        }
        out.add(new MemberView("loader", Optional.empty(), "Loaders at " + t.depotCode()));
        t.outletIds().forEach(o -> out.add(new MemberView("outlet", Optional.of(o), o)));
        out.add(new MemberView("all", Optional.empty(), "Everyone on the trip"));
      }
      case "driver" -> {
        out.add(new MemberView("dispatch", Optional.empty(), "Dispatcher"));
        t.outletIds().forEach(o -> out.add(new MemberView("outlet", Optional.of(o), o)));
      }
      default -> out.add(new MemberView("dispatch", Optional.empty(), "Dispatcher"));
    }
    return out;
  }

  /** The day's reports on a depot's trips, for the warning signs on the timeline. The dispatcher's read. */
  public List<ReportMarkView> reports(Actor actor, String depotCode, LocalDate serviceDate) {
    String resource = "wpt:message:depot:" + depotCode;
    return read(actor, () -> {
          if (!threads.oversees(depotCode)) {
            return Optional.<List<ReportMarkView>>empty();
          }
          List<ReportMarkView> marks = new ArrayList<>();
          for (Map<String, Object> r : threads.reports(depotCode, serviceDate)) {
            marks.add(
                new ReportMarkView(
                    (UUID) r.get("thread_id"),
                    (String) r.get("subject_id"),
                    Optional.ofNullable((String) r.get("vehicle_id")),
                    (UUID) r.get("message_id"),
                    ((Timestamp) r.get("created_at")).toInstant(),
                    (String) r.get("report_type"),
                    (String) r.get("author_role"),
                    r.get("voice_note_id") != null
                        ? MessagePolicy.voiceExcerpt((String) r.get("body"), true)
                        : MessagePolicy.excerpt((String) r.get("body")),
                    r.get("voice_note_id") != null));
          }
          return Optional.of(marks);
        })
        .orElseThrow(() -> denied(actor, resource));
  }

  // ---- internals -------------------------------------------------------------

  /** Empty when the thread is not visible to the reader: the caller turns that into an audited 403. */
  private Optional<ThreadView> view(Optional<Thread> thread, UUID threadId) {
    if (thread.isEmpty()) {
      return Optional.empty();
    }
    Thread t = thread.get();
    Optional<Belonging> b = threads.belonging(threadId);
    if (b.isEmpty()) {
      return Optional.empty();
    }
    Optional<Role> role =
        new MessagePolicy.Membership(b.get().dispatcher(), b.get().loader(), b.get().driver(), b.get().outlets()).role();
    if (role.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new ThreadView(
            t.threadId(), t.subjectType(), t.subjectId(), t.depotCode(), t.vehicleId(), t.serviceDate(),
            t.outletIds(), role.get().code(), b.get().outlets(),
            MessagePolicy.open(t.serviceDate().orElse(today()), today())));
  }

  /** A report made from an event names the role; a person's message names the person. */
  private String name(Message m, Map<UUID, String> cache) {
    if (Actor.SYSTEM_ID.equals(m.authorUserId())) {
      return switch (m.authorRole()) {
        case "loader" -> "Loader";
        case "driver" -> "Driver";
        case "store_manager" -> "Store manager";
        default -> "Dispatcher";
      };
    }
    return cache.computeIfAbsent(
        m.authorUserId(), id -> people.person(id).map(PersonQuery.PersonView::displayName).orElse("Someone"));
  }

  private LocalDate today() {
    return clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate();
  }

  private <T> Optional<T> read(Actor actor, Supplier<Optional<T>> work) {
    return database.readAs(ModuleRole.MESSAGING, actor.userId(), work);
  }

  private DomainException denied(Actor actor, String resource) {
    String reason = "outside the actor's scope";
    audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), READ, resource, reason));
    return new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason, List.of("R-MSG-01"));
  }
}
