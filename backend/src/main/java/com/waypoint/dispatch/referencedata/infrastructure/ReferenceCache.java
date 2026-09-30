package com.waypoint.dispatch.referencedata.infrastructure;

import com.waypoint.dispatch.referencedata.domain.ReferenceSnapshot;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds the current reference version in memory.
 *
 * <p>Reference data is read on every constraint evaluation, thousands of times
 * per allocation, so it is not fetched per query. A snapshot is immutable and is
 * never mutated: publishing swaps the pointer, which means a reader holding the
 * old snapshot finishes its work against a consistent view rather than seeing
 * data change underneath it.
 *
 * <p>Historical versions are read from the database on demand and deliberately
 * not cached; replaying an old decision is rare and should not cost memory.
 */
@Component
public class ReferenceCache {
  private static final Logger log = LoggerFactory.getLogger(ReferenceCache.class);

  private final AtomicReference<ReferenceSnapshot> current = new AtomicReference<>();

  public Optional<ReferenceSnapshot> current() {
    return Optional.ofNullable(current.get());
  }

  /** Replaces the pointer. The previous snapshot stays valid for anyone still reading it. */
  public void publish(ReferenceSnapshot snapshot) {
    ReferenceSnapshot previous = current.getAndSet(snapshot);
    UUID from = previous == null ? null : previous.versionId();
    log.info("Reference version in use: {} (was {})", snapshot.versionId(), from);
  }

  public void clear() {
    current.set(null);
  }

  /** Reported by health and by the dispatcher's version indicator, so a stale instance is visible. */
  public Optional<UUID> loadedVersionId() {
    return current().map(ReferenceSnapshot::versionId);
  }
}
