package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.OutlookEvents.OrderOutlookChanged;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.DayOutlookView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.OutlookStatus;
import com.waypoint.dispatch.intelligence.domain.DateOutlookPolicy;
import com.waypoint.dispatch.intelligence.domain.OutlookChangePolicy;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.OpenOrderView;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Warns a store when a day it already booked worsens to busy or at risk
 * (issue #224, slice 3, R-ML-08): workshop vehicles booked later, or more
 * orders than the forecast expected.
 *
 * <p>Hourly from 06:00 to 15:00, depot time: after the 16:00 cutoff the plan
 * decides, and its deferral is told by {@code order.deferred}. Watches orders
 * booked from tomorrow to {@link #WINDOW_DAYS} ahead and not yet planned, with
 * the same outlook the store saw when booking ({@link DateOutlookQuery#assess}).
 * A store is told once per order, day and worse status ({@link OutlookChangePolicy});
 * each warning is recorded and published in one transaction, so a rerun or a
 * second replica sends nothing twice.
 */
@Component
public class OutlookWatchJob implements ScheduledJob {
  /** P-36: how far ahead booked days are watched. */
  static final int WINDOW_DAYS = 14;

  private final Database database;
  private final DateOutlookQuery outlook;
  private final JdbcIntelligenceRepository repository;
  private final OrderQuery orders;
  private final ReferenceQuery reference;
  private final EventPublisher events;
  private final Metrics metrics;

  public OutlookWatchJob(
      Database database,
      DateOutlookQuery outlook,
      JdbcIntelligenceRepository repository,
      OrderQuery orders,
      ReferenceQuery reference,
      EventPublisher events,
      Metrics metrics) {
    this.database = database;
    this.outlook = outlook;
    this.repository = repository;
    this.orders = orders;
    this.reference = reference;
    this.events = events;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "ml.outlook-watch";
  }

  @Override
  public String cron() {
    return "0 0 6-15 * * MON-SAT";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ML;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many stores were warned */
  int runAt(Instant now) {
    LocalDate today = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
    return watch(today.plusDays(1), today.plusDays(WINDOW_DAYS), now);
  }

  /** The window as given; tests name days far from any other test's. */
  int watch(LocalDate from, LocalDate to, Instant now) {
    int warned = 0;
    for (String depot : reference.depotCodes()) {
      List<OpenOrderView> open = orders.openOrders(depot, from, to);
      Map<String, List<OpenOrderView>> byBrand =
          open.stream().collect(Collectors.groupingBy(OpenOrderView::brandCode));
      for (Map.Entry<String, List<OpenOrderView>> brand : byBrand.entrySet()) {
        Map<LocalDate, DayOutlookView> days = outlook.assess(depot, brand.getKey(), from, to).days().stream()
            .collect(Collectors.toMap(DayOutlookView::date, Function.identity()));
        for (OpenOrderView order : brand.getValue()) {
          DayOutlookView day = days.get(order.deliveryDate());
          if (day == null) {
            continue;
          }
          try {
            if (warn(depot, order, day, now)) {
              warned++;
            }
          } catch (RuntimeException e) {
            // One order that fails is counted and tried again next hour; the rest still run.
            metrics.increment("waypoint.ml.outlook_warn_failed");
          }
        }
      }
    }
    return warned;
  }

  private boolean warn(String depot, OpenOrderView order, DayOutlookView day, Instant now) {
    return database.asSystem(ModuleRole.ML, () -> {
      Optional<DateOutlookPolicy.Status> before = repository.warnedOutlook(order.orderId(), order.deliveryDate())
          .map(DateOutlookPolicy.Status::valueOf);
      Optional<DateOutlookPolicy.Status> tell =
          OutlookChangePolicy.warn(before, DateOutlookPolicy.Status.valueOf(day.status().name()));
      if (tell.isEmpty()) {
        return false;
      }
      repository.recordWarning(
          order.orderId(), order.deliveryDate(), order.outletId(), depot, tell.get().name(), day.reason(), now);
      events.publish(Actor.SYSTEM, new OrderOutlookChanged(
          order.orderId(), order.outletId(), depot, order.deliveryDate(),
          OutlookStatus.valueOf(tell.get().name()), day.reason()));
      metrics.increment("waypoint.ml.outlook_warned", "status", tell.get().name().toLowerCase(java.util.Locale.ROOT));
      return true;
    });
  }
}
