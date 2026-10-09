package com.waypoint.dispatch.messaging.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Voice note transcripts (FF-05). Never opens a transaction; row-level security limits reads to the note's readers. */
@Repository
public class JdbcTranscripts {
  private final Database database;

  public JdbcTranscripts(Database database) {
    this.database = database;
  }

  /** A note's transcript as a reader sees it. */
  public record Transcript(UUID voiceNoteId, String status, Optional<String> text, Optional<String> language, String engine) {}

  /** Queues a note once; applying the event twice queues nothing new. */
  public void queue(UUID voiceNoteId, String engine, Instant now) {
    database.update(
        "INSERT INTO messaging.voice_transcripts (voice_note_id, status, engine, queued_at) VALUES (?, 'pending', ?, ?)"
            + " ON CONFLICT (voice_note_id) DO NOTHING",
        voiceNoteId, engine, Timestamp.from(now));
  }

  public record Pending(UUID voiceNoteId, int attempts) {}

  public List<Pending> pending(int limit) {
    return database.query(
            "SELECT voice_note_id, attempts FROM messaging.voice_transcripts WHERE status = 'pending'"
                + " ORDER BY queued_at LIMIT ?",
            limit)
        .stream()
        .map(r -> new Pending((UUID) r.get("voice_note_id"), ((Number) r.get("attempts")).intValue()))
        .toList();
  }

  public void done(UUID voiceNoteId, String text, Optional<String> language, String engine, Instant now) {
    database.update(
        "UPDATE messaging.voice_transcripts SET status = 'done', transcript = ?, language = ?, engine = ?,"
            + " attempts = attempts + 1, finished_at = ? WHERE voice_note_id = ? AND status = 'pending'",
        text, language.orElse(null), engine, Timestamp.from(now), voiceNoteId);
  }

  /** Another try later, or failed for good after the last one. */
  public void missed(UUID voiceNoteId, boolean last, Instant now) {
    database.update(
        "UPDATE messaging.voice_transcripts SET attempts = attempts + 1,"
            + " status = CASE WHEN ? THEN 'failed' ELSE status END,"
            + " finished_at = CASE WHEN ? THEN ? ELSE finished_at END"
            + " WHERE voice_note_id = ? AND status = 'pending'",
        last, last, Timestamp.from(now), voiceNoteId);
  }

  /** Clears the words of notes whose audio was cleared (P-33): the text goes with the recording. */
  public int purgeWithAudio(Instant now) {
    return database.update(
        "UPDATE messaging.voice_transcripts t SET transcript = NULL, purged_at = ?,"
            + " status = CASE WHEN t.status = 'pending' THEN 'failed' ELSE t.status END,"
            + " finished_at = coalesce(t.finished_at, ?)"
            + " FROM messaging.voice_notes v"
            + " WHERE v.voice_note_id = t.voice_note_id AND v.purged_at IS NOT NULL AND t.purged_at IS NULL",
        Timestamp.from(now), Timestamp.from(now));
  }

  /** The transcripts of these notes the reader may read, by note. */
  public Map<UUID, Transcript> of(Collection<UUID> voiceNoteIds) {
    Map<UUID, Transcript> out = new HashMap<>();
    if (voiceNoteIds.isEmpty()) return out;
    database.query(
            "SELECT voice_note_id, status, transcript, language, engine FROM messaging.voice_transcripts"
                + " WHERE voice_note_id = ANY (?::uuid[])",
            (Object) voiceNoteIds.toArray(UUID[]::new))
        .forEach(r -> out.put(
            (UUID) r.get("voice_note_id"),
            new Transcript(
                (UUID) r.get("voice_note_id"), (String) r.get("status"),
                Optional.ofNullable((String) r.get("transcript")), Optional.ofNullable((String) r.get("language")),
                (String) r.get("engine"))));
    return out;
  }
}
