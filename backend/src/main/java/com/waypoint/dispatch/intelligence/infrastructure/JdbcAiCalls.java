package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** The record of every language model call (ml.ai_calls). Read and written as the system. */
@Repository
public class JdbcAiCalls {
  private final Database database;

  public JdbcAiCalls(Database database) {
    this.database = database;
  }

  /** An answer already used for this key: served again, never asked twice. */
  public Optional<String> usedAnswer(String cacheKey) {
    Map<String, Object> row = database.queryOne(
        "SELECT answer FROM ml.ai_calls WHERE cache_key = ? AND outcome = 'used' ORDER BY asked_at DESC LIMIT 1", cacheKey);
    return row == null ? Optional.empty() : Optional.ofNullable((String) row.get("answer"));
  }

  /** Calls made since a time, every outcome but the capped ones: what the daily cap counts. */
  public long callsSince(Instant since) {
    return ((Number) database.queryOne(
            "SELECT count(*) AS n FROM ml.ai_calls WHERE asked_at >= ? AND outcome <> 'capped'", Timestamp.from(since))
        .get("n")).longValue();
  }

  public void record(
      UUID callId, String purpose, String cacheKey, String provider, String model, String sentFacts,
      Optional<String> answer, String outcome, UUID askedBy, Instant at, long millis) {
    database.update(
        "INSERT INTO ml.ai_calls (call_id, purpose, cache_key, provider, model, sent_facts, answer, outcome, asked_by,"
            + " asked_at, millis) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)",
        callId, purpose, cacheKey, provider, model, sentFacts, answer.orElse(null), outcome, askedBy, Timestamp.from(at),
        (int) Math.min(Integer.MAX_VALUE, millis));
  }
}
