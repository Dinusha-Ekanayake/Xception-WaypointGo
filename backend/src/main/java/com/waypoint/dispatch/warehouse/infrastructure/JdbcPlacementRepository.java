package com.waypoint.dispatch.warehouse.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.warehouse.contract.StockPort.Alternative;
import com.waypoint.dispatch.warehouse.contract.StockPort.LineAvailability;
import com.waypoint.dispatch.warehouse.contract.StockPort.PartiallyReserved;
import com.waypoint.dispatch.warehouse.contract.StockPort.PlacementResult;
import com.waypoint.dispatch.warehouse.contract.StockPort.Reserved;
import com.waypoint.dispatch.warehouse.contract.StockPort.StockLine;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher.Attempt;
import com.waypoint.dispatch.warehouse.domain.Placement;
import com.waypoint.dispatch.warehouse.domain.Placement.State;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code warehouse.placements}. Callers open the transaction (as
 * {@code waypoint_warehouse}); every update is guarded by {@code row_version}.
 *
 * <p>The stored answer is written in this module's own JSON shape, not the
 * warehouse's, so a replay never depends on the external format.
 */
@Component
public class JdbcPlacementRepository {
  private static final String COLUMNS =
      "placement_id, order_ref, order_id, depot_code, warehouse_code, lines, attempts, state,"
          + " compensate_only, warehouse_order_ref, warehouse_status, expires_at, result,"
          + " retry_count, next_attempt_at, created_at, row_version";

  private final Database database;
  private final ObjectMapper mapper;

  public JdbcPlacementRepository(Database database, ObjectMapper mapper) {
    this.database = database;
    this.mapper = mapper;
  }

  public Optional<Placement> findByRef(String orderRef) {
    return one("SELECT " + COLUMNS + " FROM warehouse.placements WHERE order_ref = ?", orderRef);
  }

  public Optional<Placement> findByWarehouseRef(String warehouseOrderRef) {
    return one(
        "SELECT " + COLUMNS + " FROM warehouse.placements WHERE warehouse_order_ref = ?",
        warehouseOrderRef);
  }

  /** The order's own placement: the one that is not an amendment attempt. */
  public Optional<Placement> findForOrder(UUID orderId) {
    return one(
        "SELECT " + COLUMNS + " FROM warehouse.placements WHERE order_id = ?"
            + " AND order_ref NOT LIKE '%#amend-%' ORDER BY created_at LIMIT 1",
        orderId);
  }

  public List<Placement> forOrder(UUID orderId) {
    return many("SELECT " + COLUMNS + " FROM warehouse.placements WHERE order_id = ?", orderId);
  }

  /** Placements the retry job owes a decision, oldest due first. */
  public List<Placement> due(Instant now, int limit) {
    return many(
        "SELECT " + COLUMNS + " FROM warehouse.placements"
            + " WHERE state IN ('queued','unknown','attempting')"
            + " AND (next_attempt_at IS NULL OR next_attempt_at <= ?)"
            + " ORDER BY next_attempt_at NULLS FIRST LIMIT ?",
        Timestamp.from(now), limit);
  }

  /** Warehouse orders Waypoint holds and that can still change. */
  public List<Placement> open(int limit) {
    return many(
        "SELECT " + COLUMNS + " FROM warehouse.placements WHERE state IN ('placed','partial')"
            + " AND coalesce(warehouse_status, '') NOT IN ('delivered','cancelled','expired')"
            + " ORDER BY updated_at LIMIT ?",
        limit);
  }

  public long countAwaiting() {
    Map<String, Object> row =
        database.queryOne(
            "SELECT count(*) AS n FROM warehouse.placements WHERE state IN ('queued','unknown','attempting')");
    return row == null ? 0 : ((Number) row.get("n")).longValue();
  }

  /** Which of these warehouse refs already belong to some Waypoint placement. */
  public Set<String> claimed(Collection<String> warehouseRefs) {
    if (warehouseRefs.isEmpty()) {
      return Set.of();
    }
    Set<String> claimed = new HashSet<>();
    for (Map<String, Object> row :
        database.query(
            "SELECT warehouse_order_ref FROM warehouse.placements WHERE warehouse_order_ref = ANY (?)",
            (Object) warehouseRefs.toArray(String[]::new))) {
      claimed.add((String) row.get("warehouse_order_ref"));
    }
    return claimed;
  }

  public void insert(Placement p, Instant at) {
    database.update(
        "INSERT INTO warehouse.placements (placement_id, order_ref, order_id, depot_code, warehouse_code,"
            + " lines, attempts, state, compensate_only, warehouse_order_ref, warehouse_status, expires_at,"
            + " result, temperature, weight_kg, volume_m3, item_count, retry_count, next_attempt_at,"
            + " created_at, updated_at, row_version)"
            + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
        params(p, at));
  }

  /** @return the new row version; fails on a stale version (rule 6) */
  public long update(Placement p, Instant at) {
    database.updateExpectingOneRow(
        "UPDATE warehouse.placements SET order_id = ?, lines = ?::jsonb, attempts = ?::jsonb, state = ?,"
            + " compensate_only = ?, warehouse_order_ref = ?, warehouse_status = ?, expires_at = ?,"
            + " result = ?::jsonb, temperature = ?, weight_kg = ?, volume_m3 = ?, item_count = ?,"
            + " retry_count = ?, next_attempt_at = ?, updated_at = ?, row_version = row_version + 1"
            + " WHERE placement_id = ? AND row_version = ?",
        updateParams(p, at));
    return p.rowVersion() + 1;
  }

  public void recordError(UUID placementId, String error, Instant at) {
    database.update(
        "UPDATE warehouse.placements SET last_error = ?, updated_at = ? WHERE placement_id = ?",
        error, Timestamp.from(at), placementId);
  }

  // ---- mapping ----------------------------------------------------------------

  private Object[] params(Placement p, Instant at) {
    Optional<Reserved> r = reservedOf(p.result());
    return new Object[] {
      p.placementId(), p.orderRef(), p.orderId(), p.depotCode(), p.warehouseCode(),
      linesJson(p.lines()), attemptsJson(p.attempts()), p.state().code(), p.compensateOnly(),
      p.warehouseOrderRef().orElse(null), p.warehouseStatus().orElse(null),
      p.expiresAt().map(Timestamp::from).orElse(null), resultJson(p.result()),
      r.map(Reserved::temperature).orElse(null), r.map(Reserved::weightKg).orElse(null),
      r.map(Reserved::volumeM3).orElse(null), r.map(x -> (Object) x.itemCount()).orElse(null),
      p.retryCount(), p.nextAttemptAt().map(Timestamp::from).orElse(null),
      Timestamp.from(p.createdAt()), Timestamp.from(at)
    };
  }

  private Object[] updateParams(Placement p, Instant at) {
    Optional<Reserved> r = reservedOf(p.result());
    return new Object[] {
      p.orderId(), linesJson(p.lines()), attemptsJson(p.attempts()), p.state().code(),
      p.compensateOnly(), p.warehouseOrderRef().orElse(null), p.warehouseStatus().orElse(null),
      p.expiresAt().map(Timestamp::from).orElse(null), resultJson(p.result()),
      r.map(Reserved::temperature).orElse(null), r.map(Reserved::weightKg).orElse(null),
      r.map(Reserved::volumeM3).orElse(null), r.map(x -> (Object) x.itemCount()).orElse(null),
      p.retryCount(), p.nextAttemptAt().map(Timestamp::from).orElse(null), Timestamp.from(at),
      p.placementId(), p.rowVersion()
    };
  }

  private static Optional<Reserved> reservedOf(Optional<PlacementResult> result) {
    if (result.isPresent() && result.get() instanceof Reserved r) {
      return Optional.of(r);
    }
    if (result.isPresent() && result.get() instanceof PartiallyReserved p) {
      return Optional.of(p.reservation());
    }
    return Optional.empty();
  }

  private Optional<Placement> one(String sql, Object... params) {
    List<Placement> rows = many(sql, params);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private List<Placement> many(String sql, Object... params) {
    List<Placement> placements = new ArrayList<>();
    for (Map<String, Object> row : database.query(sql, params)) {
      placements.add(map(row));
    }
    return placements;
  }

  private Placement map(Map<String, Object> row) {
    return new Placement(
        (UUID) row.get("placement_id"),
        (String) row.get("order_ref"),
        (UUID) row.get("order_id"),
        (String) row.get("depot_code"),
        (String) row.get("warehouse_code"),
        lines(read(row.get("lines"))),
        attempts(read(row.get("attempts"))),
        State.of((String) row.get("state")),
        Boolean.TRUE.equals(row.get("compensate_only")),
        Optional.ofNullable((String) row.get("warehouse_order_ref")),
        Optional.ofNullable((String) row.get("warehouse_status")),
        Optional.ofNullable((Timestamp) row.get("expires_at")).map(Timestamp::toInstant),
        result(row.get("result") == null ? null : read(row.get("result"))),
        ((Number) row.get("retry_count")).intValue(),
        Optional.ofNullable((Timestamp) row.get("next_attempt_at")).map(Timestamp::toInstant),
        ((Timestamp) row.get("created_at")).toInstant(),
        ((Number) row.get("row_version")).longValue());
  }

  private JsonNode read(Object jsonb) {
    try {
      return mapper.readTree(String.valueOf(jsonb));
    } catch (IOException e) {
      throw new IllegalStateException("warehouse.placements holds JSON that does not parse", e);
    }
  }

  private String linesJson(List<StockLine> lines) {
    return linesNode(lines).toString();
  }

  private ArrayNode linesNode(List<StockLine> lines) {
    ArrayNode array = mapper.createArrayNode();
    for (StockLine l : lines) {
      array.addObject().put("productId", l.productId()).put("quantity", l.quantity());
    }
    return array;
  }

  private static List<StockLine> lines(JsonNode node) {
    List<StockLine> lines = new ArrayList<>();
    for (JsonNode l : node) {
      lines.add(new StockLine(l.path("productId").asText(), l.path("quantity").asInt()));
    }
    return lines;
  }

  private String attemptsJson(List<Attempt> attempts) {
    ArrayNode array = mapper.createArrayNode();
    for (Attempt a : attempts) {
      ObjectNode node = array.addObject();
      node.put("sentAt", a.sentAt().toString());
      node.set("lines", linesNode(a.lines()));
    }
    return array.toString();
  }

  private static List<Attempt> attempts(JsonNode node) {
    List<Attempt> attempts = new ArrayList<>();
    for (JsonNode a : node) {
      attempts.add(new Attempt(lines(a.path("lines")), Instant.parse(a.path("sentAt").asText())));
    }
    return attempts;
  }

  private String resultJson(Optional<PlacementResult> result) {
    if (result.isEmpty()) {
      return null;
    }
    ObjectNode node = mapper.createObjectNode();
    if (result.get() instanceof Reserved r) {
      node.put("kind", "reserved");
      node.set("reservation", reservedNode(r));
    } else if (result.get() instanceof PartiallyReserved p) {
      node.put("kind", "partial");
      node.set("reservation", reservedNode(p.reservation()));
      node.put("expiresAt", p.expiresAt().toString());
      ArrayNode lines = node.putArray("lines");
      for (LineAvailability l : p.lines()) {
        lines.addObject().put("productId", l.productId()).put("requested", l.requested())
            .put("available", l.available());
      }
      ArrayNode alternatives = node.putArray("alternatives");
      for (Alternative a : p.alternatives()) {
        alternatives.addObject().put("productId", a.productId()).put("warehouse", a.warehouse())
            .put("available", a.available());
      }
    } else {
      return null;
    }
    return node.toString();
  }

  private ObjectNode reservedNode(Reserved r) {
    return mapper.createObjectNode()
        .put("warehouseOrderRef", r.warehouseOrderRef())
        .put("weightKg", r.weightKg().toPlainString())
        .put("volumeM3", r.volumeM3().toPlainString())
        .put("temperature", r.temperature())
        .put("itemCount", r.itemCount());
  }

  private static Reserved reserved(JsonNode n) {
    return new Reserved(
        n.path("warehouseOrderRef").asText(),
        new BigDecimal(n.path("weightKg").asText()),
        new BigDecimal(n.path("volumeM3").asText()),
        n.path("temperature").asText(),
        n.path("itemCount").asInt());
  }

  private static Optional<PlacementResult> result(JsonNode node) {
    if (node == null || node.isNull() || !node.has("kind")) {
      return Optional.empty();
    }
    Reserved reservation = reserved(node.path("reservation"));
    if ("reserved".equals(node.path("kind").asText())) {
      return Optional.of(reservation);
    }
    List<LineAvailability> lines = new ArrayList<>();
    for (JsonNode l : node.path("lines")) {
      lines.add(new LineAvailability(
          l.path("productId").asText(), l.path("requested").asInt(), l.path("available").asInt()));
    }
    List<Alternative> alternatives = new ArrayList<>();
    for (JsonNode a : node.path("alternatives")) {
      alternatives.add(new Alternative(
          a.path("productId").asText(), a.path("warehouse").asText(), a.path("available").asInt()));
    }
    return Optional.of(new PartiallyReserved(
        reservation, Instant.parse(node.path("expiresAt").asText()), lines, alternatives));
  }
}
