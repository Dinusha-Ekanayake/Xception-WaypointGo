package com.waypoint.dispatch.notification.application;

import com.waypoint.dispatch.notification.contract.NotificationViews.PushConfigView;
import com.waypoint.dispatch.notification.domain.Delivery.PushResult;
import java.time.Duration;

/**
 * A browser's push service: the one thing Notification reaches outside the
 * process. Called only outside a transaction (R-NOT-05).
 */
public interface PushGateway {

  /** Whether push is configured, and the key browsers subscribe with. */
  PushConfigView config();

  /** One subscribed browser, as its Push API subscription described it. */
  record Target(String endpoint, String p256dhKey, String authSecret) {}

  /** What the push service answered, with a detail worth keeping when it was not a yes. */
  record Response(PushResult result, String detail) {}

  /**
   * Encrypts and sends one message. Never throws for a push service's answer or
   * an unreachable one; that is a {@link PushResult}.
   */
  Response send(Target target, byte[] payload, Duration ttl, boolean urgent);
}
