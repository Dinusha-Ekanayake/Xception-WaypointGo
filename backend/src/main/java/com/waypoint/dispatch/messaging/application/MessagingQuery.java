package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.identity.contract.PersonQuery;
import com.waypoint.dispatch.messaging.contract.MessagingCommands;
import com.waypoint.dispatch.messaging.contract.MessagingViews.MemberView;
import com.waypoint.dispatch.messaging.contract.MessagingViews.MessagePage;
import com.waypoint.dispatch.messaging.contract.MessagingViews.MessageView;
import com.waypoint.dispatch.messaging.contract.MessagingViews.TranscriptView;
import com.waypoint.dispatch.messaging.contract.MessagingViews.ReportMarkView;
import com.waypoint.dispatch.messaging.contract.MessagingViews.ThreadView;
import com.waypoint.dispatch.messaging.domain.MessagePolicy;
import com.waypoint.dispatch.messaging.domain.MessagePolicy.Role;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository;
import com.waypoint.dispatch.messaging.infrastructure.JdbcTranscripts;
import com.waypoint.dispatch.notification.contract.NotificationQuery;
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
  private final NotificationQuery notifications;
  private final JdbcTranscripts transcripts;

  public MessagingQuery(
      Database database, JdbcThreadRepository threads, PersonQuery people, IdentityQuery identity, AuditLog audit,
      Clock clock, NotificationQuery notifications, JdbcTranscripts transcripts) {
    this.transcripts = transcripts;
    this.notifications = notifications;
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

  /**
   * R-MSG-14: for the dispatcher, when the driver read each message for the
   * driver or for everyone on this page. Only messages the dispatcher has just
   * read under row-level security are asked about.
   */
  private Map<UUID, Optional<Instant>> driverReads(Actor actor, UUID threadId, List<Message> page) {
    List<UUID> toDriver =
        page.stream()
            .filter(m -> "driver".equals(m.audience()) || "all".equals(m.audience()))
            .map(Message::messageId)
            .toList();
    if (toDriver.isEmpty() || !"dispatcher".equals(thread(actor, threadId).memberRole())) {
      return Map.of();
    }
    return notifications.driverReads(toDriver);
  }

  /** FF-05: the reader's view of a note's transcript; anything but words or work in progress is "none". */
  private static TranscriptView transcript(JdbcTranscripts.Transcript t) {
    if (t == null) return new TranscriptView("none", Optional.empty(), Optional.empty(), true);
    if ("pending".equals(t.status())) return new TranscriptView("pending", Optional.empty(), Optional.empty(), true);
    if ("done".equals(t.status()) && t.text().isPresent()) {
      return new TranscriptView("done", t.text(), t.language(), !t.audioGone(), t.unclear());
    }
    return new TranscriptView("none", Optional.empty(), Optional.empty(), !t.audioGone());
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
    Map<UUID, Optional<Instant>> seen = driverReads(actor, threadId, page.items());
    List<UUID> voices = page.items().stream().flatMap(m -> m.voiceNoteId().stream()).toList();
    Map<UUID, JdbcTranscripts.Transcript> heard =
        voices.isEmpty() ? Map.of() : database.readAs(ModuleRole.MESSAGING, actor.userId(), () -> transcripts.of(voices));
    List<MessageView> items = new ArrayList<>();
    for (Message m : page.items()) {
      Optional<Optional<Instant>> read = Optional.ofNullable(seen.get(m.messageId()));
      items.add(
          new MessageView(
              m.messageId(), m.threadId(), name(m, names), m.authorRole(), m.kind(), m.reportType(), m.audience(),
              m.audienceOutlet(), m.body(), m.voiceNoteId(), m.voiceDurationMs(), m.createdAt(),
              m.authorUserId().equals(actor.userId()), m.voicePeaks(), m.resolvedAt(),
              m.resolvedBy().map(by -> resolver(by, names)), m.resolutionNote(), m.redirectedAt(),
              m.redirectReason(), read.map(Optional::isPresent), read.flatMap(r -> r),
              m.voiceNoteId().map(v -> transcript(heard.get(v))), m.sourceIssueId(),
              m.kind().equals("report") ? m.aboutOutlet() : Optional.empty()));
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
        // R-MSG-08: the stores still waiting on the trip, together.
        if (t.channelOpen()) {
          out.add(new MemberView("channel", Optional.empty(), "Stores still waiting"));
        }
      }
      case "driver" -> {
        out.add(new MemberView("dispatch", Optional.empty(), "Dispatcher"));
        t.outletIds().forEach(o -> out.add(new MemberView("outlet", Optional.of(o), o)));
      }
      case "store_manager" -> {
        out.add(new MemberView("dispatch", Optional.empty(), "Dispatcher"));
        boolean waiting = read(actor, () -> Optional.of(threads.inChannel(threadId))).orElse(false);
        // R-MSG-16: its own driver, while the store still has a delivery on this trip; it arrives on the driver's Home.
        if (waiting && t.vehicleId().isPresent()) {
          out.add(new MemberView("driver", Optional.empty(), "Your driver"));
        }
        if (t.channelOpen() && waiting) {
          out.add(new MemberView("channel", Optional.empty(), "Dispatcher and stores still waiting"));
        }
      }
      case "loader" -> {
        out.add(new MemberView("dispatch", Optional.empty(), "Dispatcher"));
        // R-MSG-12: a note about the load for the driver who will carry it.
        if (t.vehicleId().isPresent()) {
          out.add(new MemberView("driver", Optional.empty(), "Driver"));
        }
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
                    r.get("voice_note_id") != null,
                    Optional.ofNullable((String) r.get("about_outlet"))));
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
            MessagePolicy.open(t.serviceDate().orElse(today()), today()), t.channelOpen()));
  }

  /** A report made from an event names the role; a person's message names the person. */
  /** Who resolved a report: a person, or the system when the issue behind it was resolved. */
  private String resolver(UUID by, Map<UUID, String> cache) {
    if (Actor.SYSTEM_ID.equals(by)) {
      return "Issue resolved";
    }
    return cache.computeIfAbsent(by, id -> people.person(id).map(PersonQuery.PersonView::displayName).orElse("Dispatcher"));
  }

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

  /**
   * FF-05: one voice message's transcript, for a screen that shows the message
   * outside its thread (the driver's Home). Read as the reader: a message they
   * cannot see is not found, and a thread outside their scope is refused.
   */
  public TranscriptView transcriptOf(Actor actor, UUID threadId, UUID messageId) {
    thread(actor, threadId);
    UUID voiceNoteId =
        read(actor, () -> threads.message(messageId).filter(m -> m.threadId().equals(threadId)))
            .flatMap(Message::voiceNoteId)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No voice message " + messageId + " here"));
    Map<UUID, JdbcTranscripts.Transcript> heard =
        database.readAs(ModuleRole.MESSAGING, actor.userId(), () -> transcripts.of(List.of(voiceNoteId)));
    return transcript(heard.get(voiceNoteId));
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
