package com.waypoint.dispatch.notification.contract;

import com.waypoint.dispatch.notification.contract.NotificationViews.NotificationView;
import com.waypoint.dispatch.shared.domain.Page;
import java.util.Optional;
import java.util.UUID;

/** A person reads only their own inbox. */
public interface NotificationQuery {

  /** Newest first. */
  Page<NotificationView> inbox(UUID userId, Optional<String> cursor, int limit);

  long unreadCount(UUID userId);

  /**
   * R-MSG-14: when the driver read the notification of each message, for the
   * dispatcher's "Seen" mark. Only messages the driver was told about are in
   * the map; read or not, an absent one is unknown. The caller passes only
   * messages its reader may see: this answers read times, never content.
   */
  java.util.Map<UUID, Optional<java.time.Instant>> driverReads(java.util.Collection<UUID> messageIds);

  /**
   * R-EXE-31: when the driver read the push that each stop was taken off the
   * trip, for the dispatcher's "Seen by the driver" mark. As for messages: an
   * absent stop is unknown, and only read times are answered.
   */
  java.util.Map<UUID, Optional<java.time.Instant>> driverReadsOfSkips(java.util.Collection<UUID> deliveryIds);
}
