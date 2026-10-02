package com.waypoint.dispatch.identity.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.identity.domain.policy.Statement;
import com.waypoint.dispatch.platform.observability.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SEC-12. The cache may hold what an old snapshot saw; it must never serve it to
 * a reader who has seen a newer generation.
 */
class PolicyCacheTest {
  private static final UUID ACTOR = UUID.randomUUID();
  private static final List<Statement> OLD = List.of();

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final PolicyCache cache = new PolicyCache(new Metrics(registry));

  @Test
  void statementsAreServedOnlyAtTheGenerationTheyWereLoadedAt() {
    cache.store(ACTOR, 5, OLD);

    assertTrue(cache.statementsAt(ACTOR, 5).isPresent());
    assertTrue(cache.statementsAt(ACTOR, 6).isEmpty(), "generation 6 must reload");
  }

  /**
   * The race the after-commit clear exists for. A request starts loading at
   * generation 5, a policy change commits and the cache is cleared for 6, and only
   * then does the slow load finish and store what it read. It must not come back.
   */
  @Test
  void aLoadThatFinishesAfterTheChangeCommittedIsNotServed() {
    cache.statementsAt(ACTOR, 5);
    cache.advanceTo(6);

    cache.store(ACTOR, 5, OLD);

    assertTrue(
        cache.statementsAt(ACTOR, 6).isEmpty(),
        "statements read before the change must not answer a decision made after it");
  }

  @Test
  void aNewerGenerationClearsEverythingOlderAndIsCounted() {
    cache.statementsAt(ACTOR, 5);
    cache.store(ACTOR, 5, OLD);
    cache.store(UUID.randomUUID(), 5, OLD);

    cache.advanceTo(6);

    assertEquals(0, cache.size());
    assertEquals(1.0, registry.counter("waypoint.policy.cache.cleared").count());
  }

  @Test
  void seeingTheSameGenerationAgainClearsNothing() {
    cache.statementsAt(ACTOR, 5);
    cache.store(ACTOR, 5, OLD);

    cache.advanceTo(5);
    cache.statementsAt(ACTOR, 5);

    assertEquals(1, cache.size());
    assertEquals(0.0, registry.counter("waypoint.policy.cache.cleared").count());
  }
}
