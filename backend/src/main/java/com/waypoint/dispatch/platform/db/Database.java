package com.waypoint.dispatch.platform.db;

import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

  private static final Logger log = LoggerFactory.getLogger(Database.class);

  /** The actor of the unit of work running on this thread, for contract reads made inside it. */
  private static final ThreadLocal<UUID> AMBIENT_ACTOR = new ThreadLocal<>();

  private final JdbcTemplate jdbc;
  private final TransactionTemplate serializable;
  private final TransactionTemplate separateRead;
  private final Metrics metrics;

  public Database(
      JdbcTemplate jdbc, TransactionTemplate serializableTransactions, Metrics metrics) {
    this.jdbc = jdbc;
    this.serializable = serializableTransactions;
    this.separateRead = new TransactionTemplate(serializableTransactions.getTransactionManager());
    separateRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    separateRead.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    separateRead.setReadOnly(true);
    this.metrics = metrics;
  }

  /** Runs one command as a module, on behalf of an actor, in one serializable transaction. */
  public <T> T asModule(ModuleRole role, UUID actorId, Supplier<T> work) {
    String module = role.roleName();
    if (actorId == null) {
      // SEC-06, SEC-13. Legitimate for sign-in and startup, which have no actor yet,
      // but row-level security sees nobody here, so a request path showing up in
      // this count is a scope bug.
      metrics.increment("waypoint.db.no_actor", "module", module);
    }
    for (int attempt = 0; ; attempt++) {
      try {
        return serializable.execute(status -> runAs(role, actorId, work));
      } catch (RuntimeException e) {
        String state = sqlState(e);
        if ("42501".equals(state)) {
          // SEC-16: a grant refused the statement. Either a module reached into a
          // schema it does not own, or a migration forgot a grant.
          metrics.increment("waypoint.db.permission_denied", "module", module);
        }
        boolean retryable = "40001".equals(state) || "40P01".equals(state);
        if (retryable && attempt < MAX_ATTEMPTS - 1) {
          // PLT-01: the retry re-runs the whole unit of work, validation included.
          metrics.increment("waypoint.db.retry", "module", module);
          sleep(20L * (attempt + 1));
          continue;
        }
        if (retryable) {
          metrics.increment("waypoint.db.retry.exhausted", "module", module);
          log.warn("Gave up after {} attempts as {} (SQLSTATE {})", MAX_ATTEMPTS, module, state);
        }
        throw e;
      }
    }
  }

  /**
   * A module's contract query, answered in a read-only transaction of its own.
   *
   * <p>A contract query is called from inside another module's command, whose
   * transaction runs as that module's role. Joining it would switch the caller's
   * role mid-transaction, and the caller's role cannot read this module's tables
   * anyway (D-B). So the read suspends the caller's transaction, borrows another
   * connection, and runs as the owning module for the same actor, which keeps
   * row-level security deciding the rows. With no actor on the thread, it runs
   * as nobody and sees nothing.
   */
  public <T> T readAs(ModuleRole role, UUID actorId, Supplier<T> work) {
    return separateRead.execute(status -> runAs(role, actorId, work));
  }

  /**
   * Runs {@code work} once the transaction this thread is inside has committed,
   * and not at all if it rolls back. With no transaction open it runs now.
   *
   * <p>For effects that live outside the database, such as clearing an in-memory
   * cache: done before the commit, another request can refill the cache from the
   * state that is about to be replaced.
   */
  public void afterCommit(Runnable work) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      work.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            work.run();
          }
        });
  }

  /** The actor of the unit of work this thread is inside, if any. */
  public Optional<UUID> ambientActor() {
    return Optional.ofNullable(AMBIENT_ACTOR.get());
  }

  private <T> T runAs(ModuleRole role, UUID actorId, Supplier<T> work) {
    jdbc.execute("SET LOCAL ROLE " + role.roleName());
    if (actorId != null) {
      // set_config is a SELECT, not a statement: calling it through update()
      // throws "a result was returned when none was expected", which means the
      // actor is never set and row-level security sees nobody. Parameterised
      // rather than interpolated so an actor id can never be injected into SQL.
      jdbc.queryForList("SELECT set_config('app.actor_id', ?, true)", actorId.toString());
    }
    UUID previous = AMBIENT_ACTOR.get();
    AMBIENT_ACTOR.set(actorId);
    try {
      return work.get();
    } finally {
      if (previous == null) {
        AMBIENT_ACTOR.remove();
      } else {
        AMBIENT_ACTOR.set(previous);
      }
    }
  }

  /**
   * Work the process does on its own behalf, such as consuming an event or
   * running a scheduled job. Row-level security sees {@link Actor#SYSTEM_ID},
   * which module policies admit through {@code app.actor_is_system()}, so the
   * work reaches its own module's rows and still nothing outside the role.
   */
  public <T> T asSystem(ModuleRole role, Supplier<T> work) {
    return asModule(role, Actor.SYSTEM_ID, work);
  }

  public void asSystem(ModuleRole role, Runnable work) {
    asModule(role, Actor.SYSTEM_ID, work);
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

  private static String sqlState(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
    }
    return null;
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
