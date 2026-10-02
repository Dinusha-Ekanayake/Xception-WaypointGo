package com.waypoint.dispatch.notification.web;

import com.waypoint.dispatch.notification.application.InboxSignals;
import com.waypoint.dispatch.notification.application.NotificationDataQuery;
import com.waypoint.dispatch.notification.application.PushGateway;
import com.waypoint.dispatch.notification.contract.NotificationViews.NotificationView;
import com.waypoint.dispatch.notification.contract.NotificationViews.PushConfigView;
import com.waypoint.dispatch.notification.contract.NotificationViews.UnreadCountView;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The signed-in person's own inbox. Marking read and subscribing a device to
 * push are commands through {@code POST /api/commands}, never endpoints here.
 *
 * <p>No read takes a person to read for: an inbox is whoever is signed in.
 * {@code user} exists only so naming someone else is refused with {@code 403}
 * and recorded, rather than quietly answered with the caller's own inbox.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
  private static final String READ = NotificationDataQuery.READ;
  /** Browsers reconnect an event stream on their own; a bounded one frees the request thread. */
  private static final Duration STREAM_LIFETIME = Duration.ofMinutes(30);

  private final NotificationDataQuery notifications;
  private final InboxSignals signals;
  private final PushGateway push;
  private final RequestAuthorizer authorizer;

  public NotificationController(
      NotificationDataQuery notifications, InboxSignals signals, PushGateway push, RequestAuthorizer authorizer) {
    this.notifications = notifications;
    this.signals = signals;
    this.push = push;
    this.authorizer = authorizer;
  }

  /** Newest first, on a keyset cursor. */
  @GetMapping
  public Page<NotificationView> inbox(
      @RequestParam(required = false) UUID user,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    Actor actor = require(request);
    return notifications.inbox(actor, Optional.ofNullable(user), Optional.ofNullable(after), limit);
  }

  @GetMapping("/unread-count")
  public UnreadCountView unreadCount(@RequestParam(required = false) UUID user, HttpServletRequest request) {
    Actor actor = require(request);
    return new UnreadCountView(notifications.unreadCount(actor, Optional.ofNullable(user)));
  }

  /**
   * The unread count as it changes, as server-sent events named {@code unread}:
   * once on connect, after each change, and every 25 seconds regardless. A
   * client that hears nothing for longer than that shows live updates as paused
   * and falls back to polling {@code /unread-count} (rule 9).
   */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(HttpServletRequest request) {
    Actor actor = require(request);
    SseEmitter emitter = new SseEmitter(STREAM_LIFETIME.toMillis());
    Runnable stop =
        signals.listen(
            actor.userId(),
            count -> emitter.send(SseEmitter.event().name("unread").data(new UnreadCountView(count))));
    emitter.onCompletion(stop);
    emitter.onTimeout(stop);
    emitter.onError(e -> stop.run());
    return emitter;
  }

  /** Whether this server can push, and the key to subscribe with. Push off is reported, not hidden. */
  @GetMapping("/push-config")
  public PushConfigView pushConfig(HttpServletRequest request) {
    require(request);
    return push.config();
  }

  private Actor require(HttpServletRequest request) {
    return authorizer.require(request, READ, "wpt:notification:inbox:self");
  }
}
