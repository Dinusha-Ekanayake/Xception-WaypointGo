package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@code ml.ai_cache} (R-ML-47): an answer by its key, while it has not expired.
 * Called as the system only. Two writers of the same key keep one row: the later
 * answer replaces an expired one and leaves a live one as it is.
 */
@Repository
public class JdbcAiCache {
  private final Database database;

  public JdbcAiCache(Database database) {
    this.database = database;
  }

  /** The kept answer, counted as a hit; empty when there is none or it has expired. */
  public Optional<String> hit(String cacheKey, Instant now) {
    Map<String, Object> row = database.queryOne(
        "UPDATE ml.ai_cache SET hits = hits + 1, last_hit_at = ? WHERE cache_key = ? AND expires_at > ? RETURNING answer::text AS answer",
        Timestamp.from(now), cacheKey, Timestamp.from(now));
    return row == null ? Optional.empty() : Optional.ofNullable((String) row.get("answer"));
  }

  public void keep(String cacheKey, String purpose, String promptVersion, String model, String depotCode, String answerJson,
      Instant now, Duration lasts) {
    database.update(
        "INSERT INTO ml.ai_cache (cache_key, purpose, prompt_version, model, depot_code, answer, created_at, expires_at)"
            + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)"
            + " ON CONFLICT (cache_key) DO UPDATE SET answer = EXCLUDED.answer, created_at = EXCLUDED.created_at,"
            + " expires_at = EXCLUDED.expires_at, hits = 0, last_hit_at = NULL WHERE ml.ai_cache.expires_at <= EXCLUDED.created_at",
        cacheKey, purpose, promptVersion, model, depotCode, answerJson, Timestamp.from(now), Timestamp.from(now.plus(lasts)));
  }
}
