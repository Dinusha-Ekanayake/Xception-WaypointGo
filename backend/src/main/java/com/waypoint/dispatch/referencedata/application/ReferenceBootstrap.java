package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceCache;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceVersionReader;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.LocalDate;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Loads the current reference version into memory once the application is up.
 *
 * <p>Deliberately not part of startup: the process must come up and report
 * liveness even when the database is unreachable. A missing version leaves the
 * cache empty and reads fail with a clear message, which is better than a boot
 * loop that hides the real problem.
 *
 * <p>This only reads. It never imports: that is an explicit operator command,
 * never a side effect of a restart.
 */
@Component
public class ReferenceBootstrap {
  private static final Logger log = LoggerFactory.getLogger(ReferenceBootstrap.class);
  private static final ZoneId OPERATING_ZONE = ZoneId.of("Asia/Colombo");
  private static final Duration CHECK_EVERY = Duration.ofSeconds(30);

  private final Database database;
  private final ReferenceVersionReader reader;
  private final ReferenceCache cache;
  private final Metrics metrics;
  private final AtomicLong lastChecked = new AtomicLong(System.nanoTime());

  public ReferenceBootstrap(
      Database database,
      ReferenceVersionReader reader,
      ReferenceCache cache,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.reader = reader;
    this.cache = cache;
    this.metrics = metrics;
    // PLT-07: alert before the supplied calendar runs out, not after. Reads the
    // snapshot in use at scrape time, so a newly imported version moves it. With no
    // version loaded it reads 0, which alerts too.
    metrics.gauge(
        "waypoint.reference.calendar.days_remaining",
        () ->
            cache
                .current()
                .map(snapshot -> snapshot.calendarDaysRemaining(today(clock)))
                .orElse(0L));
  }

  private static LocalDate today(Clock clock) {
    return LocalDate.ofInstant(clock.now(), OPERATING_ZONE);
  }

  /**
   * Notices a version another process published: {@code import-reference} run
   * from a terminal, or an import handled by another replica. Called on the read
   * path and answered from memory except once every {@link #CHECK_EVERY}, when it
   * costs one indexed read. Without it this instance would plan against the old
   * version until it was restarted.
   */
  public void refreshIfStale() {
    long now = System.nanoTime();
    long last = lastChecked.get();
    if (now - last < CHECK_EVERY.toNanos() || !lastChecked.compareAndSet(last, now)) {
      return;
    }
    try {
      Optional<UUID> current = database.readAs(ModuleRole.REF, null, reader::currentVersionId);
      if (current.isEmpty() || current.equals(cache.loadedVersionId())) {
        return;
      }
      database
          .readAs(ModuleRole.REF, null, () -> reader.load(current.get()))
          .ifPresent(
              snapshot -> {
                cache.publish(snapshot);
                metrics.increment("waypoint.reference.cache.refreshed");
              });
    } catch (RuntimeException e) {
      // The snapshot in memory is still a published version; keep serving it.
      log.warn("Could not check for a newer reference version: {}", e.getMessage());
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  public void loadCurrentVersion() {
    try {
      database
          .asModule(
              ModuleRole.REF,
              null,
              () -> reader.currentVersionId().flatMap(reader::load))
          .ifPresentOrElse(
              cache::publish,
              () -> log.warn("No current reference version. Run import-reference before planning."));
    } catch (RuntimeException e) {
      log.warn(
          "Could not load reference data at startup: {}. The instance is running without it.",
          e.getMessage());
    }
  }
}
