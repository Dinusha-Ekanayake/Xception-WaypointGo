package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.platform.observability.Metrics;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Tells open screens that something they show has changed (roadmap F-07b).
 *
 * <p>A dispatcher's board read about ten things every two seconds to look live.
 * Now every committed domain event names a topic (the part of its type before
 * the dot: {@code plan}, {@code trip}, {@code issue}...) and, when its payload
 * has one, a depot; a screen watching that depot re-reads that topic through
 * its usual endpoints. The signal carries no data, so nothing reaches a screen
 * that its own read would not: the read decides, under the reader's scope.
 *
 * <p>Changes are coalesced for half a second and sent once; a heartbeat every
 * {@value #HEARTBEAT_SECONDS} seconds keeps proxies that close idle connections
 * (Cloudflare at 100 s) from dropping the stream, and lets a screen notice a
 * silent drop and fall back to polling. Only this process's events are heard; a
 * change committed on another replica reaches the screen on its fallback poll.
 */
@Component
public class ChangeSignals implements DisposableBean {
  static final long HEARTBEAT_SECONDS = 25;
  static final long COALESCE_MILLIS = 500;

  /** Receives changes; throwing means the connection is gone and it is dropped. */
  public interface Listener {
    void changed(String topic, String depot) throws Exception;

    void heartbeat() throws Exception;
  }

  private record Watcher(Set<String> depots, Listener listener) {}

  record Change(String topic, String depot) {}

  private final Set<Watcher> watchers = ConcurrentHashMap.newKeySet();
  private final Set<Change> pending = new HashSet<>();
  private boolean flushScheduled;
  private final ScheduledExecutorService sender =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "platform-change-signals");
            t.setDaemon(true);
            return t;
          });

  public ChangeSignals(Metrics metrics) {
    metrics.gauge("waypoint.changes.listeners", watchers::size);
    sender.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
  }

  /** Starts watching these depots. @return what stops watching */
  public Runnable listen(Set<String> depots, Listener listener) {
    Watcher watcher = new Watcher(Set.copyOf(depots), listener);
    watchers.add(watcher);
    return () -> watchers.remove(watcher);
  }

  /** An event of this type committed, with this payload depot (null when it names none). */
  public void committed(String eventType, String depot) {
    if (watchers.isEmpty()) return;
    synchronized (pending) {
      pending.add(new Change(topicOf(eventType), depot));
      if (flushScheduled) return;
      flushScheduled = true;
    }
    sender.schedule(this::flush, COALESCE_MILLIS, TimeUnit.MILLISECONDS);
  }

  /** {@code plan.published} is {@code plan}; {@code orders.closed} is {@code order}. */
  static String topicOf(String eventType) {
    String head = eventType.contains(".") ? eventType.substring(0, eventType.indexOf('.')) : eventType;
    return head.equals("orders") ? "order" : head;
  }

  /** Whether a watcher of these depots hears a change at this depot; a change naming no depot reaches every watcher. */
  static boolean hears(Set<String> depots, String depot) {
    return depot == null || depots.contains(depot);
  }

  private void flush() {
    Set<Change> changed;
    synchronized (pending) {
      changed = Set.copyOf(pending);
      pending.clear();
      flushScheduled = false;
    }
    for (Watcher watcher : List.copyOf(watchers)) {
      for (Change change : changed) {
        if (!hears(watcher.depots(), change.depot())) continue;
        try {
          watcher.listener().changed(change.topic(), change.depot());
        } catch (Exception e) {
          watchers.remove(watcher);
          break;
        }
      }
    }
  }

  private void heartbeat() {
    for (Watcher watcher : List.copyOf(watchers)) {
      try {
        watcher.listener().heartbeat();
      } catch (Exception e) {
        watchers.remove(watcher);
      }
    }
  }

  /** For tests: deliver anything waiting now instead of after the coalescing delay. */
  void flushNow() {
    try {
      sender.submit(this::flush).get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  int watching() {
    return watchers.size();
  }

  @Override
  public void destroy() {
    sender.shutdownNow();
  }
}
