package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.platform.observability.Metrics;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
 * <p>A store watches its outlets instead of a depot: it hears a change whose
 * payload names one of them ({@code outletId} or {@code outletIds}), and only
 * the topic, never the depot or the other outlets the event named.
 *
 * <p>A driver watches a vehicle (live refresh, phase 3): it hears the topic of a
 * change whose payload names that vehicle anywhere ({@code vehicleId}, a plan's
 * trips included), and nothing else.
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

  private record Watcher(Set<String> depots, Set<String> outlets, Set<String> vehicles, Listener listener) {}

  record Change(String topic, String depot, Set<String> outlets, Set<String> vehicles) {
    Change(String topic, String depot, Set<String> outlets) {
      this(topic, depot, outlets, Set.of());
    }
  }

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
    return listen(depots, Set.of(), listener);
  }

  /** Starts watching these depots and outlets. @return what stops watching */
  public Runnable listen(Set<String> depots, Set<String> outlets, Listener listener) {
    return listen(depots, outlets, Set.of(), listener);
  }

  /** Starts watching these depots, outlets and vehicles. @return what stops watching */
  public Runnable listen(Set<String> depots, Set<String> outlets, Set<String> vehicles, Listener listener) {
    Watcher watcher = new Watcher(Set.copyOf(depots), Set.copyOf(outlets), Set.copyOf(vehicles), listener);
    watchers.add(watcher);
    return () -> watchers.remove(watcher);
  }

  /** An event of this type committed, with this payload depot (null when it names none). */
  public void committed(String eventType, String depot) {
    committed(eventType, depot, Set.of());
  }

  /** An event of this type committed, naming this depot (or null) and these outlets. */
  public void committed(String eventType, String depot, Set<String> outlets) {
    committed(eventType, depot, outlets, Set.of());
  }

  /** An event of this type committed, naming this depot (or null), these outlets and these vehicles. */
  public void committed(String eventType, String depot, Set<String> outlets, Set<String> vehicles) {
    if (watchers.isEmpty()) return;
    synchronized (pending) {
      pending.add(new Change(topicOf(eventType), depot, Set.copyOf(outlets), Set.copyOf(vehicles)));
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

  /**
   * What a watcher is told of a change, or null when it hears nothing. A depot
   * watcher hears its depots and every change naming no depot, as before outlets
   * were watched; an outlet watcher hears the topic alone when the change names
   * one of its outlets, or names neither a depot nor an outlet.
   */
  static Change heard(Set<String> depots, Set<String> outlets, Change change) {
    return heard(depots, outlets, Set.of(), change);
  }

  /** As above, and a vehicle watcher hears the topic alone when the change names one of its vehicles. */
  static Change heard(Set<String> depots, Set<String> outlets, Set<String> vehicles, Change change) {
    if (!vehicles.isEmpty() && depots.isEmpty() && outlets.isEmpty()) {
      for (String vehicle : change.vehicles()) {
        if (vehicles.contains(vehicle)) return new Change(change.topic(), null, Set.of());
      }
      return null;
    }
    if (change.depot() == null ? !depots.isEmpty() : depots.contains(change.depot())) {
      return new Change(change.topic(), change.depot(), Set.of());
    }
    if (change.depot() == null && change.outlets().isEmpty()) return change;
    for (String outlet : change.outlets()) {
      if (outlets.contains(outlet)) return new Change(change.topic(), null, Set.of());
    }
    return null;
  }

  private void flush() {
    Set<Change> changed;
    synchronized (pending) {
      changed = Set.copyOf(pending);
      pending.clear();
      flushScheduled = false;
    }
    for (Watcher watcher : List.copyOf(watchers)) {
      // Changes that differ only in their outlets reach a watcher once.
      Set<Change> told = new LinkedHashSet<>();
      for (Change change : changed) {
        Change heard = heard(watcher.depots(), watcher.outlets(), watcher.vehicles(), change);
        if (heard != null) told.add(new Change(heard.topic(), heard.depot(), Set.of()));
      }
      for (Change change : told) {
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
