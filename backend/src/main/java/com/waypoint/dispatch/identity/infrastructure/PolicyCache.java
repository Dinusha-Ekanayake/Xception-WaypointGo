package com.waypoint.dispatch.identity.infrastructure;

import com.waypoint.dispatch.identity.domain.policy.Statement;
import com.waypoint.dispatch.platform.observability.Metrics;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p>Every entry is stored under the policy generation it was loaded at, and is
 * served only to a reader who has just read the same generation from the
 * database. That one comparison is what makes the cache safe across replicas,
 * which share the number and nothing else, and what makes a stale reload
 * harmless: statements read from an old snapshot land under the old generation,
 * and the next reader does not accept them.
 */
@Component
public class PolicyCache {
  private static final Logger log = LoggerFactory.getLogger(PolicyCache.class);

  private record Entry(long generation, List<Statement> statements) {}

  private final Map<UUID, Entry> byActor = new ConcurrentHashMap<>();
  private final AtomicLong newestSeen = new AtomicLong(Long.MIN_VALUE);
  private final Metrics metrics;

  public PolicyCache(Metrics metrics) {
    this.metrics = metrics;
    metrics.gauge("waypoint.policy.cache.size", byActor::size);
  }

  /** The cached statements, if they were loaded at exactly this generation. */
  public Optional<List<Statement>> statementsAt(UUID userId, long generation) {
    advanceTo(generation);
    Entry entry = byActor.get(userId);
    return entry != null && entry.generation() == generation
        ? Optional.of(entry.statements())
        : Optional.empty();
  }

  /**
   * @param generation the generation read in the same transaction as the statements, and before
   *     them, so the entry can be older than it claims but never newer
   */
  public List<Statement> store(UUID userId, long generation, List<Statement> statements) {
    List<Statement> copy = List.copyOf(statements);
    byActor.put(userId, new Entry(generation, copy));
    return copy;
  }

  /**
   * A newer generation exists: a policy, a version default, an attachment, a role
   * or a scope changed, here or on another replica. Everything older goes.
   */
  public void advanceTo(long generation) {
    long previous = newestSeen.getAndAccumulate(generation, Math::max);
    if (generation <= previous || previous == Long.MIN_VALUE) {
      return;
    }
    int size = byActor.size();
    byActor.values().removeIf(entry -> entry.generation() < generation);
    // SEC-12: every policy change must show up here. A change with no clear is a
    // revoked permission still being served.
    metrics.increment("waypoint.policy.cache.cleared");
    if (size > 0) {
      log.info("Policy cache cleared: {} actor(s) will be re-evaluated", size);
    }
  }

  public int size() {
    return byActor.size();
  }
}
