package com.waypoint.dispatch.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs what the process actually booted with, once, at startup.
 *
 * <p>Secrets are reported as present or absent and never printed. "Which
 * configuration is this instance running?" should be answerable from the logs
 * without shell access to the container.
 */
@Component
public class ConfigurationReport {
  private static final Logger log = LoggerFactory.getLogger(ConfigurationReport.class);

  private final AppProperties app;
  private final WarehouseProperties warehouse;

  public ConfigurationReport(AppProperties app, WarehouseProperties warehouse) {
    this.app = app;
    this.warehouse = warehouse;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void report() {
    log.info(
        "Configuration: database={} dataDir={} migrationsDir={} cookieSecure={}"
            + " warehouseBaseUrl={} warehouseApiKey={}",
        redactCredentials(app.databaseUrl()),
        app.dataDir(),
        app.migrationsDir(),
        app.cookieSecure(),
        warehouse.baseUrl(),
        warehouse.isConfigured() ? "present" : "absent");
  }

  /** Keeps the host and database visible while removing any embedded password. */
  static String redactCredentials(String url) {
    if (url == null || url.isBlank()) {
      return "(unset)";
    }
    int scheme = url.indexOf("://");
    int at = url.lastIndexOf('@');
    if (scheme < 0 || at < 0 || at < scheme) {
      return url;
    }
    return url.substring(0, scheme + 3) + "***@" + url.substring(at + 1);
  }
}
