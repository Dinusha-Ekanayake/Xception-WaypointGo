package com.waypoint.dispatch.identity.infrastructure;

import com.waypoint.dispatch.identity.domain.policy.Statement;
import com.waypoint.dispatch.platform.observability.Metrics;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Caches the statements in force for each actor.
 *
 * <p>Authorization runs before every command, so reloading and reparsing policy
 * documents each time would put JSON parsing on the hot path.
 *
 * <p>Correctness beats hit rate here: **any** policy change clears the whole
 * cache rather than trying to work out who was affected. Attaching a policy to a
 * role changes the permissions of everyone holding it, and a cache that is
 * clever about invalidation is a cache that eventually serves a revoked
 * permission. Policy changes are rare; commands are not.
 */
@Component
public class PolicyCache {
  private static final Logger log = LoggerFactory.getLogger(PolicyCache.class);

  private final Map<UUID, List<Statement>> byActor = new ConcurrentHashMap<>();
  private final Metrics metrics;

  public PolicyCache(Metrics metrics) {
    this.metrics = metrics;
    metrics.gauge("waypoint.policy.cache.size", byActor::size);
  }

  public List<Statement> statementsFor(UUID userId, Supplier<List<Statement>> loader) {
    return byActor.computeIfAbsent(userId, id -> List.copyOf(loader.get()));
  }

  /** Called whenever a policy, a version default, an attachment or a role changes. */
  public void invalidateAll() {
    int size = byActor.size();
    byActor.clear();
    // SEC-12: every policy change must show up here. A change with no clear is a
    // revoked permission still being served.
    metrics.increment("waypoint.policy.cache.cleared");
    if (size > 0) {
      log.info("Policy cache cleared: {} actor(s) will be re-evaluated", size);
    }
  }

  /** Used when a single account is disabled or its roles change. */
  public void invalidate(UUID userId) {
    byActor.remove(userId);
  }

  public int size() {
    return byActor.size();
  }
}
