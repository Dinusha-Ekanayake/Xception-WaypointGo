package com.waypoint.dispatch.notification.application;

import com.waypoint.dispatch.notification.domain.RoutingTable;
import com.waypoint.dispatch.notification.infrastructure.JdbcNotificationRepository;
import org.springframework.stereotype.Component;

/**
 * The current routing matrix, read once per version.
 *
 * <p>Every lookup asks which version is current, one indexed row, so a newly
 * published version applies to the next event without a restart; the rules
 * themselves are read again only when the version moved.
 */
@Component
class RoutingTables {
  private final JdbcNotificationRepository repository;
  private volatile RoutingTable cached;

  RoutingTables(JdbcNotificationRepository repository) {
    this.repository = repository;
  }

  /** Inside a transaction opened as {@code waypoint_notification}. */
  RoutingTable current() {
    int version =
        repository
            .currentRoutingVersion()
            .orElseThrow(() -> new IllegalStateException("no current notification routing version"));
    RoutingTable table = cached;
    if (table == null || table.version() != version) {
      table = repository.routing(version);
      cached = table;
    }
    return table;
  }
}
