package com.waypoint.dispatch.messaging.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Spoken messages (R-MSG-10). Written as the system; read through the message's own row-level security. */
@Repository
public class JdbcSpeech {
  public record Pending(UUID messageId, String textSha256, String lang, int attempts) {}

  private final Database database;

  public JdbcSpeech(Database database) {
    this.database = database;
  }

  /** Once per message: a redelivered event queues nothing new. */
  public void queue(UUID messageId, String textSha256, String lang, Instant at) {
    database.update(
        "INSERT INTO messaging.message_speech (message_id, text_sha256, lang, created_at) VALUES (?, ?, ?, ?)"
            + " ON CONFLICT (message_id) DO NOTHING",
        messageId, textSha256, lang, Timestamp.from(at));
  }

  public List<Pending> pending(int limit) {
    return database.query(
            "SELECT message_id, text_sha256, lang, attempts FROM messaging.message_speech WHERE status = 'pending'"
                + " ORDER BY created_at LIMIT ?", limit)
        .stream()
        .map(r -> new Pending((UUID) r.get("message_id"), (String) r.get("text_sha256"), (String) r.get("lang"),
            ((Number) r.get("attempts")).intValue()))
        .toList();
  }

  public boolean rendered(String textSha256, String lang) {
    return database.queryOne(
        "SELECT 1 AS one FROM messaging.speech_renders WHERE text_sha256 = ? AND lang = ?", textSha256, lang) != null;
  }

  public void storeRender(String textSha256, String lang, byte[] audio, String contentType, Instant at) {
    database.update(
        "INSERT INTO messaging.speech_renders (text_sha256, lang, audio, content_type, created_at) VALUES (?, ?, ?, ?, ?)"
            + " ON CONFLICT (text_sha256, lang) DO NOTHING",
        textSha256, lang, audio, contentType, Timestamp.from(at));
  }

  /** Every message waiting for this text and language is ready at once. */
  public int ready(String textSha256, String lang, Instant at) {
    return database.update(
        "UPDATE messaging.message_speech SET status = 'ready', ready_at = ? WHERE text_sha256 = ? AND lang = ?"
            + " AND status = 'pending'",
        Timestamp.from(at), textSha256, lang);
  }

  public void missed(UUID messageId, boolean giveUp) {
    database.update(
        "UPDATE messaging.message_speech SET attempts = attempts + 1,"
            + " status = CASE WHEN ? THEN 'failed' ELSE status END WHERE message_id = ? AND status = 'pending'",
        giveUp, messageId);
  }

  /** The message's audio, when ready and the reader may read the message; empty otherwise. */
  public Optional<Map<String, Object>> audio(UUID threadId, UUID messageId) {
    return Optional.ofNullable(database.queryOne(
        "SELECT r.audio, r.content_type FROM messaging.message_speech s"
            + " JOIN messaging.messages m ON m.message_id = s.message_id"
            + " JOIN messaging.speech_renders r ON r.text_sha256 = s.text_sha256 AND r.lang = s.lang"
            + " WHERE s.message_id = ? AND m.thread_id = ? AND s.status = 'ready'",
        messageId, threadId));
  }
}
