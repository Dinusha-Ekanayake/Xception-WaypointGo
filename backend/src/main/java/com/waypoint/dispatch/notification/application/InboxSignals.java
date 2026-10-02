package com.waypoint.dispatch.notification.application;

import com.waypoint.dispatch.platform.observability.Metrics;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * The live unread badge: who is listening, and telling them when their count
 * changes.
 *
 * <p>A listener hears its person's count when it connects, after every
 * notification written for them or read by them on this instance, and every
 * {@value #REFRESH_SECONDS} seconds regardless. The periodic send is both the
 * keep-alive and the safety net: a change made on another instance, whose
 * signal never reaches this one, still arrives within that interval.
 *
 * <p>Sends run on a thread of their own, so a slow browser never holds up the
 * event relay or a command that caused the change.
 */
@Component
public class InboxSignals implements DisposableBean {
  static final long REFRESH_SECONDS = 25;
  private static final Logger log = LoggerFactory.getLogger(InboxSignals.class);

  /** Receives a count; throwing means the connection is gone and it is dropped. */
  @FunctionalInterface
  public interface Listener {
    void unread(long count) throws Exception;
  }

  private final NotificationDataQuery query;
  private final Metrics metrics;
  private final Map<UUID, Set<Listener>> listeners = new ConcurrentHashMap<>();
  private final ScheduledExecutorService sender =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "notification-inbox-signals");
            t.setDaemon(true);
            return t;
          });

  InboxSignals(NotificationDataQuery query, Metrics metrics) {
    this.query = query;
    this.metrics = metrics;
    metrics.gauge(
        "waypoint.notification.live_listeners", () -> listeners.values().stream().mapToInt(Set::size).sum());
    sender.scheduleWithFixedDelay(this::refreshAll, REFRESH_SECONDS, REFRESH_SECONDS, TimeUnit.SECONDS);
  }

  /** Starts listening and sends the current count at once. @return what stops listening */
  public Runnable listen(UUID userId, Listener listener) {
    listeners.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(listener);
    sender.execute(() -> send(userId, Set.of(listener)));
    return () -> forget(userId, listener);
  }

  /** These people's counts changed: tell whoever of them is listening here. */
  public void changed(Collection<UUID> userIds) {
    List<UUID> listening = userIds.stream().filter(listeners::containsKey).toList();
    if (!listening.isEmpty()) {
      sender.execute(() -> listening.forEach(u -> send(u, listeners.getOrDefault(u, Set.of()))));
    }
  }

  private void refreshAll() {
    listeners.forEach(this::send);
  }

  private void send(UUID userId, Set<Listener> targets) {
    if (targets.isEmpty()) {
      return;
    }
    long count;
    try {
      count = query.unreadCountOf(userId);
    } catch (RuntimeException e) {
      // The database is down: send nothing rather than a wrong number. A client
      // that hears nothing past the refresh interval shows the badge as paused.
      metrics.increment("waypoint.notification.live_count_failed");
      log.debug("Could not count unread notifications: {}", e.getMessage());
      return;
    }
    for (Listener listener : List.copyOf(targets)) {
      try {
        listener.unread(count);
      } catch (Exception e) {
        forget(userId, listener);
      }
    }
  }

  private void forget(UUID userId, Listener listener) {
    listeners.computeIfPresent(
        userId,
        (k, set) -> {
          set.remove(listener);
          return set.isEmpty() ? null : set;
        });
  }

  @Override
  public void destroy() {
    sender.shutdownNow();
  }
}
