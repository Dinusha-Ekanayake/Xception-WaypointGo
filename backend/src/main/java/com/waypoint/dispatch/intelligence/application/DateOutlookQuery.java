package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.PredictionViews.DateOutlookView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.DayOutlookView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.OutlookStatus;
import com.waypoint.dispatch.intelligence.domain.DateOutlookPolicy;
import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.BookedVolumeView;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.CalendarDayView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The date outlook a store manager reads while choosing a delivery day
 * (issue #224, R-ML-07).
 *
 * <p>Scope is checked as the asking actor: the outlet must be theirs, or the
 * read is {@code 403} with an audit row. The depot's totals behind the answer
 * (booked volume from Ordering's contract, the forecast, the vehicles
 * available) are then read as the system, because a store's scope covers its
 * own orders only; what leaves is a status per day, never a row.
 */
@Component
public class DateOutlookQuery {
  public static final String READ = "ml:ReadOutlook";
  /** One request covers at most a month; the store's strip shows 28 days (P-34). */
  static final int MAX_DAYS = 31;

  private final Database database;
  private final JdbcIntelligenceRepository repository;
  private final OrderQuery orders;
  private final PlanQuery plans;
  private final ReferenceQuery reference;
  private final AuditLog audit;
  private final Metrics metrics;

  public DateOutlookQuery(
      Database database,
      JdbcIntelligenceRepository repository,
      OrderQuery orders,
      PlanQuery plans,
      ReferenceQuery reference,
      AuditLog audit,
      Metrics metrics) {
    this.database = database;
    this.repository = repository;
    this.orders = orders;
    this.plans = plans;
    this.reference = reference;
    this.audit = audit;
    this.metrics = metrics;
  }

  public DateOutlookView outlook(Actor actor, String outletId, LocalDate from, LocalDate to) {
    if (from.isAfter(to)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "from is after to");
    }
    if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "An outlook covers at most " + MAX_DAYS + " days");
    }
    requireOutlet(actor, outletId);
    return metrics.time("waypoint.ml.estimate", () -> build(outletId, from, to), "operation", "date_outlook");
  }

  private DateOutlookView build(String outletId, LocalDate from, LocalDate to) {
    OutletView outlet = reference.outlet(outletId, null)
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No outlet " + outletId));
    Assessed a = assess(outlet.depotCode(), outlet.brandCode(), from, to);
    return new DateOutlookView(outletId, from, to, a.days(), a.forecast(), a.modelLabel(), a.degraded());
  }

  /** The days of a depot for one brand, and what the forecast behind them was. */
  public record Assessed(List<DayOutlookView> days, boolean forecast, String modelLabel, boolean degraded) {}

  /**
   * The one computation behind the store's strip and the watch job (rule 5):
   * a depot's days for a brand, from totals read as the system. The caller has
   * checked scope, or is the system.
   */
  public Assessed assess(String depot, String brandCode, LocalDate from, LocalDate to) {
    LocalDate firstMonday = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    LocalDate lastSunday = to.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));

    List<BookedVolumeView> booked = orders.bookedVolumes(depot, from, to);
    List<Map<String, Object>> forecasts = database.readAs(ModuleRole.ML, Actor.SYSTEM_ID,
        () -> repository.latestDepotForecasts(depot, weekKey(firstMonday), weekKey(lastSunday)));
    Map<LocalDate, CalendarDayView> calendar = new HashMap<>();
    reference.calendarDays(firstMonday, lastSunday).forEach(d -> calendar.put(d.date(), d));

    // A-05: only some brands send chilled goods; the store's own history and forecast say whether its brand does.
    boolean chilled = booked.stream().anyMatch(b -> b.brandCode().equals(brandCode) && b.chilledM3().signum() > 0)
        || forecasts.stream().anyMatch(f -> brandCode.equals(f.get("brand_code"))
            && ((BigDecimal) f.get("chilled_m3")).signum() > 0);

    Map<Integer, BigDecimal[]> weekly = new HashMap<>();
    for (Map<String, Object> f : forecasts) {
      int key = ((Number) f.get("iso_year")).intValue() * 100 + ((Number) f.get("iso_week")).intValue();
      BigDecimal[] sum = weekly.computeIfAbsent(key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
      sum[0] = sum[0].add((BigDecimal) f.get("total_m3"));
      sum[1] = sum[1].add((BigDecimal) f.get("chilled_m3"));
    }

    List<DayOutlookView> days = new ArrayList<>();
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      LocalDate day = d;
      boolean operating = Optional.ofNullable(calendar.get(day)).map(CalendarDayView::operating)
          .orElseGet(() -> reference.isOperating(day));
      BigDecimal bookedM3 = BigDecimal.ZERO;
      BigDecimal bookedCold = BigDecimal.ZERO;
      for (BookedVolumeView b : booked) {
        if (b.date().equals(day)) {
          bookedM3 = bookedM3.add(b.totalM3());
          bookedCold = bookedCold.add(b.chilledM3());
        }
      }
      // A-41: a day's share is the week's forecast over the week's operating days.
      BigDecimal[] week = weekly.get(weekKey(day));
      int operatingInWeek = operatingDays(calendar, day);
      Optional<BigDecimal> share = Optional.ofNullable(week).filter(w -> operatingInWeek > 0)
          .map(w -> w[0].divide(BigDecimal.valueOf(operatingInWeek), 4, RoundingMode.HALF_UP));
      Optional<BigDecimal> coldShare = Optional.ofNullable(week).filter(w -> operatingInWeek > 0)
          .map(w -> w[1].divide(BigDecimal.valueOf(operatingInWeek), 4, RoundingMode.HALF_UP));

      BigDecimal room = BigDecimal.ZERO;
      BigDecimal coldRoom = BigDecimal.ZERO;
      if (operating) {
        for (VehicleView v : reference.availableVehicles(depot, day, null)) {
          BigDecimal trips = v.volumeCapM3().multiply(BigDecimal.valueOf(plans.maxTripsFor(day)));
          room = room.add(trips);
          coldRoom = v.refrigerated() ? coldRoom.add(trips) : coldRoom;
        }
      }
      DateOutlookPolicy.Outlook o = DateOutlookPolicy.assess(new DateOutlookPolicy.Inputs(
          operating, bookedM3, bookedCold, share, coldShare, room, coldRoom, chilled));
      days.add(new DayOutlookView(day, OutlookStatus.valueOf(o.status().name()), o.load(), o.reason()));
    }

    String label = forecasts.stream().map(f -> (String) f.get("model_label")).distinct()
        .reduce((a, b) -> "mixed").orElse("none");
    boolean degraded = forecasts.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("degraded")));
    return new Assessed(days, !forecasts.isEmpty(), label, degraded);
  }

  /**
   * An outlet outside the actor's scope is {@code 403} plus an audit row, never
   * an empty outlook: the totals behind it are read as the system.
   */
  private void requireOutlet(Actor actor, String outletId) {
    boolean inScope = database.readAs(ModuleRole.ML, actor.userId(), () -> Boolean.TRUE.equals(
        database.queryOne("SELECT app.actor_has_outlet(?) AS ok", outletId).get("ok")));
    if (!inScope) {
      String resource = "wpt:ml:outlet:" + outletId;
      String reason = "outside the actor's scope";
      audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), READ, resource, reason));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason);
    }
  }

  private static int operatingDays(Map<LocalDate, CalendarDayView> calendar, LocalDate day) {
    LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    int n = 0;
    for (int i = 0; i < 7; i++) {
      CalendarDayView c = calendar.get(monday.plusDays(i));
      n += c != null && c.operating() ? 1 : 0;
    }
    return n;
  }

  private static int weekKey(LocalDate day) {
    return day.get(IsoFields.WEEK_BASED_YEAR) * 100 + day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
  }
}
