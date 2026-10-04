package com.waypoint.dispatch.messaging.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Threads and messages (issue #136). Every read runs under the caller's
 * row-level security, so a thread or a message the actor may not see is simply
 * absent; these statements never widen it (R-MSG-01).
 */
@Repository
public class JdbcThreadRepository {
  private final Database database;

  public JdbcThreadRepository(Database database) {
    this.database = database;
  }

  /** A thread as stored. */
  public record Thread(
      UUID threadId,
      String subjectType,
      String subjectId,
      String depotCode,
      Optional<String> vehicleId,
      Optional<LocalDate> serviceDate,
      List<String> outletIds) {}

  /** How the current actor belongs to a thread, decided by the scope predicates. */
  public record Belonging(boolean dispatcher, boolean loader, boolean driver, List<String> outlets) {}

  /** A message as stored. */
  public record Message(
      UUID messageId,
      UUID threadId,
      UUID authorUserId,
      String authorRole,
      String kind,
      Optional<String> reportType,
      String audience,
      Optional<String> audienceOutlet,
      String body,
      Optional<UUID> voiceNoteId,
      Optional<Integer> voiceDurationMs,
      Instant createdAt,
      List<Integer> voicePeaks,
      Optional<Instant> resolvedAt,
      Optional<UUID> resolvedBy,
      Optional<String> resolutionNote) {}

  /** A stored voice note, without its bytes. */
  public record VoiceNote(
      UUID voiceNoteId,
      UUID threadId,
      UUID uploadedBy,
      String contentType,
      int sizeBytes,
      Optional<Integer> durationMs,
      List<Integer> peaks) {}

  // ---- threads ------------------------------------------------------------------

  /**
   * Opens a trip's thread, or widens it when a revision added stops to the same
   * trip. Never narrows: nobody loses what they were already told.
   *
   * @return true when the thread was opened or widened
   */
  public boolean openTrip(
      UUID threadId, String tripId, String depotCode, String vehicleId, LocalDate serviceDate, List<String> outlets,
      Instant now) {
    return database.update(
            """
            INSERT INTO messaging.threads
                (thread_id, subject_type, subject_id, depot_code, vehicle_id, service_date, outlet_ids, opened_at, updated_at)
            VALUES (?, 'trip', ?, ?, ?, ?, ?::text[], ?, ?)
            ON CONFLICT (subject_type, subject_id) DO UPDATE
               SET outlet_ids = ARRAY(SELECT DISTINCT o FROM unnest(messaging.threads.outlet_ids || EXCLUDED.outlet_ids) o ORDER BY o),
                   updated_at = EXCLUDED.updated_at
             WHERE NOT (EXCLUDED.outlet_ids <@ messaging.threads.outlet_ids)
            """,
            threadId, tripId, depotCode, vehicleId, serviceDate, outlets.toArray(String[]::new),
            Timestamp.from(now), Timestamp.from(now))
        > 0;
  }

  public Optional<Thread> thread(UUID threadId) {
    return Optional.ofNullable(database.queryOne("SELECT * FROM messaging.threads WHERE thread_id = ?", threadId))
        .map(JdbcThreadRepository::thread);
  }

  public Optional<Thread> bySubject(String subjectType, String subjectId) {
    return Optional.ofNullable(
            database.queryOne(
                "SELECT * FROM messaging.threads WHERE subject_type = ? AND subject_id = ?", subjectType, subjectId))
        .map(JdbcThreadRepository::thread);
  }

  /** The trip a vehicle is on that day: the latest opened, for a report that names only the vehicle. */
  public Optional<Thread> tripOfVehicle(String vehicleId, LocalDate serviceDate) {
    return Optional.ofNullable(
            database.queryOne(
                """
                SELECT * FROM messaging.threads
                 WHERE subject_type = 'trip' AND vehicle_id = ? AND service_date = ?
                 ORDER BY opened_at DESC, thread_id DESC LIMIT 1
                """,
                vehicleId, serviceDate))
        .map(JdbcThreadRepository::thread);
  }

  /** Empty when the thread is not visible to the actor at all. */
  public Optional<Belonging> belonging(UUID threadId) {
    Map<String, Object> row =
        database.queryOne(
            """
            SELECT app.actor_oversees_depot(depot_code) AS dispatcher,
                   (app.actor_has_depot(depot_code) AND app.actor_holds_role(ARRAY['loader'])) AS loader,
                   (vehicle_id IS NOT NULL AND app.actor_drives(vehicle_id, service_date)) AS driver,
                   ARRAY(SELECT o FROM unnest(outlet_ids) o WHERE app.actor_has_outlet(o) ORDER BY o) AS outlets
              FROM messaging.threads
             WHERE thread_id = ?
            """,
            threadId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        new Belonging(
            Boolean.TRUE.equals(row.get("dispatcher")),
            Boolean.TRUE.equals(row.get("loader")),
            Boolean.TRUE.equals(row.get("driver")),
            strings(row.get("outlets"))));
  }

  /** Whether the actor oversees the depot, which is what reading every report of a day needs. */
  public boolean oversees(String depotCode) {
    return Boolean.TRUE.equals(
        database.queryOne("SELECT app.actor_oversees_depot(?) AS ok", depotCode).get("ok"));
  }

  // ---- messages -----------------------------------------------------------------

  /** @return false when this author already sent this client message, or this event already posted */
  public boolean insert(
      UUID messageId, UUID threadId, UUID authorUserId, String authorRole, String kind, Optional<String> reportType,
      String audience, Optional<String> audienceOutlet, String body, Optional<UUID> voiceNoteId,
      Optional<UUID> clientMessageId, Optional<UUID> sourceEventId, Optional<UUID> sourceIssueId,
      Optional<String> aboutOutlet, Optional<UUID> commandId, Instant now) {
    return database.update(
            """
            INSERT INTO messaging.messages
                (message_id, thread_id, author_user_id, author_role, kind, report_type, audience, audience_outlet,
                 body, voice_note_id, client_message_id, source_event_id, source_issue_id, about_outlet, command_id,
                 created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """,
            messageId, threadId, authorUserId, authorRole, kind, reportType.orElse(null), audience,
            audienceOutlet.orElse(null), body, voiceNoteId.orElse(null), clientMessageId.orElse(null),
            sourceEventId.orElse(null), sourceIssueId.orElse(null), aboutOutlet.orElse(null), commandId.orElse(null),
            Timestamp.from(now))
        > 0;
  }

  // ---- voice notes --------------------------------------------------------------

  /** @return false when this id was already uploaded: a retry of the same note */
  public boolean insertVoice(
      UUID voiceNoteId, UUID threadId, UUID uploadedBy, String contentType, byte[] content, Optional<Integer> durationMs,
      String sha256, Instant now, Instant retainUntil, List<Integer> peaks) {
    return database.update(
            """
            INSERT INTO messaging.voice_notes
                (voice_note_id, thread_id, uploaded_by, content_type, size_bytes, duration_ms, sha256, content,
                 uploaded_at, retain_until, peaks)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::smallint[])
            ON CONFLICT (voice_note_id) DO NOTHING
            """,
            voiceNoteId, threadId, uploadedBy, contentType, content.length, durationMs.orElse(null), sha256, content,
            Timestamp.from(now), Timestamp.from(retainUntil), peaks.isEmpty() ? null : peaks.toArray(Integer[]::new))
        > 0;
  }

  /** Visible only to its uploader and to whoever may see the message carrying it. */
  public Optional<VoiceNote> voice(UUID voiceNoteId) {
    return Optional.ofNullable(
            database.queryOne(
                "SELECT voice_note_id, thread_id, uploaded_by, content_type, size_bytes, duration_ms, peaks"
                    + " FROM messaging.voice_notes WHERE voice_note_id = ?",
                voiceNoteId))
        .map(r -> new VoiceNote(
            (UUID) r.get("voice_note_id"), (UUID) r.get("thread_id"), (UUID) r.get("uploaded_by"),
            (String) r.get("content_type"), ((Number) r.get("size_bytes")).intValue(),
            Optional.ofNullable((Number) r.get("duration_ms")).map(Number::intValue), ints(r.get("peaks"))));
  }

  /** Empty once the retention job cleared it (MSG-11). */
  public Optional<byte[]> voiceContent(UUID voiceNoteId) {
    return Optional.ofNullable(
            database.queryOne(
                "SELECT content FROM messaging.voice_notes WHERE voice_note_id = ? AND purged_at IS NULL", voiceNoteId))
        .map(r -> (byte[]) r.get("content"));
  }

  /** Voice notes past their retention, oldest first. */
  public List<UUID> voiceDue(Instant now, int limit) {
    return database
        .query(
            "SELECT voice_note_id FROM messaging.voice_notes WHERE retain_until < ? AND purged_at IS NULL"
                + " ORDER BY retain_until LIMIT ?",
            Timestamp.from(now), limit)
        .stream()
        .map(r -> (UUID) r.get("voice_note_id"))
        .toList();
  }

  /** Clears the audio; the row, its size and its SHA-256 stay. Running twice changes nothing. */
  public boolean purgeVoice(UUID voiceNoteId, Instant now) {
    return database.update(
            "UPDATE messaging.voice_notes SET content = NULL, purged_at = ?"
                + " WHERE voice_note_id = ? AND purged_at IS NULL",
            Timestamp.from(now), voiceNoteId)
        > 0;
  }

  /** Bytes of audio still held, for the gauge. */
  public long voiceBytesHeld() {
    Map<String, Object> row =
        database.queryOne("SELECT coalesce(sum(size_bytes), 0) AS n FROM messaging.voice_notes WHERE purged_at IS NULL");
    return row == null ? 0 : ((Number) row.get("n")).longValue();
  }

  /** The message this author already sent with this client id, for an idempotent resend. */
  public Optional<Message> byClientId(UUID authorUserId, UUID clientMessageId) {
    return Optional.ofNullable(
            database.queryOne(
                "SELECT * FROM messaging.messages WHERE author_user_id = ? AND client_message_id = ?",
                authorUserId, clientMessageId))
        .map(JdbcThreadRepository::message);
  }

  /**
   * Newest first, a page of {@code limit} after the keyset cursor
   * ({@code createdAt}, {@code messageId}) when one is given.
   */
  public List<Message> page(UUID threadId, Optional<Instant> beforeAt, Optional<UUID> beforeId, int limit) {
    List<Map<String, Object>> rows =
        beforeAt.isPresent()
            ? database.query(
                """
                SELECT m.*, v.duration_ms, v.peaks FROM messaging.messages m
                  LEFT JOIN messaging.voice_notes v ON v.voice_note_id = m.voice_note_id
                 WHERE m.thread_id = ? AND (m.created_at, m.message_id) < (?, ?)
                 ORDER BY m.created_at DESC, m.message_id DESC LIMIT ?
                """,
                threadId, Timestamp.from(beforeAt.get()), beforeId.orElseThrow(), limit)
            : database.query(
                """
                SELECT m.*, v.duration_ms, v.peaks FROM messaging.messages m
                  LEFT JOIN messaging.voice_notes v ON v.voice_note_id = m.voice_note_id
                 WHERE m.thread_id = ?
                 ORDER BY m.created_at DESC, m.message_id DESC LIMIT ?
                """,
                threadId, limit);
    return rows.stream().map(JdbcThreadRepository::message).toList();
  }

  /** Every open report on a depot's trips that day, oldest first: the timeline's warning signs (R-MSG-07). */
  public List<Map<String, Object>> reports(String depotCode, LocalDate serviceDate) {
    return database.query(
        """
        SELECT m.message_id, m.thread_id, m.report_type, m.author_role, m.body, m.voice_note_id, m.created_at,
               m.about_outlet, t.subject_id, t.vehicle_id
          FROM messaging.messages m
          JOIN messaging.threads t ON t.thread_id = m.thread_id
         WHERE t.subject_type = 'trip' AND t.depot_code = ? AND t.service_date = ? AND m.kind = 'report'
           AND m.resolved_at IS NULL
         ORDER BY m.created_at, m.message_id
        """,
        depotCode, serviceDate);
  }

  private static Thread thread(Map<String, Object> row) {
    return new Thread(
        (UUID) row.get("thread_id"),
        (String) row.get("subject_type"),
        (String) row.get("subject_id"),
        (String) row.get("depot_code"),
        Optional.ofNullable((String) row.get("vehicle_id")),
        Optional.ofNullable(row.get("service_date")).map(d -> ((java.sql.Date) d).toLocalDate()),
        strings(row.get("outlet_ids")));
  }

  private static Message message(Map<String, Object> row) {
    return new Message(
        (UUID) row.get("message_id"),
        (UUID) row.get("thread_id"),
        (UUID) row.get("author_user_id"),
        (String) row.get("author_role"),
        (String) row.get("kind"),
        Optional.ofNullable((String) row.get("report_type")),
        (String) row.get("audience"),
        Optional.ofNullable((String) row.get("audience_outlet")),
        (String) row.get("body"),
        Optional.ofNullable((UUID) row.get("voice_note_id")),
        Optional.ofNullable((Number) row.get("duration_ms")).map(Number::intValue),
        ((Timestamp) row.get("created_at")).toInstant(),
        ints(row.get("peaks")),
        Optional.ofNullable((Timestamp) row.get("resolved_at")).map(Timestamp::toInstant),
        Optional.ofNullable((UUID) row.get("resolved_by")),
        Optional.ofNullable((String) row.get("resolution_note")));
  }

  // ---- reports ------------------------------------------------------------------

  /** One message, as the reader may see it. */
  public Optional<Message> message(UUID messageId) {
    return Optional.ofNullable(
            database.queryOne(
                "SELECT m.*, v.duration_ms, v.peaks FROM messaging.messages m"
                    + " LEFT JOIN messaging.voice_notes v ON v.voice_note_id = m.voice_note_id WHERE m.message_id = ?",
                messageId))
        .map(JdbcThreadRepository::message);
  }

  /**
   * Resolves an open report. Row-level security lets only a dispatcher who
   * oversees the trip's depot, or the system, do it.
   *
   * @return false when it was already resolved, or the reader may not resolve it
   */
  public boolean resolve(UUID messageId, UUID by, Optional<String> note, Instant now) {
    return database.update(
            "UPDATE messaging.messages SET resolved_at = ?, resolved_by = ?, resolution_note = ?"
                + " WHERE message_id = ? AND kind = 'report' AND resolved_at IS NULL",
            Timestamp.from(now), by, note.orElse(null), messageId)
        > 0;
  }

  /** Resolves every open report made from this issue, when the issue is resolved. */
  public int resolveFromIssue(UUID issueId, UUID by, String note, Instant now) {
    return database.update(
        "UPDATE messaging.messages SET resolved_at = ?, resolved_by = ?, resolution_note = ?"
            + " WHERE source_issue_id = ? AND kind = 'report' AND resolved_at IS NULL",
        Timestamp.from(now), by, note, issueId);
  }

  static List<Integer> ints(Object value) {
    if (value == null) {
      return List.of();
    }
    try {
      Object[] raw = (Object[]) ((Array) value).getArray();
      List<Integer> out = new java.util.ArrayList<>(raw.length);
      for (Object o : raw) {
        out.add(o == null ? 0 : ((Number) o).intValue());
      }
      return List.copyOf(out);
    } catch (SQLException e) {
      throw new IllegalStateException("unreadable number array", e);
    }
  }

  static List<String> strings(Object value) {
    if (value == null) {
      return List.of();
    }
    try {
      return Arrays.asList((String[]) ((Array) value).getArray());
    } catch (SQLException e) {
      throw new IllegalStateException("unreadable text array", e);
    }
  }
}
