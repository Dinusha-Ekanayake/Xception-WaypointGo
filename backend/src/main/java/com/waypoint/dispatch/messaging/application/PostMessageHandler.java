package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.identity.contract.PersonQuery;
import com.waypoint.dispatch.messaging.contract.MessagingCommands;
import com.waypoint.dispatch.messaging.contract.MessagingEvents.MessagePosted;
import com.waypoint.dispatch.messaging.domain.MessagePolicy;
import com.waypoint.dispatch.messaging.domain.MessagePolicy.Post;
import com.waypoint.dispatch.messaging.domain.MessagePolicy.Role;
import com.waypoint.dispatch.messaging.domain.MessagePolicy.To;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository.Thread;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code message:Post} (issue #136). The thread is read under the actor's
 * row-level security: a thread they do not belong to is a 403 plus an audit row,
 * never "not found" (security rules). How they belong decides what they may
 * write (R-MSG-02, R-MSG-03); the message and its {@code message.posted} commit
 * together, and Notification tells only the people it is for (R-NOT-14).
 */
@Component
public class PostMessageHandler implements CommandHandler {
  private final JdbcThreadRepository threads;
  private final PersonQuery people;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  PostMessageHandler(
      JdbcThreadRepository threads, PersonQuery people, EventPublisher events, Metrics metrics, Clock clock) {
    this.threads = threads;
    this.people = people;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return MessagingCommands.POST;
  }

  @Override
  public String action() {
    return MessagingCommands.POST;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.MESSAGING;
  }

  @Override
  public String resource(Command command) {
    String thread = CommandPayload.of(command).text("threadId");
    return thread == null ? null : "wpt:message:thread:" + thread;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    UUID threadId = payload.uuid("threadId");
    Optional<UUID> clientId = Optional.ofNullable(payload.optionalUuid("clientMessageId"));

    // A resend from a phone's queue under a new command id is the same message.
    if (clientId.isPresent()) {
      Optional<JdbcThreadRepository.Message> sent = threads.byClientId(actor.userId(), clientId.get());
      if (sent.isPresent()) {
        return result(sent.get().messageId(), threadId, true);
      }
    }

    Thread thread = threads.thread(threadId).orElseThrow(() -> outside(threadId));
    JdbcThreadRepository.Belonging b = threads.belonging(threadId).orElseThrow(() -> outside(threadId));
    Role role =
        new MessagePolicy.Membership(b.dispatcher(), b.loader(), b.driver(), b.outlets())
            .role()
            .orElseThrow(() -> outside(threadId));

    Optional<UUID> voiceNoteId = Optional.ofNullable(payload.optionalUuid("voiceNoteId"));
    Optional<JdbcThreadRepository.VoiceNote> note = voiceNoteId.map(id -> {
      // The note must be this author's, on this thread: nobody posts another's recording.
      JdbcThreadRepository.VoiceNote found =
          threads.voice(id).orElseThrow(() -> new DomainException(
              ErrorCode.VALIDATION_FAILED, "voice note " + id + " has not been uploaded", List.of("R-MSG-06")));
      if (!found.uploadedBy().equals(actor.userId()) || !found.threadId().equals(threadId)) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED, "voice note " + id + " was not recorded by you on this thread",
            List.of("R-MSG-06"));
      }
      return found;
    });
    Post post =
        new Post(
            payload.text("body") == null ? "" : payload.text("body"),
            To.parse(payload.requiredText("to")),
            Optional.ofNullable(payload.text("outletId")),
            Optional.ofNullable(payload.text("report")),
            voiceNoteId.isPresent());
    Instant now = clock.now();
    LocalDate today = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
    String body =
        MessagePolicy.check(
            role, post, thread.outletIds(), thread.vehicleId().isPresent(),
            thread.serviceDate().orElse(today), today);

    UUID messageId = UuidV7.generate(now, random);
    String kind = post.reportType().isPresent() ? "report" : "message";
    threads.insert(
        messageId, threadId, actor.userId(), role.code(), kind, post.reportType(), post.to().code(), post.outletId(),
        body, voiceNoteId, clientId, Optional.empty(), Optional.empty(), aboutOutlet(role, kind, post, b),
        Optional.of(command.commandId()), now);

    List<String> reaches =
        switch (post.to()) {
          case OUTLET -> List.of(post.outletId().orElseThrow());
          case ALL -> thread.outletIds();
          default -> List.of();
        };
    String author = people.person(actor.userId()).map(PersonQuery.PersonView::displayName).orElse(role.code());
    events.publish(
        actor,
        new MessagePosted(
            threadId, messageId, thread.subjectType(), thread.subjectId(), thread.depotCode(), thread.vehicleId(),
            thread.serviceDate(), author, role.code(), kind, post.reportType(), post.to().code(), reaches,
            voiceNoteId.isPresent() ? MessagePolicy.voiceExcerpt(body, post.reportType().isPresent())
                : MessagePolicy.excerpt(body), now,
            voiceNoteId, note.flatMap(JdbcThreadRepository.VoiceNote::durationMs),
            note.map(JdbcThreadRepository.VoiceNote::peaks).orElse(List.of())));
    metrics.increment("waypoint.message.posted", "role", role.code(), "to", post.to().code(), "kind", kind);
    return result(messageId, threadId, false);
  }

  /**
   * The stop a report is about: a store's own outlet on this trip, when it has
   * exactly one. A driver's or a loader's report is about the trip as a whole.
   */
  private static Optional<String> aboutOutlet(Role role, String kind, Post post, JdbcThreadRepository.Belonging b) {
    if (!"report".equals(kind) || role != Role.STORE_MANAGER) {
      return Optional.empty();
    }
    return b.outlets().size() == 1 ? Optional.of(b.outlets().get(0)) : post.outletId();
  }

  private static Map<String, Object> result(UUID messageId, UUID threadId, boolean replayed) {
    return Map.of("messageId", messageId.toString(), "threadId", threadId.toString(), "alreadySent", replayed);
  }

  private static DomainException outside(UUID threadId) {
    return new DomainException(
        ErrorCode.FORBIDDEN, "thread " + threadId + " is outside the actor's scope", List.of("R-MSG-01"));
  }
}
