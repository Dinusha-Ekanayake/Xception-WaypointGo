package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.contract.OrderViews.DemandView;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderLineView;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.ordering.contract.OrderViews.StatusChangeView;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.domain.Reservation;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository.Stored;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Cursor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads orders, for other modules through {@link OrderQuery} and for the store
 * and dispatcher through the web layer.
 *
 * <p>Every read runs as {@code waypoint_ordering} in a read-only transaction of
 * its own ({@link Database#readAs}), so row-level security narrows it to the
 * actor's outlets and depots in SQL (rule 7). A contract caller reads as the
 * actor of the unit of work it is inside; with none, it sees nothing.
 *
 * <p>A single order outside scope is indistinguishable from one that does not
 * exist: {@code 404}, so an id cannot be used to probe for other outlets' orders.
 * A list asked for outside scope is {@code 403} plus an audit row, never an
 * empty page, because an empty page reads as "no orders" (AGENTS.md, Security).
 */
@Component
public class OrderDataQuery implements OrderQuery {
  static final int MAX_PAGE = 100;
  public static final String READ = "order:Read";

  private final Database database;
  private final JdbcOrderRepository orders;
  private final AuditLog audit;

  public OrderDataQuery(Database database, JdbcOrderRepository orders, AuditLog audit) {
    this.database = database;
    this.orders = orders;
    this.audit = audit;
  }

  // ---- contract: as the ambient actor --------------------------------------

  @Override
  public List<DemandView> confirmedDemand(String depotCode, LocalDate serviceDate) {
    return read(ambient(), () -> orders.demand(depotCode, serviceDate))
        .stream()
        .map(OrderDataQuery::toDemand)
        .toList();
  }

  @Override
  public Optional<OrderView> order(UUID orderId) {
    return read(ambient(), () -> orders.findStored(orderId).map(OrderDataQuery::toView));
  }

  @Override
  public List<StatusChangeView> timeline(UUID orderId) {
    return read(ambient(), () -> history(orderId));
  }

  @Override
  public Page<OrderView> ordersForOutlet(String outletId, Optional<String> cursor, int limit) {
    return read(ambient(), () -> page(outletId, cursor, limit));
  }

  // ---- web: as the authenticated actor -------------------------------------

  public OrderView order(Actor actor, UUID orderId) {
    return read(actor.userId(), () -> orders.findStored(orderId).map(OrderDataQuery::toView)
            .map(v -> withStops(List.of(v)).get(0)))
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No order " + orderId));
  }

  /** Planned, loading or on the road: the stop on the published plan still holds (issue #224). */
  private static final java.util.Set<OrderStatus> ON_PLAN =
      java.util.Set.of(OrderStatus.ALLOCATED, OrderStatus.LOADING, OrderStatus.IN_TRANSIT);

  /** Each order with its stop and expected arrival, read inside the caller's transaction and scope. */
  private List<OrderView> withStops(List<OrderView> views) {
    List<UUID> ids = views.stream().filter(v -> ON_PLAN.contains(v.status())).map(OrderView::orderId).toList();
    if (ids.isEmpty()) {
      return views;
    }
    Map<UUID, Map<String, Object>> stops = orders.stops(ids);
    return views.stream().map(v -> {
      Map<String, Object> s = stops.get(v.orderId());
      if (s == null || !ON_PLAN.contains(v.status())
          || !((java.sql.Date) s.get("service_date")).toLocalDate().equals(v.deliveryDate())) {
        return v;
      }
      Optional<java.time.LocalTime> arrival =
          Optional.ofNullable((java.sql.Time) s.get("planned_arrival")).map(java.sql.Time::toLocalTime);
      return v.withStop(((Number) s.get("stop_sequence")).intValue(), arrival);
    }).toList();
  }

  public List<StatusChangeView> timeline(Actor actor, UUID orderId) {
    return read(
        actor.userId(),
        () -> {
          if (orders.findStored(orderId).isEmpty()) {
            throw new DomainException(ErrorCode.NOT_FOUND, "No order " + orderId);
          }
          return history(orderId);
        });
  }

  public Page<OrderView> ordersForOutlet(
      Actor actor, String outletId, Optional<String> cursor, int limit) {
    requireScope(actor, "wpt:order:outlet:" + outletId, "SELECT app.actor_has_outlet(?) AS ok", outletId);
    return read(actor.userId(), () -> page(outletId, cursor, limit));
  }

  public List<DemandView> confirmedDemand(Actor actor, String depotCode, LocalDate serviceDate) {
    requireScope(actor, "wpt:order:depot:" + depotCode, "SELECT app.actor_has_depot(?) AS ok", depotCode);
    return read(
        actor.userId(),
        () -> orders.demand(depotCode, serviceDate).stream().map(OrderDataQuery::toDemand).toList());
  }

  /** What became of every order due at a depot on a day, for the dispatcher's order board. */
  public List<OrderView> ordersForDay(Actor actor, String depotCode, LocalDate serviceDate) {
    requireScope(actor, "wpt:order:depot:" + depotCode, "SELECT app.actor_has_depot(?) AS ok", depotCode);
    return read(
        actor.userId(),
        () -> orders.forDay(depotCode, serviceDate).stream().map(OrderDataQuery::toView).toList());
  }

  /** Keyset directory of orders visible through the actor's SQL scope. */
  public Page<OrderView> adminPage(Actor actor, String depot, LocalDate date, String status,
      String brand, String outlet, String temperature, String search, String after, Integer limit) {
    int size = Page.limit(limit);
    List<String> cursor = Cursor.decode(after, 1);
    UUID key = cursor.isEmpty() ? null : uuidCursor(cursor.get(0));
    return read(actor.userId(), () -> {
      List<UUID> ids = database.query(
          """
          SELECT order_id FROM ordering.orders
           WHERE (?::text IS NULL OR depot_code = ?::text)
             AND (?::date IS NULL OR delivery_date = ?::date)
             AND (?::text IS NULL OR status = ?::text)
             AND (?::text IS NULL OR brand_code = ?::text)
             AND (?::text IS NULL OR outlet_id = ?::text)
             AND (?::text IS NULL OR temperature = ?::text)
             AND (?::text IS NULL OR order_ref ILIKE '%' || ?::text || '%'
                  OR outlet_id ILIKE '%' || ?::text || '%')
             AND (?::uuid IS NULL OR order_id > ?::uuid)
           ORDER BY order_id LIMIT ?
          """, depot, depot, date == null ? null : java.sql.Date.valueOf(date),
          date == null ? null : java.sql.Date.valueOf(date), status, status, brand, brand,
          outlet, outlet, temperature, temperature, search, search, search, key, key, size + 1)
          .stream().map(r -> (UUID) r.get("order_id")).toList();
      List<OrderView> rows = ids.stream().map(id -> orders.findStored(id).map(OrderDataQuery::toView)
          .orElseThrow(() -> new IllegalStateException("Visible order disappeared"))).toList();
      return Page.fromOverfetch(rows, size, row -> Cursor.encode(row.orderId().toString()));
    });
  }

  private static UUID uuidCursor(String value) {
    try { return UUID.fromString(value); } catch (IllegalArgumentException e) { throw Cursor.invalid(); }
  }

  /** The scope half of "policy AND scope" for one outlet: 403 and an audit row when outside it. */
  void requireOutletScope(Actor actor, String outletId) {
    requireScope(actor, "wpt:order:outlet:" + outletId, "SELECT app.actor_has_outlet(?) AS ok", outletId);
  }

  /** Any other Ordering read, as the authenticated actor. */
  public <T> T asActor(Actor actor, java.util.function.Supplier<T> work) {
    return read(actor.userId(), work);
  }

  @Override
  public List<com.waypoint.dispatch.ordering.contract.OrderViews.DailyVolumeView> dailyVolumes(
      String depotCode, String brandCode, LocalDate from, LocalDate to) {
    return read(
        ambient(),
        () ->
            database
                .query(
                    """
                    SELECT (o.placed_at AT TIME ZONE 'Asia/Colombo')::date AS day, count(*) AS n,
                           coalesce(sum(o.volume_m3), 0) AS total,
                           coalesce(sum(o.volume_m3) FILTER (WHERE o.temperature = 'chilled'), 0) AS chilled
                      FROM ordering.orders o
                     WHERE o.depot_code = ? AND o.brand_code = ? AND o.status <> 'cancelled'
                       AND (o.placed_at AT TIME ZONE 'Asia/Colombo')::date BETWEEN ? AND ?
                     GROUP BY 1 ORDER BY 1
                    """,
                    depotCode,
                    brandCode,
                    java.sql.Date.valueOf(from),
                    java.sql.Date.valueOf(to))
                .stream()
                .map(
                    r ->
                        new com.waypoint.dispatch.ordering.contract.OrderViews.DailyVolumeView(
                            ((java.sql.Date) r.get("day")).toLocalDate(),
                            ((Number) r.get("n")).intValue(),
                            (java.math.BigDecimal) r.get("total"),
                            (java.math.BigDecimal) r.get("chilled")))
                .toList());
  }

  /**
   * Orders not yet delivered or ended, by the day they are due. A store-unknown
   * order has no measures yet and adds nothing (R-STK-05).
   */
  @Override
  public List<com.waypoint.dispatch.ordering.contract.OrderViews.BookedVolumeView> bookedVolumes(
      String depotCode, LocalDate from, LocalDate to) {
    return read(
        Actor.SYSTEM_ID,
        () ->
            database
                .query(
                    """
                    SELECT o.delivery_date AS day, o.brand_code AS brand, count(*) AS n,
                           coalesce(sum(o.volume_m3), 0) AS total,
                           coalesce(sum(o.volume_m3) FILTER (WHERE o.temperature = 'chilled'), 0) AS chilled
                      FROM ordering.orders o
                     WHERE o.depot_code = ? AND o.delivery_date BETWEEN ? AND ?
                       AND o.status IN ('confirmed', 'partially_reserved', 'allocated', 'deferred',
                                        'loading', 'in_transit')
                     GROUP BY 1, 2 ORDER BY 1, 2
                    """,
                    depotCode,
                    java.sql.Date.valueOf(from),
                    java.sql.Date.valueOf(to))
                .stream()
                .map(
                    r ->
                        new com.waypoint.dispatch.ordering.contract.OrderViews.BookedVolumeView(
                            ((java.sql.Date) r.get("day")).toLocalDate(),
                            (String) r.get("brand"),
                            ((Number) r.get("n")).intValue(),
                            (java.math.BigDecimal) r.get("total"),
                            (java.math.BigDecimal) r.get("chilled")))
                .toList());
  }

  // ---- internals -----------------------------------------------------------

  private UUID ambient() {
    return database.ambientActor().orElse(null);
  }

  private <T> T read(UUID actorId, java.util.function.Supplier<T> work) {
    return database.readAs(ModuleRole.ORDERING, actorId, work);
  }

  /**
   * Policy allowed {@code order:Read}; this is the scope half of "policy AND
   * scope". The check reads in its own transaction and the denial is audited
   * after it ends, because the audit write cannot join a read-only transaction.
   */
  private void requireScope(Actor actor, String resource, String sql, String key) {
    boolean inScope =
        read(actor.userId(), () -> Boolean.TRUE.equals(database.queryOne(sql, key).get("ok")));
    if (!inScope) {
      String reason = "outside the actor's scope";
      audit.recordStandalone(
          AuditEntry.denied(actor.userId(), actor.deviceId(), READ, resource, reason));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason);
    }
  }

  private List<StatusChangeView> history(UUID orderId) {
    return orders.history(orderId).stream()
        .map(c -> new StatusChangeView(c.from(), c.to(), c.reason(), c.actorId(), c.at()))
        .toList();
  }

  private Page<OrderView> page(String outletId, Optional<String> cursor, int limit) {
    int size = Math.max(1, Math.min(MAX_PAGE, limit));
    Optional<Keyset> after = cursor.filter(c -> !c.isBlank()).map(Keyset::decode);
    List<Stored> rows =
        orders.pageForOutlet(
            outletId,
            after.map(Keyset::placedAt),
            after.map(Keyset::orderId),
            size + 1);
    boolean more = rows.size() > size;
    List<Stored> shown = more ? rows.subList(0, size) : rows;
    Optional<String> next =
        more
            ? Optional.of(
                new Keyset(shown.get(size - 1).placedAt(), shown.get(size - 1).order().orderId())
                    .encode())
            : Optional.empty();
    return new Page<>(withStops(shown.stream().map(OrderDataQuery::toView).toList()), next);
  }

  /** The keyset position; opaque to the caller and free of personal data. */
  record Keyset(Instant placedAt, UUID orderId) {
    String encode() {
      String raw = placedAt.toString() + "|" + orderId;
      return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Keyset decode(String cursor) {
      try {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        int bar = raw.indexOf('|');
        return new Keyset(Instant.parse(raw.substring(0, bar)), UUID.fromString(raw.substring(bar + 1)));
      } catch (RuntimeException e) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "Unreadable cursor");
      }
    }
  }

  static OrderView toView(Stored stored) {
    Order o = stored.order();
    Optional<Reservation> r = o.reservation();
    return new OrderView(
        o.orderId(),
        o.orderRef(),
        o.outletId(),
        o.depotCode(),
        o.brandCode(),
        o.districtName(),
        o.requestedDate(),
        o.deliveryDate(),
        !o.deliveryDate().equals(o.requestedDate()),
        r.map(Reservation::temperature).orElse(null),
        r.map(Reservation::itemCount).orElse(0),
        r.map(Reservation::weightKg).orElse(null),
        r.map(Reservation::volumeM3).orElse(null),
        o.status(),
        r.map(Reservation::warehouseOrderRef),
        o.redeliveryOf(),
        o.deferralCount(),
        stored.placedAt(),
        o.lines().stream().map(l -> new OrderLineView(l.productId(), l.quantity())).toList(),
        o.rowVersion());
  }

  static DemandView toDemand(Order o) {
    Reservation r = o.reservation().orElseThrow();
    return new DemandView(
        o.orderId(),
        o.orderRef(),
        o.outletId(),
        o.brandCode(),
        o.districtName(),
        r.temperature(),
        r.weightKg(),
        r.volumeM3(),
        r.itemCount(),
        o.originalRequestedDate(),
        o.deferralCount(),
        o.rowVersion());
  }
}
