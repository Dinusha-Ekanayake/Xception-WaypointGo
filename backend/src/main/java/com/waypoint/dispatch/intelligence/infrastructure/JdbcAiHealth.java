package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.intelligence.contract.AiHealthViews.CallDayView;
import com.waypoint.dispatch.intelligence.contract.AiHealthViews.FailureView;
import com.waypoint.dispatch.intelligence.contract.AiHealthViews.JevVersionView;
import com.waypoint.dispatch.intelligence.contract.AiHealthViews.JevView;
import com.waypoint.dispatch.intelligence.contract.AiHealthViews.SuggestionUseView;
import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The AI health screen's reads (R-ML-27), over ml.ai_calls, ml.jev_shadow and ml.decision_suggestions. As the system. */
@Repository
public class JdbcAiHealth {
  static final int FAILURES = 25;

  private final Database database;

  public JdbcAiHealth(Database database) {
    this.database = database;
  }

  /** Per use and depot day (Asia/Colombo), since a time. */
  public List<CallDayView> callDays(Instant since) {
    return database.query(
            "SELECT purpose, (asked_at AT TIME ZONE 'Asia/Colombo')::date AS day,"
                + " count(*) FILTER (WHERE outcome = 'used') AS used,"
                + " count(*) FILTER (WHERE outcome = 'unfaithful') AS unfaithful,"
                + " count(*) FILTER (WHERE outcome = 'failed') AS failed,"
                + " count(*) FILTER (WHERE outcome = 'capped') AS capped,"
                + " coalesce(percentile_disc(0.5) WITHIN GROUP (ORDER BY millis) FILTER (WHERE outcome <> 'capped'), 0) AS p50,"
                + " coalesce(percentile_disc(0.95) WITHIN GROUP (ORDER BY millis) FILTER (WHERE outcome <> 'capped'), 0) AS p95"
                + " FROM ml.ai_calls WHERE asked_at >= ? GROUP BY 1, 2 ORDER BY 2 DESC, 1",
            Timestamp.from(since))
        .stream()
        .map(r -> new CallDayView((String) r.get("purpose"), ((java.sql.Date) r.get("day")).toLocalDate(),
            n(r, "used"), n(r, "unfaithful"), n(r, "failed"), n(r, "capped"), (int) n(r, "p50"), (int) n(r, "p95")))
        .toList();
  }

  /** The newest calls that were not used. */
  public List<FailureView> failures(Instant since) {
    return database.query(
            "SELECT asked_at, purpose, outcome, model, prompt_version, millis, sent_facts::text AS sent FROM ml.ai_calls"
                + " WHERE asked_at >= ? AND outcome <> 'used' ORDER BY asked_at DESC LIMIT " + FAILURES,
            Timestamp.from(since))
        .stream()
        .map(r -> new FailureView(((Timestamp) r.get("asked_at")).toInstant(), (String) r.get("purpose"),
            (String) r.get("outcome"), (String) r.get("model"), Optional.ofNullable((String) r.get("prompt_version")),
            (int) n(r, "millis"), (String) r.get("sent")))
        .toList();
  }

  public JevView jev(Instant since) {
    Map<String, Object> all = database.queryOne(
        "SELECT count(*) FILTER (WHERE status = 'pending') AS pending,"
            + " count(*) FILTER (WHERE status = 'answered') AS answered,"
            + " count(*) FILTER (WHERE agrees) AS agreed, count(*) FILTER (WHERE status = 'failed') AS failed"
            + " FROM ml.jev_shadow WHERE created_at >= ?",
        Timestamp.from(since));
    List<JevVersionView> byVersion = database.query(
            "SELECT prompt_version, model, source, count(*) AS answered, count(*) FILTER (WHERE agrees) AS agreed"
                + " FROM ml.jev_shadow WHERE status = 'answered' AND created_at >= ?"
                + " GROUP BY 1, 2, 3 ORDER BY max(answered_at) DESC",
            Timestamp.from(since))
        .stream()
        .map(r -> new JevVersionView(Optional.ofNullable((String) r.get("prompt_version")), (String) r.get("model"),
            n(r, "answered"), n(r, "agreed"), (String) r.get("source")))
        .toList();
    return new JevView(n(all, "pending"), n(all, "answered"), n(all, "agreed"), n(all, "failed"), byVersion);
  }

  public List<SuggestionUseView> suggestions(Instant since) {
    return database.query(
            "SELECT situation, count(*) AS decided, count(chosen) AS chosen,"
                + " count(*) FILTER (WHERE chosen = shown[1]) AS first"
                + " FROM ml.decision_suggestions WHERE decided_at >= ? GROUP BY 1 ORDER BY 2 DESC",
            Timestamp.from(since))
        .stream()
        .map(r -> new SuggestionUseView((String) r.get("situation"), n(r, "decided"), n(r, "chosen"), n(r, "first")))
        .toList();
  }

  private static long n(Map<String, Object> row, String key) {
    Object v = row.get(key);
    return v == null ? 0 : ((Number) v).longValue();
  }
}
