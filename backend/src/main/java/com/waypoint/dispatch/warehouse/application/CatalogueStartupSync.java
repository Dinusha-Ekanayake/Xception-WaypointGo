package com.waypoint.dispatch.warehouse.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcCatalogueRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * A database that has never held the catalogue (a first install, or a demo
 * database just reset) gets it as soon as the backend serves, rather than at the
 * next quarter-hour run of {@link CatalogueSyncJob}, so a store is not told the
 * warehouse is not answering for up to 15 minutes. Runs once, in the background,
 * only when serving (never during migrate or import), and only when no sync ever
 * succeeded here; the 15-minute refresh is unchanged.
 */
@Component
class CatalogueStartupSync implements ApplicationListener<ApplicationReadyEvent> {
  private static final Logger log = LoggerFactory.getLogger(CatalogueStartupSync.class);

  private final CatalogueSyncJob job;
  private final JdbcCatalogueRepository catalogue;
  private final Database database;
  private final boolean enabled;
  private final boolean serving;

  CatalogueStartupSync(CatalogueSyncJob job, JdbcCatalogueRepository catalogue, Database database,
      ApplicationArguments arguments, @Value("${app.warehouse.sync-on-start:true}") boolean enabled) {
    this.job = job;
    this.catalogue = catalogue;
    this.database = database;
    this.enabled = enabled;
    this.serving = arguments.getNonOptionArgs().isEmpty();
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (!enabled || !serving) return;
    Thread sync = new Thread(() -> {
      try {
        boolean never = database.asSystem(ModuleRole.WAREHOUSE, () -> catalogue.lastGood().isEmpty());
        if (never) log.info("No catalogue yet; first sync now: {}", job.sync(Instant.now()));
      } catch (RuntimeException e) {
        // The quarter-hour job tries again; the store says the copy is stale meanwhile.
        log.warn("First catalogue sync failed: {}", e.getClass().getSimpleName());
      }
    }, "warehouse-first-sync");
    sync.setDaemon(true);
    sync.start();
  }
}
