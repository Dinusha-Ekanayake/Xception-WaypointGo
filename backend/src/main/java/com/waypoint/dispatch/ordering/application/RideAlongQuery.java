package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.LoadView;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.RideAlongDay;
import com.waypoint.dispatch.ordering.contract.OrderViews.RideAlongView;
import com.waypoint.dispatch.ordering.domain.DeliveryDate;
import com.waypoint.dispatch.ordering.domain.RideAlong;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.shared.domain.Actor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The store's "a trip already goes there" hint (R-ORD-13, issue #199).
 *
 * <p>The outlet is checked against the actor's scope first, as the actor. The
 * count of other outlets' orders is then read as the system, because a store
 * manager's scope cannot see them; it returns one number per day and nothing
 * that names an outlet or an order. Nothing here touches Planning: the hint is
 * built from demand already booked, so it cannot disagree with how a plan is
 * calculated, and it never claims the order fits a vehicle.
 */
@Component
public class RideAlongQuery {
  private final Database database;
  private final OrderDataQuery orders;
  private final JdbcOrderRepository repository;
  private final DeliveryDateResolver dates;
  private final PlanQuery plans;
  private final ReferenceQuery reference;
  private final Metrics metrics;

  public RideAlongQuery(
      Database database,
      OrderDataQuery orders,
      JdbcOrderRepository repository,
      DeliveryDateResolver dates,
      PlanQuery plans,
      ReferenceQuery reference,
      Metrics metrics) {
    this.database = database;
    this.orders = orders;
    this.repository = repository;
    this.dates = dates;
    this.plans = plans;
    this.reference = reference;
    this.metrics = metrics;
  }

  public RideAlongView suggest(Actor actor, OutletView outlet, LocalDate requested, Instant now) {
    orders.requireOutletScope(actor, outlet.outletId());
    DeliveryDate chosen =
        orders.asActor(actor, () -> dates.resolve(outlet.depotCode(), requested, now));
    LocalDate day = chosen.delivery();
    if (!RideAlong.offeredTo(outlet.brandCode())) {
      return new RideAlongView(requested, day, false, List.of());
    }
    LocalDate from = day.minusDays(RideAlong.REACH_DAYS);
    LocalDate to = day.plusDays(RideAlong.REACH_DAYS);
    List<LocalDate> open =
        orders.asActor(
            actor,
            () -> {
              List<LocalDate> days = new ArrayList<>();
              for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (!dates.resolve(outlet.depotCode(), d, now).rolled()) {
                  days.add(d);
                }
              }
              return days;
            });
    Map<LocalDate, Integer> stops =
        database.readAs(
            ModuleRole.ORDERING,
            Actor.SYSTEM_ID,
            () ->
                repository.bookedStops(
                    outlet.depotCode(),
                    outlet.brandCode(),
                    outlet.districtName(),
                    outlet.outletId(),
                    from,
                    to));
    // R-ORD-14: a day is offered only when the store's usual order would join the
    // trip already going there, by Planning's load rules. With no usual order yet,
    // or Planning unable to answer, the days stand on bookings alone and say so.
    Optional<JdbcOrderRepository.BookedLoad> usual = database.readAs(
        ModuleRole.ORDERING, Actor.SYSTEM_ID, () -> repository.usualLoad(outlet.outletId()));
    Map<LocalDate, List<LoadView>> booked = new java.util.HashMap<>();
    if (usual.isPresent()) {
      Map<String, Boolean> vanOnly = new java.util.HashMap<>();
      database.readAs(ModuleRole.ORDERING, Actor.SYSTEM_ID, () -> repository.bookedLoads(
              outlet.depotCode(), outlet.brandCode(), outlet.districtName(), outlet.outletId(), from, to))
          .forEach(b -> booked.computeIfAbsent(b.date(), d -> new ArrayList<>()).add(new LoadView(
              b.temperature(),
              vanOnly.computeIfAbsent(b.outletId(),
                  id -> reference.outlet(id, null).map(OutletView::vanOnly).orElse(false)),
              b.weightKg(), b.volumeM3())));
    }
    Optional<java.util.function.Predicate<LocalDate>> joins = usual.map(u -> {
      LoadView extra = new LoadView(u.temperature(), outlet.vanOnly(), u.weightKg(), u.volumeM3());
      return d -> plans.joinsTrip(outlet.depotCode(), d, outlet.brandCode(), outlet.districtName(),
          booked.getOrDefault(d, List.of()), extra);
    });
    RideAlong.Checked checked = RideAlong.suggestChecked(outlet.brandCode(), day, open, stops, joins,
        () -> metrics.increment("waypoint.order.ride_along_room_unknown"));
    List<RideAlongDay> days = checked.days().stream()
        .map(s -> new RideAlongDay(s.date(), s.stopsBooked()))
        .toList();
    return new RideAlongView(requested, day, true, days, checked.roomChecked());
  }
}
