package com.waypoint.dispatch.platform.db;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only way a module reaches PostgreSQL. Wraps the serializable transaction
 * template and the bounded retry on serialization failure, deadlock and the
 * command-receipt uniqueness race, so no module carries that logic itself.
 *
 * <p>Behaviour is identical to the helpers this class was extracted from; the
 * retry states (40001, 40P01 and the commands_pkey conflict) are unchanged.
 */
@Component
public class Database {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate serializable;

  public Database(JdbcTemplate jdbc, TransactionTemplate serializableTransactions) {
    this.jdbc = jdbc;
    this.serializable = serializableTransactions;
  }

  public List<Map<String, Object>> all(String sql, Object... params) {
    return jdbc.queryForList(sql, params);
  }

  public Map<String, Object> get(String sql, Object... params) {
    List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public void run(String sql, Object... params) {
    jdbc.update(sql, params);
  }

  public <T> T transaction(Supplier<T> fn) {
    for (int attempt = 0; ; attempt++) {
      try {
        return serializable.execute(status -> fn.get());
      } catch (RuntimeException e) {
        if (attempt < 4 && isRetryable(e)) {
          sleep(20L * (attempt + 1));
          continue;
        }
        throw e;
      }
    }
  }

  public void transaction(Runnable fn) {
    transaction(() -> {
      fn.run();
      return null;
    });
  }

  private static boolean isRetryable(RuntimeException e) {
    Throwable t = e;
    while (t != null) {
      if (t instanceof java.sql.SQLException sql) {
        String state = sql.getSQLState();
        if ("40001".equals(state) || "40P01".equals(state)) return true;
        if ("23505".equals(state)
            && sql.getMessage() != null
            && sql.getMessage().contains("commands_pkey")) return true;
      }
      String msg = t.getMessage();
      if (msg != null && msg.contains("commands_pkey") && msg.contains("duplicate key")) return true;
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
