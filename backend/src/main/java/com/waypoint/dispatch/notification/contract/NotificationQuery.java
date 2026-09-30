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
}
