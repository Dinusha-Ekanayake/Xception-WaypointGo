package com.waypoint.dispatch.platform.db;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only way a module reaches PostgreSQL.
 *
 * <p>Every transaction opens with two identities:
 *
 * <ul>
 *   <li>{@code SET LOCAL ROLE waypoint_&lt;module&gt;} decides which tables the code may touch
 *   <li>{@code SET LOCAL app.actor_id} drives the row-level security policies
 * </ul>
 *
 * <p>Both use SET LOCAL, never plain SET: a pooled connection would otherwise carry
 * one request's identity into the next borrower.
 */
@Component
public class Database {
  private static final int MAX_ATTEMPTS = 5;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate serializable;

  public Database(JdbcTemplate jdbc, TransactionTemplate serializableTransactions) {
    this.jdbc = jdbc;
    this.serializable = serializableTransactions;
  }

  /** Runs one command as a module, on behalf of an actor, in one serializable transaction. */
  public <T> T asModule(ModuleRole role, UUID actorId, Supplier<T> work) {
    for (int attempt = 0; ; attempt++) {
      try {
        return serializable.execute(
            status -> {
              jdbc.execute("SET LOCAL ROLE " + role.roleName());
              if (actorId != null) {
                jdbc.update("SELECT set_config('app.actor_id', ?, true)", actorId.toString());
              }
              return work.get();
            });
      } catch (RuntimeException e) {
        if (attempt < MAX_ATTEMPTS - 1 && isRetryable(e)) {
          sleep(20L * (attempt + 1));
          continue;
        }
        throw e;
      }
    }
  }

  public void asModule(ModuleRole role, UUID actorId, Runnable work) {
    asModule(
        role,
        actorId,
        () -> {
          work.run();
          return null;
        });
  }

  /** Unscoped access, for migrations and startup checks only. Never on a request path. */
  public List<Map<String, Object>> unscopedQuery(String sql, Object... params) {
    return jdbc.queryForList(sql, params);
  }

  public List<Map<String, Object>> query(String sql, Object... params) {
    return jdbc.queryForList(sql, params);
  }

  public Map<String, Object> queryOne(String sql, Object... params) {
    List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
    if (rows.isEmpty()) {
      return null;
    }
    return rows.get(0);
  }

  /** Applies a write and enforces the optimistic concurrency guard. */
  public void updateExpectingOneRow(String sql, Object... params) {
    int affected = jdbc.update(sql, params);
    if (affected == 0) {
      throw new DomainException(
          ErrorCode.VERSION_CONFLICT, "Row version did not match; the record changed");
    }
  }

  public int update(String sql, Object... params) {
    return jdbc.update(sql, params);
  }

  private static boolean isRetryable(RuntimeException e) {
    Throwable t = e;
    while (t != null) {
      if (t instanceof java.sql.SQLException sql) {
        String state = sql.getSQLState();
        if ("40001".equals(state) || "40P01".equals(state)) {
          return true;
        }
      }
      t = t.getCause();
    }
    return false;
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
