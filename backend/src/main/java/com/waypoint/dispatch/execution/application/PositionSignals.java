package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionViews.VehiclePositionView;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * The live map: who is watching a depot's or an outlet's vehicles, and telling
 * them when a new fix lands (R-EXE-23).
 *
 * <p>A watcher hears the vehicles' latest positions when it connects, about a
 * second after any fix of its depot and day is stored on this instance (fixes
 * arriving together are sent once), and every {@value #REFRESH_SECONDS} seconds
 * regardless. The periodic send is the keep-alive and the safety net for a fix
 * stored on another instance.
 *
 * <p>Every send re-reads as the watcher, through {@link PositionsQuery}, so the
 * depot scope and row-level security decide what each one hears, exactly as on
 * {@code GET /api/execution/positions}: a store stops hearing a vehicle once its
 * stops are done (R-EXE-20). A watcher whose scope was withdrawn is dropped.
 * Sends run on a thread of their own, so a slow browser never holds up a command.
 */
@Component
public class PositionSignals implements DisposableBean {
  static final long REFRESH_SECONDS = 20;
  static final long COALESCE_MILLIS = 1_000;
  private static final Logger log = LoggerFactory.getLogger(PositionSignals.class);

  /** What a watcher is watching: one depot's or one outlet's vehicles on one day. */
  public record Watch(String depotCode, String outletId, LocalDate serviceDate) {
    public static Watch depot(String depotCode, LocalDate serviceDate) {
      return new Watch(depotCode, null, serviceDate);
    }

    public static Watch outlet(String outletId, LocalDate serviceDate) {
      return new Watch(null, outletId, serviceDate);
    }

    boolean hears(String depot, LocalDate day) {
      // An outlet's vehicles can come from either depot, so any fix of the day is worth a re-read.
      return serviceDate.equals(day) && (outletId != null || depotCode.equals(depot));
    }
  }

  /** Receives the positions; throwing means the connection is gone and it is dropped. */
  @FunctionalInterface
  public interface Listener {
    void positions(List<VehiclePositionView> positions) throws Exception;
  }

  private record Watcher(Actor actor, Watch watch, Listener listener) {}

  private record DepotDay(String depotCode, LocalDate serviceDate) {}

  private final PositionsQuery query;
  private final Metrics metrics;
  private final Set<Watcher> watchers = ConcurrentHashMap.newKeySet();
  private final Set<DepotDay> pending = new HashSet<>();
  private boolean flushScheduled;
  private final ScheduledExecutorService sender =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "execution-position-signals");
            t.setDaemon(true);
            return t;
          });

  PositionSignals(PositionsQuery query, Metrics metrics) {
    this.query = query;
    this.metrics = metrics;
    metrics.gauge("waypoint.execution.position_listeners", watchers::size);
    sender.scheduleWithFixedDelay(this::refreshAll, REFRESH_SECONDS, REFRESH_SECONDS, TimeUnit.SECONDS);
  }

  /** Starts watching and sends the current positions at once. @return what stops watching */
  public Runnable listen(Actor actor, Watch watch, Listener listener) {
    Watcher watcher = new Watcher(actor, watch, listener);
    watchers.add(watcher);
    sender.execute(() -> send(watcher));
    return () -> watchers.remove(watcher);
  }

  /** Fixes of this depot and day were stored: tell its watchers shortly, once for the lot. */
  public void changed(String depotCode, LocalDate serviceDate) {
    if (watchers.isEmpty()) return;
    synchronized (pending) {
      pending.add(new DepotDay(depotCode, serviceDate));
      if (flushScheduled) return;
      flushScheduled = true;
    }
    sender.schedule(this::flush, COALESCE_MILLIS, TimeUnit.MILLISECONDS);
  }

  private void flush() {
    Set<DepotDay> changed;
    synchronized (pending) {
      changed = Set.copyOf(pending);
      pending.clear();
      flushScheduled = false;
    }
    for (Watcher watcher : List.copyOf(watchers)) {
      if (changed.stream().anyMatch(c -> watcher.watch().hears(c.depotCode(), c.serviceDate()))) send(watcher);
    }
  }

  private void refreshAll() {
    List.copyOf(watchers).forEach(this::send);
  }

  private void send(Watcher watcher) {
    if (!watchers.contains(watcher)) return;
    Watch watch = watcher.watch();
    List<VehiclePositionView> positions;
    try {
      positions = watch.outletId() == null
          ? query.ofDepot(watcher.actor(), watch.depotCode(), watch.serviceDate())
          : query.forOutlet(watcher.actor(), watch.outletId(), watch.serviceDate());
    } catch (DomainException e) {
      // The scope was withdrawn since the stream opened: stop telling them anything.
      watchers.remove(watcher);
      return;
    } catch (RuntimeException e) {
      // The database is down: send nothing rather than an empty map. A client that
      // hears nothing past the refresh interval shows live updates as paused.
      metrics.increment("waypoint.execution.position_send_failed");
      log.debug("Could not read positions for a watcher: {}", e.getMessage());
      return;
    }
    try {
      watcher.listener().positions(positions);
    } catch (Exception e) {
      watchers.remove(watcher);
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
