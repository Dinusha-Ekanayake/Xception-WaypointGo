package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.messaging.contract.MessagingCommands;
import com.waypoint.dispatch.messaging.domain.MessagePolicy;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.config.MessagingProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Voice messages and voice reports (issue #136, R-MSG-06). The audio is
 * uploaded under the phone's own id before the message that carries it is
 * posted, so a retry from an offline queue stores it once. It is served only to
 * whoever may see that message, or to the person who recorded it. The bytes are
 * personal communication: never logged.
 */
@Component
public class VoiceNotes {
  private final Database database;
  private final JdbcThreadRepository threads;
  private final AuditLog audit;
  private final Metrics metrics;
  private final Clock clock;
  private final MessagingProperties properties;

  VoiceNotes(
      Database database, JdbcThreadRepository threads, AuditLog audit, Metrics metrics, Clock clock,
      MessagingProperties properties) {
    this.properties = properties;
    this.database = database;
    this.threads = threads;
    this.audit = audit;
    this.metrics = metrics;
    this.clock = clock;
  }

  public record Stored(UUID voiceNoteId, boolean alreadyStored) {}

  public record Audio(String contentType, byte[] content) {}

  /** Policy has already allowed {@code message:Post} on the thread; this decides scope. */
  public Stored store(
      Actor actor, UUID threadId, UUID voiceNoteId, String contentType, byte[] content, Optional<Integer> durationMs) {
    String type = MessagePolicy.voiceType(contentType, content == null ? 0 : content.length, durationMs);
    String resource = "wpt:message:thread:" + threadId;
    Optional<Stored> stored =
        database.asModule(ModuleRole.MESSAGING, actor.userId(), () -> {
          if (threads.belonging(threadId).isEmpty()) {
            return Optional.<Stored>empty();
          }
          Optional<JdbcThreadRepository.VoiceNote> existing = threads.voice(voiceNoteId);
          if (existing.isPresent()) {
            if (!existing.get().uploadedBy().equals(actor.userId())) {
              throw new DomainException(ErrorCode.CONFLICT, "voice note " + voiceNoteId + " is someone else's");
            }
            return Optional.of(new Stored(voiceNoteId, true));
          }
          threads.insertVoice(
              voiceNoteId, threadId, actor.userId(), type, content, durationMs, sha256(content), clock.now(),
              clock.now().plus(properties.voiceRetention()));
          return Optional.of(new Stored(voiceNoteId, false));
        });
    if (stored.isEmpty()) {
      throw denied(actor, MessagingCommands.POST, resource);
    }
    if (!stored.get().alreadyStored()) {
      metrics.increment("waypoint.message.voice_uploaded", "type", type);
    }
    return stored.get();
  }

  /** Absent, out of scope, and on a message the reader may not see all answer the same 404. */
  public Audio audio(Actor actor, UUID threadId, UUID voiceNoteId) {
    return database
        .readAs(ModuleRole.MESSAGING, actor.userId(), () -> threads.voice(voiceNoteId)
            .filter(v -> v.threadId().equals(threadId))
            .flatMap(v -> threads.voiceContent(voiceNoteId).map(bytes -> new Audio(v.contentType(), bytes))))
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "no voice note " + voiceNoteId));
  }

  private DomainException denied(Actor actor, String action, String resource) {
    String reason = "outside the actor's scope";
    audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), action, resource, reason));
    return new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason, List.of("R-MSG-01"));
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
