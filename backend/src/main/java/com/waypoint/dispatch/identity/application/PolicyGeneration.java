package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.infrastructure.JdbcPolicyRepository;
import com.waypoint.dispatch.identity.infrastructure.PolicyCache;
import com.waypoint.dispatch.platform.db.Database;
import org.springframework.stereotype.Component;

/**
 * Announces that something a decision depends on has changed.
 *
 * <p>Called inside the transaction that makes the change: a policy, a default
 * version, an attachment, a role, a scope, an account disabled. The number moves
 * with the change, so every replica notices on its next decision and a command
 * already in flight notices at its re-check.
 *
 * <p>This process's own cache is cleared after the commit, not before. Cleared
 * before, a concurrent request could refill it from the state about to be
 * replaced, and nothing would clear it again.
 */
@Component
public class PolicyGeneration {
  private final JdbcPolicyRepository policies;
  private final PolicyCache cache;
  private final Database database;

  public PolicyGeneration(JdbcPolicyRepository policies, PolicyCache cache, Database database) {
    this.policies = policies;
    this.cache = cache;
    this.database = database;
  }

  /** Must run inside the transaction that made the change. */
  public void advance() {
    long generation = policies.advanceGeneration();
    database.afterCommit(() -> cache.advanceTo(generation));
  }
}
