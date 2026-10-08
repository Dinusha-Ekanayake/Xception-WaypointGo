package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.NoticeEvents.StopLikelyLate;
import com.waypoint.dispatch.intelligence.domain.ExpectedArrival;
import com.waypoint.dispatch.intelligence.domain.LateNoticePolicy;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcLateNotices;
import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.contract.PlanViews.StopView;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Roadmap T1-02, R-ML-22: tells about a published stop that will likely arrive
 * after its window closes. Every ten minutes it reads today's and tomorrow's
 * published plans and their stored predictions; a stop at or above the
 * model's threshold (frozen from its backtest, else provisional) is told once,
 * to the store when the flag has earned it, else to the depot's dispatchers,
 * with the time it is expected when that is after the window. Each notice is recorded and published in one
 * transaction, so a rerun or a second replica tells nobody twice. A stop whose
 * window has already closed is not told about: the attention watch has it.
 */
@Component
public class LateNoticeJob implements ScheduledJob {
  private final Database database;
  private final PlanQuery plans;
  private final ReferenceQuery reference;
  private final JdbcIntelligenceRepository repository;
  private final JdbcLateNotices notices;
  private final EventPublisher events;
  private final Metrics metrics;

  public LateNoticeJob(
      Database database,
      PlanQuery plans,
      ReferenceQuery reference,
      JdbcIntelligenceRepository repository,
      JdbcLateNotices notices,
      EventPublisher events,
      Metrics metrics) {
    this.database = database;
    this.plans = plans;
    this.reference = reference;
    this.repository = repository;
    this.notices = notices;
    this.events = events;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "ml.late-notice";
  }

  @Override
  public String cron() {
    return "0 */10 * * * *";
  }

  @Override
  public boolean onBusinessClock() {
    return true;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ML;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many notices were given */
  int runAt(Instant now) {
    LocalDate today = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
    LocalTime time = now.atZone(Clock.OPERATING_ZONE).toLocalTime();
    Map<String, Gate> gates = new HashMap<>();
    int told = 0;
    for (String depot : database.asSystem(ModuleRole.ML, reference::depotCodes)) {
      for (LocalDate day : List.of(today, today.plusDays(1))) {
        Optional<PlanView> plan = database.asSystem(ModuleRole.ML, () -> plans.publishedPlan(depot, day));
        if (plan.isPresent()) {
          told += notice(plan.get(), day.equals(today) ? Optional.of(time) : Optional.empty(), gates, today, now);
        }
      }
    }
    return told;
  }

  /** A model's threshold (frozen, else provisional) and whether its flag has earned the stores. */
  record Gate(double threshold, boolean storesOpen) {}

  private Gate gate(String label, LocalDate today) {
    return database.asSystem(ModuleRole.ML, () -> {
      double threshold = notices.frozenThreshold(label).orElse(LateNoticePolicy.THRESHOLD);
      int[] p = notices.flagPrecision(label, threshold, today.minusDays(LateNoticePolicy.GATE_DAYS), today.minusDays(1));
      return new Gate(threshold, LateNoticePolicy.storesOpen(p[0], p[1]));
    });
  }

  private int notice(PlanView plan, Optional<LocalTime> nowToday, Map<String, Gate> gates, LocalDate today,
      Instant now) {
    List<Map<String, Object>> predictions =
        database.asSystem(ModuleRole.ML, () -> repository.predictions(plan.planId()));
    Map<UUID, Map<String, Object>> byOrder = new HashMap<>();
    predictions.forEach(p -> byOrder.put((UUID) p.get("order_id"), p));
    // Each stop, and when it is expected before the trip leaves (T1-02).
    Map<UUID, StopView> stops = new HashMap<>();
    Map<UUID, LocalTime> expected = new HashMap<>();
    plan.trips().forEach(t -> {
      List<StopView> ordered = t.stops().stream().sorted(java.util.Comparator.comparingInt(StopView::sequence)).toList();
      List<LocalTime> at = ExpectedArrival.beforeDeparture(ordered.stream()
          .map(s -> new ExpectedArrival.Stop(s.plannedArrival(), s.serviceMinutes().doubleValue(),
              Optional.ofNullable(byOrder.get(s.orderId()))
                  .map(p -> ((BigDecimal) p.get("service_min")).doubleValue())
                  .orElse(s.serviceMinutes().doubleValue())))
          .toList());
      for (int i = 0; i < ordered.size(); i++) {
        stops.put(ordered.get(i).orderId(), ordered.get(i));
        expected.put(ordered.get(i).orderId(), at.get(i));
      }
    });
    LocalDate monday = plan.serviceDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    int told = 0;
    for (Map<String, Object> p : predictions) {
      UUID orderId = (UUID) p.get("order_id");
      StopView stop = stops.get(orderId);
      BigDecimal prob = (BigDecimal) p.get("late_prob");
      String label = (String) p.get("model_label");
      Gate gate = gates.computeIfAbsent(label, l -> gate(l, today));
      if (stop == null || prob.doubleValue() < gate.threshold()
          || nowToday.map(t -> !t.isBefore(stop.windowClose())).orElse(false)) {
        continue;
      }
      // Said only when it is after the window, so the time never contradicts the warning.
      Optional<LocalTime> after = Optional.ofNullable(expected.get(orderId))
          .map(ExpectedArrival::about)
          .filter(t -> t.isAfter(stop.windowClose()));
      boolean given = database.asSystem(ModuleRole.ML, () -> {
        int thisWeek = notices.storeNotices(stop.outletId(), monday, monday.plusDays(6));
        Optional<LateNoticePolicy.Audience> audience = LateNoticePolicy.decide(
            prob.doubleValue(), gate.threshold(), notices.told(orderId), thisWeek, gate.storesOpen());
        if (audience.isEmpty()) {
          return false;
        }
        String who = audience.get().name();
        if (!notices.record(orderId, plan.planId(), stop.outletId(), plan.depotCode(), plan.serviceDate(), prob,
            label, who.toLowerCase(java.util.Locale.ROOT), now)) {
          return false;
        }
        events.publish(Actor.SYSTEM, new StopLikelyLate(
            orderId, plan.planId(), stop.outletId(), plan.depotCode(), plan.serviceDate(), stop.plannedArrival(),
            stop.windowClose(), (int) Math.round(prob.doubleValue() * 100), who, after));
        metrics.increment("waypoint.ml.late_notice", "audience", who.toLowerCase(java.util.Locale.ROOT));
        return true;
      });
      if (given) {
        told++;
      }
    }
    return told;
  }
}
