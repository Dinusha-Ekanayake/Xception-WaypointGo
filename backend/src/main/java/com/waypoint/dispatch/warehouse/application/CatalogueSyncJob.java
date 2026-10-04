package com.waypoint.dispatch.warehouse.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import com.waypoint.dispatch.warehouse.contract.WarehouseEvents.CatalogueSynced;
import com.waypoint.dispatch.warehouse.domain.CatalogueEntry;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcCatalogueRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcCatalogueRepository.LastSync;
import com.waypoint.dispatch.warehouse.infrastructure.WarehouseHttpClient;
import com.waypoint.dispatch.warehouse.infrastructure.WarehouseHttpClient.Listing;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Copies the warehouse catalogue into {@code warehouse.products} (CataloguePort).
 *
 * <p>A full read every run: the warehouse ignores {@code updated_since}, and the
 * catalogue is a few hundred rows. The whole read must succeed before anything
 * is written, so a failure half way never leaves a mixed catalogue; the last good
 * copy stays and is reported stale with its age (CAT-01). A content hash names
 * the version, so an unchanged catalogue publishes nothing.
 */
@Component
public class CatalogueSyncJob implements ScheduledJob {
  private static final int PAGE = 100;
  private static final int MAX_PAGES = 50;

  private final Database database;
  private final JdbcCatalogueRepository catalogue;
  private final WarehouseHttpClient client;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final AtomicReference<Instant> lastGood = new AtomicReference<>();

  public CatalogueSyncJob(
      Database database,
      JdbcCatalogueRepository catalogue,
      WarehouseHttpClient client,
      EventPublisher events,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.catalogue = catalogue;
    this.client = client;
    this.events = events;
    this.metrics = metrics;
    // A sync is stamped on real time: freshness is how old the copy really is, and a
    // demo clock moved forward must not make a fresh catalogue look stale.
    this.clock = clock.realTime();
    metrics.gauge("waypoint.warehouse.catalogue_age_seconds", () -> {
      Instant at = lastGood.get();
      return at == null ? -1 : Duration.between(at, this.clock.now()).toSeconds();
    });
  }

  @Override
  public String name() {
    return "warehouse.catalogue-sync";
  }

  @Override
  public String cron() {
    return "0 5/15 * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.WAREHOUSE;
  }

  @Override
  public void run(Instant now) {
    sync(clock.now());
  }

  /** @return the outcome: {@code changed}, {@code unchanged} or {@code failed} */
  public String sync(Instant started) {
    if (!client.configured()) {
      return "failed";
    }
    List<CatalogueEntry> entries = new ArrayList<>();
    String error = null;
    for (int page = 1; page <= MAX_PAGES; page++) {
      Listing<CatalogueEntry> listing = client.products(page, PAGE);
      if (listing.failure().isPresent()) {
        error = listing.failure().get().reason();
        break;
      }
      entries.addAll(listing.items());
      if (listing.items().size() < PAGE || entries.size() >= listing.total()) {
        break;
      }
    }
    Instant finished = clock.now();
    if (error == null && entries.isEmpty()) {
      error = "the warehouse listed no products";
    }
    if (error != null) {
      String reason = error;
      database.asSystem(ModuleRole.WAREHOUSE, () ->
          catalogue.recordSync(UuidV7.generate(finished, random), started, finished, "failed", null, null, reason));
      metrics.increment("waypoint.warehouse.catalogue_sync", "outcome", "failed");
      return "failed";
    }
    String version = CatalogueEntry.version(entries);
    String outcome =
        database.asSystem(ModuleRole.WAREHOUSE, () -> {
          Optional<LastSync> last = catalogue.lastGood();
          boolean changed = last.isEmpty() || !version.equals(last.get().catalogueVersion());
          if (changed) {
            catalogue.upsert(entries, version, finished);
            events.publish(Actor.SYSTEM, new CatalogueSynced(version, entries.size(), finished));
          } else {
            catalogue.touch(version, finished);
          }
          String result = changed ? "changed" : "unchanged";
          catalogue.recordSync(
              UuidV7.generate(finished, random), started, finished, result, version, entries.size(), null);
          return result;
        });
    lastGood.set(finished);
    metrics.increment("waypoint.warehouse.catalogue_sync", "outcome", outcome);
    return outcome;
  }

  /** Seeds the age gauge from the database after a restart. */
  void remember(Instant syncedAt) {
    lastGood.compareAndSet(null, syncedAt);
  }
}
