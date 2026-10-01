package com.waypoint.dispatch.platform.config;

import java.util.regex.Pattern;
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
            + " sessionAbsolute={} sessionIdle={} loginMaxFailures={} loginWindow={}"
            + " maxBodyBytes={} otlpExport={} otlpEndpoint={} traceSample={} problemTypeBase={}"
            + " warehouseBaseUrl={} warehouseApiKey={}",
        redactCredentials(app.databaseUrl()),
        app.dataDir(),
        app.migrationsDir(),
        app.cookieSecure(),
        app.session().absoluteLifetime(),
        app.session().idleLifetime(),
        app.loginThrottle().maxFailures(),
        app.loginThrottle().window(),
        app.http().maxBodyBytes(),
        app.observability().otlpExport(),
        redactCredentials(app.observability().otlpEndpoint()),
        app.observability().sampleProbability(),
        app.problemTypeBase(),
        redactCredentials(warehouse.baseUrl()),
        warehouse.isConfigured() ? "present" : "absent");
  }

  private static final Pattern USERINFO = Pattern.compile("(://)[^/@?#]*@");
  private static final Pattern SECRET_PARAM =
      Pattern.compile("(?i)([?&;](?:password|pass|pwd|user|username|apikey|api_key|token)=)[^&;#]*");

  /**
   * Keeps the scheme, host and database visible while removing credentials, whether
   * embedded as {@code user:pass@} or passed as query parameters, as a JDBC URL does
   * with {@code ?user=...&password=...}.
   */
  static String redactCredentials(String url) {
    if (url == null || url.isBlank()) {
      return "(unset)";
    }
    String redacted = USERINFO.matcher(url).replaceAll("$1***@");
    return SECRET_PARAM.matcher(redacted).replaceAll("$1***");
  }
}
