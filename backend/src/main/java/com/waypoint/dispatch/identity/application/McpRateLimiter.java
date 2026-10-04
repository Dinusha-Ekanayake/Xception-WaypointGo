package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.domain.RateWindow;
import com.waypoint.dispatch.identity.infrastructure.SessionTokens;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.config.McpProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.util.Clock;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Rate limits for MCP requests (R-IAM-33, issue #139), per credential and per
 * OAuth client, counted in {@code iam.mcp_rate_windows} so every replica shares
 * them. A refusal is {@code 429} with {@code Retry-After}; the first refusal in
 * a window is audited, the rest only counted.
 *
 * <p>Every MCP request counts, including the context refresh the adapter makes
 * before each tool call, so one tool call is about two requests.
 */
@Component
public class McpRateLimiter {
  private final Database database;
  private final SessionTokens tokens;
  private final McpProperties properties;
  private final AuditLog audit;
  private final Metrics metrics;
  private final Clock clock;

  public McpRateLimiter(Database database, SessionTokens tokens, McpProperties properties, AuditLog audit,
      Metrics metrics, Clock clock) {
    this.database = database;
    this.tokens = tokens;
    this.properties = properties;
    this.audit = audit;
    this.metrics = metrics;
    this.clock = clock.realTime();
  }

  /** Counts this request against its credential and, for a remote connection, its client. */
  public void require(String token, Actor actor) {
    var now = clock.now();
    RateWindow window = RateWindow.containing(now, RateWindow.MINUTE);
    String hash = tokens.hash(token);
    check("credential", "credential:" + hash, properties.ratePerCredentialPerMinute(), window, now, actor);
    UUID client = database.asSystem(ModuleRole.IAM, () -> {
      var row = database.queryOne("SELECT oauth_client_id FROM iam.sessions WHERE token_hash = ?", hash);
      return row == null ? null : (UUID) row.get("oauth_client_id");
    });
    if (client != null) {
      check("client", "client:" + client, properties.ratePerClientPerMinute(), window, now, actor);
    }
  }

  /**
   * Writes have a budget of their own, per credential per hour (P-32, R-IAM-35),
   * on top of the request limit: a looping assistant can read a lot, but it can
   * change little before a person notices.
   */
  public void requireWrite(String token, Actor actor) {
    var now = clock.now();
    check("write", "write:" + tokens.hash(token), properties.writesPerCredentialPerHour(),
        RateWindow.containing(now, RateWindow.HOUR), now, actor);
  }

  private void check(String kind, String bucket, int limit, RateWindow window, java.time.Instant now, Actor actor) {
    int calls = database.asSystem(ModuleRole.IAM, () -> ((Number) database.queryOne(
        """
        INSERT INTO iam.mcp_rate_windows (bucket, window_start) VALUES (?, ?)
        ON CONFLICT (bucket, window_start) DO UPDATE SET calls = iam.mcp_rate_windows.calls + 1
        RETURNING calls
        """,
        bucket, Timestamp.from(window.start())).get("calls")).intValue());
    if (!RateWindow.over(calls, limit)) {
      return;
    }
    metrics.increment("waypoint.mcp.rate_limited", "bucket", kind);
    if (RateWindow.firstRefusal(calls, limit)) {
      audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), McpAccessHandler.CONNECT,
          McpAccessHandler.RESOURCE, "MCP rate limit: " + limit + ("write".equals(kind) ? " writes an hour per credential" : " requests a minute per " + kind)));
    }
    throw DomainException.rateLimited(
        "Too many MCP requests for this " + kind + "; try again in a moment", window.secondsUntilNext(now));
  }

  /** Windows older than this are no longer read; the retention job removes them. */
  public static final java.time.Duration KEEP = java.time.Duration.ofHours(2);
}
