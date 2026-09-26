package com.waypoint.dispatch.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waypoint.dispatch.domain.Planning;
import com.waypoint.dispatch.domain.ReferenceData;
import com.waypoint.dispatch.domain.ReferenceLoader;
import com.waypoint.dispatch.util.Crypto;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Business logic port of lib/service.ts. Orders and plans are stored as JSONB
 * and manipulated as Jackson {@link ObjectNode}s so the wire shapes stay
 * identical to the Next.js implementation.
 */
@Service
public class DispatchService {
  private static final List<Scenario> SCENARIOS = List.of(
      new Scenario("2026-02-14", "Original walkthrough",
          "The original order-to-receipt journey, plus the labelled oversized order.", "historical"),
      new Scenario("2026-02-09", "Delivery outcomes",
          "Historical orders with simulated loading, road, partial delivery, failure, receipt and dispute states.",
          "simulated"),
      new Scenario("2026-02-12", "Mixed brands & mall windows",
          "151 original orders, including 11 Style deliveries and 59 chilled orders, across both depots.",
          "historical"),
      new Scenario("2026-02-13", "Heavy & chilled demand",
          "135 original orders: Fresh, Style and Tech, with 45 chilled orders and both depots.",
          "historical"),
      new Scenario("2026-02-16", "Peak day · workshop shortage",
          "All 85 supplied S1 orders; only the 28 available scenario vehicles can run. Ten are in the workshop. Assigned this scenario date; validated with standard planning rules.",
          "source scenario"),
      new Scenario("2026-02-17", "Weekly fuel pressure",
          "A copy of S1 demand with an opening fuel balance leaving 2 litres per vehicle. Other published trips in the week also consume this balance.",
          "simulated"),
      new Scenario("2026-02-18", "Constraint boundaries",
          "Small, labelled fixtures for independent weight and volume overloads, impossible windows, chilled van access, and mall handling.",
          "synthetic"));

  public record Scenario(String day, String name, String description, String kind) {}
  public record User(String id, String role, String scope) {}
  public record LoginResult(String token, Map<String, String> user) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate serializable;
  private final ReferenceLoader loader;
  private final ObjectMapper mapper;
  private final boolean demo;
  private final String demoNow;
  private final String seedPassword;
  private volatile Map<String, String> fleetAvailability;

  public DispatchService(
      JdbcTemplate jdbc,
      TransactionTemplate serializableTransactions,
      ReferenceLoader loader,
      ObjectMapper mapper,
      @Value("${app.demo-mode:1}") String demoMode,
      @Value("${app.demo-now:2026-02-13T15:30:00+05:30}") String demoNow,
      @Value("${app.seed-password:}") String seedPassword) {
    this.jdbc = jdbc;
    this.serializable = serializableTransactions;
    this.loader = loader;
    this.mapper = mapper;
    this.demo = !"0".equals(demoMode);
    this.demoNow = demoNow;
    this.seedPassword = seedPassword == null ? "" : seedPassword;
  }

  // ---------- low-level query helpers ----------

  public List<Map<String, Object>> all(String sql, Object... params) {
    return jdbc.queryForList(sql, params);
  }

  public Map<String, Object> get(String sql, Object... params) {
    List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public void run(String sql, Object... params) {
    jdbc.update(sql, params);
  }

  public <T> T transaction(java.util.function.Supplier<T> fn) {
    for (int attempt = 0; ; attempt++) {
      try {
        return serializable.execute(status -> fn.get());
      } catch (DataAccessException e) {
        if (attempt < 4 && isRetryable(e)) {
          sleep(20L * (attempt + 1));
          continue;
        }
        throw e;
      }
    }
  }

  public void transaction(Runnable fn) {
    transaction(() -> {
      fn.run();
      return null;
    });
  }

  private static boolean isRetryable(DataAccessException e) {
    Throwable t = e;
    while (t != null) {
      if (t instanceof java.sql.SQLException sql) {
        String state = sql.getSQLState();
        if ("40001".equals(state) || "40P01".equals(state)) return true;
        if ("23505".equals(state)
            && sql.getMessage() != null
            && sql.getMessage().contains("commands_pkey")) return true;
      }
      String msg = t.getMessage();
      if (msg != null && msg.contains("commands_pkey")
          && msg.contains("duplicate key")) return true;
      t = t.getCause();
    }
    return false;
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  static String colToString(Object value) {
    if (value instanceof PGobject pg) return pg.getValue();
    if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
    return value == null ? null : String.valueOf(value);
  }

  private ObjectNode parseBody(Object raw) {
    try {
      return (ObjectNode) mapper.readTree(colToString(raw));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored JSON", e);
    }
  }

  // ---------- small validators (messages preserved) ----------

  static String text(Object v, int max) {
    if (!(v instanceof String s)) throw new DomainException("A non-empty explanation is required.");
    String t = s.trim();
    if (t.isEmpty() || t.length() > max) {
      throw new DomainException("A non-empty explanation is required.");
    }
    return t;
  }

  static String text(Object v) {
    return text(v, 500);
  }

  static double number(Object v, double min, double max, boolean integer) {
    if (!(v instanceof Number n)) throw new DomainException("Invalid quantity.");
    double d = n.doubleValue();
    if (!Double.isFinite(d) || d < min || d > max) throw new DomainException("Invalid quantity.");
    if (integer && Math.floor(d) != d) throw new DomainException("Invalid quantity.");
    return d;
  }

  static double number(Object v, double min) {
    return number(v, min, 1e6, false);
  }

  static String imageData(Object v) {
    if (!(v instanceof String s)
        || s.length() >= 1_500_000
        || !s.matches("^data:image/(png|jpeg);base64,[A-Za-z0-9+/]+=*$")) {
      throw new DomainException("Use a PNG/JPEG image smaller than 1 MB.");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(s.substring(s.indexOf(',') + 1));
    } catch (Exception e) {
      throw new DomainException("Invalid image format.");
    }
    boolean png = bytes.length >= 8
        && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
        && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
    boolean jpeg = bytes.length >= 3
        && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
    if (!png && !jpeg) throw new DomainException("Invalid image format.");
    return s;
  }

  // ---------- time / reference ----------

  public String now() {
    return demo ? demoNow : Instant.now().toString();
  }

  public boolean isDemo() {
    return demo;
  }

  public ReferenceData reference(String day) {
    return Planning.dayReference(loader.get(), day, demo, availability());
  }

  private Map<String, String> availability() {
    Map<String, String> cached = fleetAvailability;
    if (cached != null) return cached;
    Map<String, String> map = new HashMap<>();
    try {
      for (Map<String, String> row : loader.dataset("Test Data/task2b_peak_day_fleet.csv")) {
        map.put(row.get("vehicle_id"), row.get("status"));
      }
    } catch (Exception ignored) {
      // Scenario file missing: every vehicle reads as not_in_scenario.
    }
    fleetAvailability = map;
    return map;
  }

  // ---------- persistence ----------

  /** Strict write path: validates images, stores bytes separately, upserts the order. */
  public void save(ObjectNode order) {
    if (!order.hasNonNull("id")) throw new DomainException("Invalid order record.");
    ObjectNode stored = order.deepCopy();
    List<Object[]> images = new ArrayList<>();
    JsonNode proof = stored.get("proof");
    if (proof instanceof ObjectNode proofObj) {
      for (String kind : new String[] {"photo", "signature"}) {
        JsonNode data = proofObj.get(kind);
        if (data == null || data.isNull()) continue;
        String dataUrl = imageData(data.asText());
        byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));
        String id = Crypto.sha256Hex(stored.get("id").asText() + ":" + kind + ":" + dataUrl);
        String mime = dataUrl.substring(5, dataUrl.indexOf(';'));
        images.add(new Object[] {id, stored.get("id").asText(), kind, mime, bytes});
        proofObj.put(kind + "_id", id);
        proofObj.remove(kind);
      }
    }
    String id = stored.get("id").asText();
    String body;
    try {
      body = mapper.writeValueAsString(stored);
    } catch (Exception e) {
      if (e instanceof DataAccessException databaseError) throw databaseError;
        throw new IllegalStateException(e);
    }
    jdbc.update(
        "INSERT INTO orders(id,body) VALUES(?,?::jsonb) ON CONFLICT(id) DO UPDATE SET body=excluded.body",
        id, body);
    for (Object[] image : images) {
      jdbc.update(
          "INSERT INTO proof_images(id,order_id,kind,content_type,bytes) VALUES(?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
          image);
    }
  }

  /** Creation never updates a pre-existing order, even if an identifier collides. */
  public void insertOrder(ObjectNode order) {
    run("INSERT INTO orders(id,body) VALUES(?,?::jsonb)", optText(order, "id"), order.toString());
  }

  public Map<String, String> proofImage(User user, String orderId, String imageId) {
    return transaction(() -> {
      Map<String, Object> record = get("SELECT body FROM orders WHERE id=?", orderId);
      if (record == null) throw new DomainException("Order not found.", 404);
      ObjectNode order = parseBody(record.get("body"));
      if (!allowed(user, order)) throw new DomainException("Record is outside your assignment.", 403);
      JsonNode proof = order.get("proof");
      String photoId = proof != null ? optText(proof, "photo_id") : null;
      String sigId = proof != null ? optText(proof, "signature_id") : null;
      if (!imageId.equals(photoId) && !imageId.equals(sigId)) {
        throw new DomainException("Proof not found.", 404);
      }
      Map<String, Object> image =
          get("SELECT content_type,bytes FROM proof_images WHERE id=? AND order_id=?", imageId, orderId);
      if (image == null || !(image.get("bytes") instanceof byte[] bytes)) {
        throw new DomainException("Proof not found.", 404);
      }
      String contentType = String.valueOf(image.get("content_type"));
      return Map.of("data",
          "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes));
    });
  }

  public void checkLoginRate(String email) {
    boolean accepted = transaction(() -> {
      String key = email.toLowerCase();
      if (key.length() > 200) key = key.substring(0, 200);
      long now = System.currentTimeMillis();
      jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "login:" + key);
      jdbc.update("DELETE FROM login_attempts WHERE attempted_at<?", now - 60_000);
      Map<String, Object> row = get(
          "SELECT count(*) AS count FROM login_attempts WHERE email=? AND attempted_at>=?", key,
          now - 60_000);
      long count = row == null ? 0 : ((Number) row.get("count")).longValue();
      if (count >= 15) return false;
      jdbc.update("INSERT INTO login_attempts VALUES(?,?)", key, now);
      return true;
    });
    if (!accepted) throw new DomainException("Too many attempts. Wait one minute.", 429);
  }

  public List<ObjectNode> orders() {
    return orders((User) null);
  }

  public List<ObjectNode> orders(User user) {
    String column = null;
    if (user != null) {
      column = switch (user.role()) {
        case "store" -> "outlet_id";
        case "loader" -> "depot";
        case "driver" -> "vehicle_id";
        default -> null;
      };
    }
    List<Map<String, Object>> rows = column == null
        ? all("SELECT body FROM orders ORDER BY id")
        : all("SELECT body FROM orders WHERE " + column + "=? ORDER BY id", user.scope());
    List<ObjectNode> out = new ArrayList<>();
    for (Map<String, Object> row : rows) out.add(parseBody(row.get("body")));
    return out;
  }

  public List<ObjectNode> plans() {
    List<ObjectNode> out = new ArrayList<>();
    for (Map<String, Object> row : all("SELECT body FROM plans ORDER BY day")) {
      out.add(parseBody(row.get("body")));
    }
    return out;
  }

  // ---------- seed ----------

  public void seed() {
    if (!demo) throw new DomainException("Demo seeding requires DEMO_MODE=1. Use account commands for production.");
    if (seedPassword.length() < 12) {
      throw new DomainException(
          "Set SEED_PASSWORD to at least 12 characters before seeding.");
    }
    transaction(() -> {
      run("LOCK TABLE settings IN EXCLUSIVE MODE");
      if (get("SELECT 1 FROM settings WHERE key=?", "seeded") != null) return null;
      ReferenceData ref = loader.get();
      Map<String, ReferenceData.Outlet> outlets = new HashMap<>();
      for (ReferenceData.Outlet o : ref.outlets()) outlets.put(o.outletId(), o);
      List<ObjectNode> orders = new ArrayList<>();
      for (Map<String, String> r : loader.dataset("Training Data/deliveries_train.csv")) {
        if (!"2026-02-14".equals(r.get("order_date"))) continue;
        ReferenceData.Outlet outlet = outlets.get(r.get("outlet_id"));
        if (outlet == null) throw new IllegalStateException("Unknown outlet " + r.get("outlet_id"));
        ObjectNode o = mapper.createObjectNode();
        putOutlet(o, outlet);
        o.put("id", r.get("delivery_id"));
        o.put("day", "2026-02-14");
        o.put("weight", Double.parseDouble(r.get("order_weight_kg")));
        o.put("volume", Double.parseDouble(r.get("order_volume_m3")));
        o.put("units", Integer.parseInt(r.get("order_units")));
        o.put("temp", r.get("temp_requirement"));
        o.put("status", "confirmed_order");
        o.put("version", 0);
        o.put("skips", 0);
        orders.add(o);
      }
      ReferenceData.Outlet sample = outlets.get("OUT001");
      if (sample == null) throw new IllegalStateException("Missing outlet OUT001");
      ObjectNode overload = mapper.createObjectNode();
      putOutlet(overload, sample);
      overload.put("id", "OVERLOAD-001");
      overload.put("day", "2026-02-14");
      overload.put("weight", 1150);
      overload.put("volume", 6.8);
      overload.put("units", 115);
      overload.put("temp", "chilled");
      overload.put("status", "confirmed_order");
      overload.put("version", 0);
      overload.put("skips", 1);
      ObjectNode source = mapper.createObjectNode();
      source.put("file", "Walkthrough capacity fixture");
      source.put("order_ref", "OVERLOAD-001");
      source.put("kind", "synthetic");
      overload.set("source", source);
      orders.add(overload);

      ObjectNode plan = Planning.allocate(orders, ref, "2026-02-14", new HashMap<>(), mapper);
      String targetId = null;
      for (ObjectNode o : orders) {
        if ("OUT001".equals(optText(o, "outlet_id")) && !"OVERLOAD-001".equals(optText(o, "id"))) {
          targetId = optText(o, "id");
          break;
        }
      }
      String vehicle = null;
      ArrayNode routes = (ArrayNode) plan.get("routes");
      for (JsonNode r : routes) {
        for (JsonNode id : r.get("order_ids")) {
          if (id.asText().equals(targetId)) vehicle = r.get("vehicle_id").asText();
        }
      }
      for (ObjectNode o : orders) save(o);
      String[][] accounts = {
          {"dispatcher", "all"}, {"loader", "Peliyagoda"}, {"driver", vehicle}, {"store", "OUT001"}};
      for (String[] account : accounts) {
        String salt = Crypto.randomHex(16);
        run("INSERT INTO users VALUES(?,?,?,?,?)", account[0] + "@waypoint.local", account[0],
            account[1], salt, Crypto.passwordHashHex(seedPassword, salt));
      }
      run("INSERT INTO settings VALUES(?,?)", "seeded", "1");
      return null;
    });
    seedScenarios();
  }

  private void putOutlet(ObjectNode o, ReferenceData.Outlet outlet) {
    o.put("outlet_id", outlet.outletId());
    o.put("brand", outlet.brand());
    o.put("district", outlet.district());
    o.put("depot", outlet.depot());
    o.put("dock_type", outlet.dockType());
    o.put("parking_constraint", outlet.parkingConstraint());
    o.put("mall_window", outlet.mallWindow());
    o.put("window_open_time", outlet.windowOpenTime());
    o.put("window_close_time", outlet.windowCloseTime());
  }

  public void seedScenarios() {
    if (!demo) return;
    transaction(() -> {
      run("LOCK TABLE settings IN EXCLUSIVE MODE");
      if (get("SELECT 1 FROM settings WHERE key=?", "scenario_seed_v2") != null) return null;
      ReferenceData ref = loader.get();
      Map<String, ReferenceData.Outlet> outlets = new HashMap<>();
      for (ReferenceData.Outlet o : ref.outlets()) outlets.put(o.outletId(), o);
      List<Map<String, String>> historical = loader.dataset("Training Data/deliveries_train.csv");
      List<Map<String, String>> peak = loader.dataset("Test Data/task2b_peak_day_scenarios.csv");
      Set<String> existingDays = new TreeSet<>();
      for (ObjectNode o : orders()) existingDays.add(optText(o, "day"));
      for (ObjectNode p : plans()) existingDays.add(optText(p, "day"));
      List<String> installed = new ArrayList<>(List.of("2026-02-14"));
      for (Scenario spec : SCENARIOS) {
        if ("2026-02-14".equals(spec.day()) || existingDays.contains(spec.day())) continue;
        List<ObjectNode> rows = new ArrayList<>();
        if (List.of("2026-02-09", "2026-02-12", "2026-02-13").contains(spec.day())) {
          for (Map<String, String> r : historical) {
            if (spec.day().equals(r.get("order_date"))) rows.add(fromHistoryRow(r, outlets, spec));
          }
        }
        if (List.of("2026-02-16", "2026-02-17").contains(spec.day())) {
          for (Map<String, String> r : peak) {
            rows.add(fromPeakRow(r, outlets, spec));
          }
        }
        if ("2026-02-09".equals(spec.day())) {
          ObjectNode base = null;
          for (ObjectNode o : rows) {
            if ("OUT001".equals(optText(o, "outlet_id"))
                && "ambient".equals(optText(o, "temp"))) {
              base = o;
              break;
            }
          }
          if (base != null) {
            for (int i = 0; i < 8; i++) {
              ObjectNode extra = base.deepCopy();
              extra.put("id", "EXTRA-OUT001-" + (i + 1));
              extra.put("units", 8);
              extra.put("weight", 40);
              extra.put("volume", 0.2);
              ObjectNode src = mapper.createObjectNode();
              src.put("file", "Delivery outcome fixture");
              src.put("order_ref", optText(base, "id"));
              src.put("kind", "simulated");
              extra.set("source", src);
              rows.add(extra);
            }
          }
        }
        if ("2026-02-18".equals(spec.day())) {
          ReferenceData.Outlet outlet001 = outlets.get("OUT001");
          if (outlet001 == null) throw new IllegalStateException("Missing outlet OUT001");
          ObjectNode ambient = mapper.createObjectNode();
          putOutlet(ambient, outlet001);
          ambient.put("day", spec.day());
          ambient.put("id", "EDGE-BASE");
          ambient.put("units", 10);
          ambient.put("weight", 50);
          ambient.put("volume", 0.3);
          ambient.put("temp", "ambient");
          ambient.put("status", "confirmed_order");
          ambient.put("version", 0);
          ambient.put("skips", 0);
          ObjectNode src = mapper.createObjectNode();
          src.put("file", "Constraint boundary fixtures");
          src.put("order_ref", "OUT001");
          src.put("kind", "synthetic");
          ambient.set("source", src);
          ReferenceData.Outlet mall = null;
          for (ReferenceData.Outlet o : ref.outlets()) {
            if ("Style".equals(o.brand()) && !o.mallWindow().isEmpty()) {
              mall = o;
              break;
            }
          }
          double maxWeight = ref.vehicles().stream().mapToDouble(ReferenceData.Vehicle::weightCapKg).max().orElse(0);
          double maxVolume = ref.vehicles().stream().mapToDouble(ReferenceData.Vehicle::volumeCapM3).max().orElse(0);
          List<ObjectNode> edges = new ArrayList<>();
          ObjectNode w = ambient.deepCopy();
          w.put("id", "EDGE-WEIGHT");
          w.put("weight", maxWeight + 1);
          edges.add(w);
          ObjectNode v = ambient.deepCopy();
          v.put("id", "EDGE-VOLUME");
          v.put("volume", maxVolume + 1);
          edges.add(v);
          ObjectNode win = ambient.deepCopy();
          win.put("id", "EDGE-WINDOW");
          win.put("window_open_time", "03:00");
          win.put("window_close_time", "03:05");
          edges.add(win);
          ObjectNode chilled = ambient.deepCopy();
          chilled.put("id", "EDGE-CHILLED-VAN");
          chilled.put("temp", "chilled");
          edges.add(chilled);
          if (mall != null) {
            ObjectNode m = ambient.deepCopy();
            putOutlet(m, mall);
            m.put("id", "EDGE-MALL");
            ObjectNode ms = mapper.createObjectNode();
            ms.put("file", "Constraint boundary fixtures");
            ms.put("order_ref", mall.outletId());
            ms.put("kind", "synthetic");
            m.set("source", ms);
            edges.add(m);
            ObjectNode mh = ambient.deepCopy();
            putOutlet(mh, mall);
            mh.put("id", "EDGE-MALL-HANDLING");
            mh.put("window_open_time", "09:00");
            mh.put("window_close_time", "09:05");
            mh.put("mall_window", "09:00-09:05");
            ObjectNode mhs = mapper.createObjectNode();
            mhs.put("file", "Constraint boundary fixtures");
            mhs.put("order_ref", mall.outletId());
            mhs.put("kind", "synthetic");
            mh.set("source", mhs);
            edges.add(mh);
          }
          rows = edges;
        }
        for (ObjectNode o : rows) save(o);
        installed.add(spec.day());
      }
      try {
        run("INSERT INTO settings VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            "scenario_days", mapper.writeValueAsString(installed));
      } catch (Exception e) {
        if (e instanceof DataAccessException databaseError) throw databaseError;
        throw new IllegalStateException(e);
      }
      if (installed.contains("2026-02-09")) seedOutcomes();
      run("INSERT INTO settings VALUES(?,?)", "scenario_seed_v2", "1");
      return null;
    });
  }

  private ObjectNode fromHistoryRow(
      Map<String, String> r, Map<String, ReferenceData.Outlet> outlets, Scenario spec) {
    ReferenceData.Outlet outlet = outlets.get(r.get("outlet_id"));
    if (outlet == null) throw new IllegalStateException("Unknown outlet " + r.get("outlet_id"));
    ObjectNode o = mapper.createObjectNode();
    putOutlet(o, outlet);
    o.put("id", r.get("delivery_id"));
    o.put("day", spec.day());
    o.put("weight", Double.parseDouble(r.get("order_weight_kg")));
    o.put("volume", Double.parseDouble(r.get("order_volume_m3")));
    o.put("units", Integer.parseInt(r.get("order_units")));
    o.put("temp", r.get("temp_requirement"));
    o.put("status", "confirmed_order");
    o.put("version", 0);
    o.put("skips", Integer.parseInt(r.getOrDefault("deferred_yesterday", "0")));
    String dsls = r.getOrDefault("days_since_last_served", "0");
    if (!dsls.isEmpty()) {
      try {
        o.put("days_since_last_served", Integer.parseInt(dsls));
      } catch (NumberFormatException ignored) {}
    }
    ObjectNode src = mapper.createObjectNode();
    src.put("file", "Training Data/deliveries_train.csv");
    src.put("order_ref", r.getOrDefault("delivery_id", o.get("id").asText()));
    src.put("kind", "historical");
    o.set("source", src);
    return o;
  }

  private ObjectNode fromPeakRow(
      Map<String, String> r, Map<String, ReferenceData.Outlet> outlets, Scenario spec) {
    ReferenceData.Outlet outlet = outlets.get(r.get("outlet_id"));
    if (outlet == null) throw new IllegalStateException("Unknown outlet " + r.get("outlet_id"));
    ObjectNode o = mapper.createObjectNode();
    putOutlet(o, outlet);
    String prefix = spec.day().endsWith("16") ? "PEAK-" : "FUEL-";
    o.put("id", prefix + r.get("order_ref"));
    o.put("day", spec.day());
    o.put("weight", Double.parseDouble(r.get("order_weight_kg")));
    o.put("volume", Double.parseDouble(r.get("order_volume_m3")));
    o.put("units", Integer.parseInt(r.get("order_units")));
    o.put("temp", r.get("temp_requirement"));
    o.put("status", "confirmed_order");
    o.put("version", 0);
    o.put("skips", Integer.parseInt(r.getOrDefault("deferred_yesterday", "0")));
    String dsls = r.getOrDefault("days_since_last_served", "0");
    if (!dsls.isEmpty()) {
      try {
        o.put("days_since_last_served", Integer.parseInt(dsls));
      } catch (NumberFormatException ignored) {}
    }
    ObjectNode src = mapper.createObjectNode();
    src.put("file", "Test Data/task2b_peak_day_scenarios.csv");
    src.put("order_ref", r.getOrDefault("order_ref", o.get("id").asText()));
    src.put("kind", "source scenario");
    o.set("source", src);
    return o;
  }

  private void seedOutcomes() {
    String day = "2026-02-09";
    User dispatcher = new User("dispatcher@waypoint.local", "dispatcher", "all");
    List<ObjectNode> rows = new ArrayList<>();
    for (ObjectNode o : orders()) {
      if (day.equals(optText(o, "day"))) rows.add(o);
    }
    ObjectNode plan;
    try {
      plan = Planning.allocate(rows, loader.get(), day, reservations(day, null), mapper);
    } catch (Exception e) {
      if (e instanceof DataAccessException databaseError) throw databaseError;
        throw new IllegalStateException(e);
    }
    for (JsonNode d : plan.get("deferred")) {
      ((ObjectNode) d).put("justification",
          "No fit in this assisted plan; dispatcher to review next eligible run.");
    }
    try {
      run("INSERT INTO plans VALUES(?,?::jsonb)", day, mapper.writeValueAsString(plan));
    } catch (Exception e) {
      if (e instanceof DataAccessException databaseError) throw databaseError;
        throw new IllegalStateException(e);
    }
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("id", "seed-publish-" + day);
    publish.put("kind", "publish");
    publish.put("day", day);
    publish.put("client_time", day + "T06:00:00+05:30");
    apply(dispatcher, publish);

    List<String> assigned = new ArrayList<>();
    ObjectNode published = null;
    for (ObjectNode p : plans()) {
      if (day.equals(optText(p, "day"))) published = p;
    }
    if (published == null) return;
    for (JsonNode r : published.get("routes")) {
      for (JsonNode id : r.get("order_ids")) assigned.add(id.asText());
    }
    List<String> showcase = assigned.stream().filter(id -> id.startsWith("SHOW-")).toList();
    List<String> rest = assigned.stream().filter(id -> !id.startsWith("SHOW-")).toList();
    List<String> targets = new ArrayList<>(showcase);
    targets.addAll(rest);
    targets = targets.subList(0, Math.min(7, targets.size()));
    Set<String> chosenRoutes = new LinkedHashSet<>();
    for (String id : targets) {
      ObjectNode o = fresh(id);
      if (o != null) chosenRoutes.add(optText(o, "route_id"));
    }
    for (JsonNode r : published.get("routes")) {
      if (!chosenRoutes.contains(r.get("id").asText())) continue;
      for (JsonNode id : r.get("order_ids")) {
        ObjectNode o = fresh(id.asText());
        if (o == null) continue;
        Map<String, Object> cmd = seedCmd("load", day, Map.of("order_id", id.asText(),
            "version", o.get("version").asInt()));
        User loader = new User("loader@waypoint.local", "loader", optText(o, "depot"));
        apply(loader, cmd);
      }
    }
    String photo = dataUrl("../public/assets/proof-sample.png");
    String signature = dataUrl("../public/assets/signature-sample.png");
    String[] goals = {"departed", "arrived", "delivered", "partial", "failed", "confirmed", "disputed"};
    for (int i = 0; i < targets.size(); i++) {
      String id = targets.get(i);
      String goal = goals[i];
      act(id, "depart", Map.of());
      if ("departed".equals(goal)) continue;
      act(id, "arrive", Map.of());
      if ("arrived".equals(goal)) continue;
      ObjectNode o = fresh(id);
      String outcome = "partial".equals(goal) ? "partial" : "failed".equals(goal) ? "failed" : "delivered";
      int count = "failed".equals(outcome) ? 0
          : "partial".equals(outcome) ? Math.max(1, o.get("units").asInt() - 1) : o.get("units").asInt();
      String note = "failed".equals(outcome) ? "Outlet shutter closed, no receiving staff."
          : "partial".equals(outcome) ? "One damaged case, retained on vehicle."
              : "Photo proof attached at handover.";
      Map<String, Object> extra = new LinkedHashMap<>();
      extra.put("outcome", outcome);
      extra.put("count", count);
      extra.put("receiver", "Store receiver");
      extra.put("note", note);
      extra.put("photo", photo);
      extra.put("signature", signature);
      act(id, "deliver", extra);
      ObjectNode saved = fresh(id);
      ObjectNode proof = (ObjectNode) saved.get("proof");
      proof.put("demo", true);
      save(saved);
      if ("confirmed".equals(goal)) act(id, "receive", Map.of());
      if ("disputed".equals(goal)) act(id, "dispute", Map.of("note",
          "Receipt discrepancy: store counted one fewer case."));
    }
    List<ObjectNode> untouched = new ArrayList<>();
    for (ObjectNode o : orders()) {
      if (day.equals(optText(o, "day")) && "planned".equals(optText(o, "status"))) untouched.add(o);
    }
    if (untouched.size() > 0) {
      ObjectNode first = untouched.get(0);
      act(first.get("id").asText(), "shortfall",
          Map.of("count", 1, "note", "Damaged carton found during loading."));
    }
    if (untouched.size() > 1) {
      // Reload second in case the first action changed versions (it targets another order).
      ObjectNode second = fresh(untouched.get(1).get("id").asText());
      if (second != null && "planned".equals(optText(second, "status"))) {
        act(second.get("id").asText(), "load", Map.of());
      }
    }
  }

  private Map<String, Object> seedCmd(String kind, String day, Map<String, Object> data) {
    Map<String, Object> cmd = new LinkedHashMap<>();
    cmd.put("id", "seed-" + kind + "-" + data.getOrDefault("order_id", day));
    cmd.put("kind", kind);
    cmd.put("day", day);
    cmd.put("client_time", day + "T06:00:00+05:30");
    cmd.putAll(data);
    return cmd;
  }

  private void act(String id, String kind, Map<String, Object> extra) {
    ObjectNode o = fresh(id);
    if (o == null) throw new IllegalStateException("Missing order " + id);
    String role = switch (kind) {
      case "load", "shortfall" -> "loader";
      case "depart", "arrive", "deliver" -> "driver";
      case "receive", "dispute" -> "store";
      default -> throw new IllegalStateException("Unknown kind " + kind);
    };
    String scope = "loader".equals(role) ? optText(o, "depot")
        : "driver".equals(role) ? optText(o, "vehicle_id") : optText(o, "outlet_id");
    User user = new User(role + "@waypoint.local", role, scope);
    Map<String, Object> cmd = new LinkedHashMap<>();
    cmd.put("id", "seed-" + kind + "-" + id + "-" + System.nanoTime());
    cmd.put("kind", kind);
    cmd.put("order_id", id);
    cmd.put("version", o.get("version").asInt());
    cmd.putAll(extra);
    apply(user, cmd);
  }

  private ObjectNode fresh(String id) {
    Map<String, Object> row = get("SELECT body FROM orders WHERE id=?", id);
    return row == null ? null : parseBody(row.get("body"));
  }

  private String dataUrl(String relative) {
    try {
      java.nio.file.Path base = loader.getDataDir().getParent();
      java.nio.file.Path file = base.resolve("frontend/" + relative.replaceFirst("^\\.\\./", ""));
      byte[] bytes = java.nio.file.Files.readAllBytes(file);
      return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    } catch (Exception e) {
      throw new IllegalStateException("Missing proof fixture: " + relative, e);
    }
  }

  // ---------- auth ----------

  public boolean allowed(User u, ObjectNode o) {
    return "dispatcher".equals(u.role())
        || ("loader".equals(u.role()) && optText(o, "depot").equals(u.scope()))
        || ("driver".equals(u.role()) && optText(o, "vehicle_id").equals(u.scope()))
        || ("store".equals(u.role()) && optText(o, "outlet_id").equals(u.scope()));
  }

  public LoginResult login(Object email, Object password) {
    if (!(email instanceof String) || !(password instanceof String)
        || ((String) password).length() >= 200) {
      throw new DomainException("Invalid credentials.", 401);
    }
    String id = ((String) email).toLowerCase();
    Map<String, Object> row = get("SELECT * FROM users WHERE id=?", id);
    String salt = row == null ? "missing-user-salt" : String.valueOf(row.get("salt"));
    byte[] candidate = Crypto.passwordHash((String) password, salt);
    boolean ok = row != null
        && Crypto.timingSafeEqual(candidate, Crypto.fromHex(String.valueOf(row.get("hash"))));
    if (!ok) throw new DomainException("Invalid email or password.", 401);
    String token = Crypto.randomHex(40);
    run("DELETE FROM sessions WHERE expires<?", System.currentTimeMillis());
    run("INSERT INTO sessions VALUES(?,?,?)", Crypto.sha256Hex(token),
        String.valueOf(row.get("id")), System.currentTimeMillis() + 86_400_000L);
    Map<String, String> user = new LinkedHashMap<>();
    user.put("id", String.valueOf(row.get("id")));
    user.put("role", String.valueOf(row.get("role")));
    user.put("scope", String.valueOf(row.get("scope")));
    return new LoginResult(token, user);
  }

  public User session(String token) {
    if (token == null || token.isEmpty()) throw new DomainException("Please sign in again.", 401);
    Map<String, Object> row = get(
        "SELECT u.id,u.role,u.scope FROM sessions s JOIN users u ON s.user_id=u.id WHERE s.token=? AND s.expires>?",
        Crypto.sha256Hex(token), System.currentTimeMillis());
    if (row == null) throw new DomainException("Please sign in again.", 401);
    return new User(String.valueOf(row.get("id")), String.valueOf(row.get("role")),
        String.valueOf(row.get("scope")));
  }

  public void logout(String token) {
    if (token == null || token.isEmpty()) return;
    run("DELETE FROM sessions WHERE token=?", Crypto.sha256Hex(token));
  }

  // ---------- state ----------

  public Map<String, Object> state(User user) {
    return transaction(() -> readState(user));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readState(User user) {
    List<ObjectNode> orders = orders(user);
    Set<String> ids = new LinkedHashSet<>();
    for (ObjectNode o : orders) ids.add(optText(o, "id"));
    List<ObjectNode> allPlans = plans();
    List<ObjectNode> plans = new ArrayList<>();
    for (ObjectNode p : allPlans) {
      if ("dispatcher".equals(user.role())) {
        plans.add(p.deepCopy());
      } else if (p.has("published") && p.get("published").asBoolean()) {
        plans.add(filterPlan(p, ids));
      }
    }
    List<Map<String, Object>> events = new ArrayList<>();
    for (Map<String, Object> e : all("SELECT * FROM events ORDER BY id DESC")) {
      String orderId = String.valueOf(e.get("order_id"));
      if (ids.contains(orderId) || ("dispatcher".equals(user.role()) && "*".equals(orderId))) {
        events.add(eventRow(e));
      }
    }
    List<Map<String, Object>> vehicles = new ArrayList<>();
    for (ReferenceData.Vehicle v : loader.get().vehicles()) {
      if ("dispatcher".equals(user.role())
          || ("loader".equals(user.role()) && v.depot().equals(user.scope()))
          || ("driver".equals(user.role()) && v.vehicleId().equals(user.scope()))) {
        vehicles.add(vehicleRow(v));
      }
    }
    List<Map<String, Object>> outlets = new ArrayList<>();
    for (ReferenceData.Outlet o : loader.get().outlets()) {
      if ("dispatcher".equals(user.role())
          || ("store".equals(user.role()) && o.outletId().equals(user.scope()))) {
        outlets.add(outletRow(o));
      }
    }
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("user", Map.of("id", user.id(), "role", user.role(), "scope", user.scope()));
    state.put("orders", toList(orders));
    state.put("plans", toList(plans));
    state.put("events", events);
    state.put("vehicles", vehicles);
    state.put("outlets", outlets);
    state.put("now", now());
    state.put("demo", demo);
    state.put("updated", Instant.now().toString());
    if (demo) {
      List<Map<String, String>> scenarios = new ArrayList<>();
      for (Scenario s : SCENARIOS) {
        scenarios.add(Map.of("day", s.day(), "name", s.name(), "description", s.description(),
            "kind", s.kind()));
      }
      state.put("scenarios", scenarios);
    } else {
      state.put("scenarios", List.of());
    }
    if ("dispatcher".equals(user.role())) {
      Set<String> days = new TreeSet<>();
      for (ObjectNode o : orders) days.add(optText(o, "day"));
      for (ObjectNode p : allPlans) days.add(optText(p, "day"));
      for (Scenario s : SCENARIOS) days.add(s.day());
      Map<String, Object> planning = new LinkedHashMap<>();
      for (String day : days) {
        List<Map<String, Object>> dayVehicles = new ArrayList<>();
        for (ReferenceData.Vehicle v : reference(day).vehicles()) dayVehicles.add(vehicleRow(v));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("vehicles", dayVehicles);
        entry.put("reservations", reservationsJson(reservations(day, allPlans)));
        planning.put(day, entry);
      }
      state.put("planning", planning);
    }
    return state;
  }

  private ObjectNode filterPlan(ObjectNode p, Set<String> ids) {
    ObjectNode out = p.deepCopy();
    if (out.has("orders")) {
      ArrayNode kept = mapper.createArrayNode();
      for (JsonNode o : out.get("orders")) {
        if (ids.contains(o.get("id").asText())) kept.add(o);
      }
      out.set("orders", kept);
    }
    ArrayNode routes = mapper.createArrayNode();
    for (JsonNode r : out.get("routes")) {
      List<String> keptIds = new ArrayList<>();
      for (JsonNode id : r.get("order_ids")) {
        if (ids.contains(id.asText())) keptIds.add(id.asText());
      }
      if (keptIds.isEmpty()) continue;
      ObjectNode nr = ((ObjectNode) r).deepCopy();
      ArrayNode idsArr = mapper.createArrayNode();
      keptIds.forEach(idsArr::add);
      nr.set("order_ids", idsArr);
      ArrayNode stops = mapper.createArrayNode();
      for (JsonNode s : r.get("stops")) {
        if (ids.contains(s.get("order_id").asText())) stops.add(s);
      }
      nr.set("stops", stops);
      routes.add(nr);
    }
    out.set("routes", routes);
    ArrayNode deferred = mapper.createArrayNode();
    for (JsonNode d : out.get("deferred")) {
      if (ids.contains(d.get("order_id").asText())) deferred.add(d);
    }
    out.set("deferred", deferred);
    return out;
  }

  private Map<String, Object> eventRow(Map<String, Object> e) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", ((Number) e.get("id")).intValue());
    out.put("order_id", String.valueOf(e.get("order_id")));
    out.put("actor", String.valueOf(e.get("actor")));
    out.put("kind", String.valueOf(e.get("kind")));
    out.put("created", String.valueOf(e.get("created")));
    out.put("client_time", e.get("client_time") == null ? null : String.valueOf(e.get("client_time")));
    out.put("detail", colToString(e.get("detail")));
    return out;
  }

  private Map<String, Object> vehicleRow(ReferenceData.Vehicle v) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("vehicle_id", v.vehicleId());
    out.put("type", v.type());
    out.put("temp", v.temp());
    out.put("weight_cap_kg", v.weightCapKg());
    out.put("volume_cap_m3", v.volumeCapM3());
    out.put("fuel_type", v.fuelType());
    out.put("km_per_l", v.kmPerL());
    out.put("weekly_fuel_quota_l", v.weeklyFuelQuotaL());
    out.put("depot", v.depot());
    if (v.status() != null && !v.status().isEmpty()) out.put("status", v.status());
    return out;
  }

  private Map<String, Object> outletRow(ReferenceData.Outlet o) {
    return Map.of(
        "outlet_id", o.outletId(), "brand", o.brand(), "district", o.district(), "depot", o.depot(),
        "dock_type", o.dockType(), "parking_constraint", o.parkingConstraint(), "mall_window",
        o.mallWindow(), "window_open_time", o.windowOpenTime(), "window_close_time",
        o.windowCloseTime());
  }

  private List<Object> toList(List<ObjectNode> nodes) {
    List<Object> out = new ArrayList<>();
    for (ObjectNode n : nodes) {
      try {
        out.add(mapper.treeToValue(n, Object.class));
      } catch (Exception e) {
        if (e instanceof DataAccessException databaseError) throw databaseError;
        throw new IllegalStateException(e);
      }
    }
    return out;
  }

  // ---------- reservations ----------

  public Map<String, ReferenceData.Reservation> reservations(String day, List<ObjectNode> plans) {
    Map<String, ReferenceData.Reservation> used =
        new TreeMap<>(Planning.openingFuel(loader.get(), day, demo));
    List<ObjectNode> list = plans == null ? plans() : plans;
    for (ObjectNode p : list) {
      if (!p.hasNonNull("published") || !p.get("published").asBoolean()) continue;
      String pday = optText(p, "day");
      if (day.equals(pday) || !Planning.week(pday).equals(Planning.week(day))) continue;
      for (JsonNode r : p.get("routes")) {
        String vid = r.get("vehicle_id").asText();
        ReferenceData.Reservation prev =
            used.getOrDefault(vid, new ReferenceData.Reservation(0, 0, 210));
        used.put(vid,
            new ReferenceData.Reservation(prev.fuel() + r.get("fuel").asDouble(), prev.trips(),
                prev.end()));
      }
    }
    return used;
  }

  private Map<String, Object> reservationsJson(Map<String, ReferenceData.Reservation> reservations) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, ReferenceData.Reservation> e : reservations.entrySet()) {
      out.put(e.getKey(),
          Map.of("fuel", e.getValue().fuel(), "trips", e.getValue().trips(), "end",
              e.getValue().end()));
    }
    return out;
  }

  // ---------- plan validation / assignment ----------

  public void validatePlan(
      ObjectNode plan, Map<String, ObjectNode> byid,
      Map<String, ReferenceData.Reservation> reserved) {
    Map<String, ReferenceData.Reservation> base =
        reserved == null ? reservations(optText(plan, "day"), null) : reserved;
    Map<String, ReferenceData.Reservation> usage = new HashMap<>();
    for (Map.Entry<String, ReferenceData.Reservation> e : base.entrySet()) {
      usage.put(e.getKey(),
          new ReferenceData.Reservation(e.getValue().fuel(), e.getValue().trips(),
              e.getValue().end()));
    }
    ReferenceData ref = reference(optText(plan, "day"));
    List<ObjectNode> routes = new ArrayList<>();
    for (JsonNode r : plan.get("routes")) routes.add((ObjectNode) r);
    routes.sort((a, b) -> {
      int c = Double.compare(a.get("start").asDouble(), b.get("start").asDouble());
      return c != 0 ? c : a.get("id").asText().compareTo(b.get("id").asText());
    });
    for (ObjectNode r : routes) {
      ReferenceData.Vehicle v = null;
      for (ReferenceData.Vehicle candidate : ref.vehicles()) {
        if (candidate.vehicleId().equals(r.get("vehicle_id").asText())) v = candidate;
      }
      if (v == null) throw new DomainException("Vehicle no longer exists.");
      for (JsonNode id : r.get("order_ids")) {
        if (!byid.containsKey(id.asText())) {
          throw new DomainException("Orders changed. Generate a fresh plan.", 409);
        }
      }
      ReferenceData.Reservation used = usage.computeIfAbsent(v.vehicleId(),
          k -> new ReferenceData.Reservation(0, 0, 210));
      List<ObjectNode> members = new ArrayList<>();
      for (JsonNode id : r.get("order_ids")) members.add(byid.get(id.asText()));
      Planning.RouteValidation result = Planning.validateRoute(members, v, ref,
          Math.max(r.get("start").asDouble(), used.end()), used.fuel(), used.trips(), mapper);
      if (!result.errors().isEmpty()) {
        List<String> msgs = new ArrayList<>();
        for (String k : result.errors()) msgs.add(Planning.REASONS.getOrDefault(k, k));
        throw new DomainException("Invalid assignment: " + String.join(", ", msgs));
      }
      r.set("stops", mapper.valueToTree(result.stops()));
      r.put("fuel", result.fuel());
      r.put("distance", result.distance());
      r.put("start", result.start());
      r.put("end", result.end());
      r.set("errors", mapper.createArrayNode());
      usage.put(v.vehicleId(), new ReferenceData.Reservation(used.fuel() + result.fuel(),
          used.trips() + 1, result.end()));
    }
  }

  public ObjectNode assignment(ObjectNode plan, String orderId, String routeId,
      Map<String, ObjectNode> byid, Map<String, ReferenceData.Reservation> reserved) {
    Map<String, ReferenceData.Reservation> usage =
        reserved == null ? reservations(optText(plan, "day"), null) : reserved;
    if (!byid.containsKey(orderId)) throw new DomainException("Order not found.", 404);
    ObjectNode base = plan.deepCopy();
    ArrayNode routes = (ArrayNode) base.get("routes");
    for (JsonNode r : routes) {
      ObjectNode route = (ObjectNode) r;
      ArrayNode kept = mapper.createArrayNode();
      for (JsonNode id : route.get("order_ids")) {
        if (!id.asText().equals(orderId)) kept.add(id);
      }
      route.set("order_ids", kept);
    }
    ObjectNode target = null;
    for (JsonNode r : routes) {
      if (((ObjectNode) r).get("id").asText().equals(routeId)) target = (ObjectNode) r;
    }
    if (target == null && routeId.startsWith("NEW-")) {
      String vehicleId = routeId.substring(4);
      boolean exists = false;
      for (ReferenceData.Vehicle v : reference(optText(plan, "day")).vehicles()) {
        if (v.vehicleId().equals(vehicleId)) exists = true;
      }
      if (!exists) throw new DomainException("Vehicle not found.");
      double end = 210;
      for (JsonNode r : routes) {
        if (((ObjectNode) r).get("vehicle_id").asText().equals(vehicleId)) {
          end = Math.max(end, ((ObjectNode) r).get("end").asDouble());
        }
      }
      ObjectNode nr = mapper.createObjectNode();
      nr.put("id", "RUN-" + Crypto.randomHex(4));
      nr.put("vehicle_id", vehicleId);
      nr.set("order_ids", mapper.createArrayNode());
      nr.set("stops", mapper.createArrayNode());
      nr.put("start", end);
      nr.put("end", end);
      nr.put("fuel", 0);
      nr.put("distance", 0);
      nr.set("errors", mapper.createArrayNode());
      routes.add(nr);
      target = nr;
    }
    if (target == null) throw new DomainException("Select a valid route.");
    String targetId = target.get("id").asText();
    ArrayNode deferred = (ArrayNode) base.get("deferred");
    ArrayNode keptDeferred = mapper.createArrayNode();
    for (JsonNode d : deferred) {
      if (!d.get("order_id").asText().equals(orderId)) keptDeferred.add(d);
    }
    base.set("deferred", keptDeferred);

    ObjectNode best = null;
    DomainException error = null;
    int size = 0;
    for (JsonNode r : routes) {
      if (((ObjectNode) r).get("id").asText().equals(targetId)) {
        size = ((ObjectNode) r).get("order_ids").size();
      }
    }
    for (int index = 0; index <= size; index++) {
      ObjectNode candidate = base.deepCopy();
      ArrayNode cRoutes = (ArrayNode) candidate.get("routes");
      for (JsonNode r : cRoutes) {
        ObjectNode route = (ObjectNode) r;
        if (!route.get("id").asText().equals(targetId)) continue;
        ArrayNode ids = mapper.createArrayNode();
        List<String> current = new ArrayList<>();
        for (JsonNode id : route.get("order_ids")) current.add(id.asText());
        current.add(Math.min(index, current.size()), orderId);
        current.forEach(ids::add);
        route.set("order_ids", ids);
      }
      ArrayNode nonEmpty = mapper.createArrayNode();
      for (JsonNode r : cRoutes) {
        if (((ObjectNode) r).get("order_ids").size() > 0) nonEmpty.add(r);
      }
      candidate.set("routes", nonEmpty);
      try {
        validatePlan(candidate, byid, usage);
        if (best == null || routeEndSum(candidate) < routeEndSum(best)) best = candidate;
      } catch (DomainException e) {
        error = e;
      }
    }
    if (best == null) throw error == null ? new DomainException("No feasible position in this trip.") : error;
    return best;
  }

  private static double routeEndSum(ObjectNode plan) {
    double sum = 0;
    for (JsonNode r : plan.get("routes")) sum += r.get("end").asDouble();
    return sum;
  }

  public List<Map<String, Object>> previewAssignments(User user, String day, String orderId) {
    return transaction(() -> readAssignments(user, day, orderId));
  }

  private List<Map<String, Object>> readAssignments(User user, String day, String orderId) {
    if (!"dispatcher".equals(user.role())) {
      throw new DomainException("Dispatcher access required.", 403);
    }
    Map<String, ReferenceData.Reservation> usage = reservations(day, null);
    ObjectNode plan = null;
    for (ObjectNode p : plans()) {
      if (day.equals(optText(p, "day"))) plan = p;
    }
    if (plan == null || (plan.has("published") && plan.get("published").asBoolean())) {
      throw new DomainException("An editable draft is required.", 409);
    }
    Map<String, ObjectNode> byid = new HashMap<>();
    for (ObjectNode o : orders(null)) {
      if (day.equals(optText(o, "day"))) byid.put(optText(o, "id"), o);
    }
    ObjectNode order = byid.get(orderId);
    if (order == null) throw new DomainException("Order not found.", 404);
    ReferenceData ref = reference(day);
    List<Map<String, String>> targets = new ArrayList<>();
    for (JsonNode r : plan.get("routes")) {
      boolean contains = false;
      for (JsonNode id : r.get("order_ids")) {
        if (id.asText().equals(orderId)) contains = true;
      }
      if (!contains) {
        targets.add(Map.of("route_id", r.get("id").asText(), "vehicle_id",
            r.get("vehicle_id").asText()));
      }
    }
    for (ReferenceData.Vehicle v : ref.vehicles()) {
      if (v.depot().equals(optText(order, "depot"))) {
        targets.add(Map.of("route_id", "NEW-" + v.vehicleId(), "vehicle_id", v.vehicleId()));
      }
    }
    double baseFuel = 0;
    double baseDistance = 0;
    for (JsonNode r : plan.get("routes")) {
      baseFuel += r.get("fuel").asDouble();
      baseDistance += r.get("distance").asDouble();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, String> t : targets) {
      try {
        ObjectNode candidate = assignment(plan, orderId, t.get("route_id"), byid, usage);
        ObjectNode hit = null;
        for (JsonNode r : candidate.get("routes")) {
          for (JsonNode id : r.get("order_ids")) {
            if (id.asText().equals(orderId)) hit = (ObjectNode) r;
          }
        }
        ReferenceData.Vehicle v = null;
        for (ReferenceData.Vehicle candidate2 : ref.vehicles()) {
          if (candidate2.vehicleId().equals(hit.get("vehicle_id").asText())) v = candidate2;
        }
        double fuel = 0;
        double distance = 0;
        for (JsonNode r : candidate.get("routes")) {
          fuel += r.get("fuel").asDouble();
          distance += r.get("distance").asDouble();
        }
        String arrival = null;
        for (JsonNode s : hit.get("stops")) {
          if (s.get("order_id").asText().equals(orderId)) arrival = s.get("eta").asText();
        }
        double usedWeight = 0;
        double usedVolume = 0;
        for (JsonNode id : hit.get("order_ids")) {
          usedWeight += byid.get(id.asText()).get("weight").asDouble();
          usedVolume += byid.get(id.asText()).get("volume").asDouble();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("route_id", t.get("route_id"));
        row.put("vehicle_id", t.get("vehicle_id"));
        row.put("feasible", true);
        row.put("reason", "All operating constraints pass");
        row.put("arrival", arrival);
        row.put("added_fuel", fuel - baseFuel);
        row.put("added_distance", distance - baseDistance);
        row.put("remaining_weight", v.weightCapKg() - usedWeight);
        row.put("remaining_volume", v.volumeCapM3() - usedVolume);
        out.add(row);
      } catch (DomainException e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("route_id", t.get("route_id"));
        row.put("vehicle_id", t.get("vehicle_id"));
        row.put("feasible", false);
        row.put("reason", e.getMessage());
        row.put("added_fuel", 0);
        row.put("added_distance", 0);
        out.add(row);
      }
    }
    out.sort((a, b) -> {
      int c = Boolean.compare((Boolean) b.get("feasible"), (Boolean) a.get("feasible"));
      if (c != 0) return c;
      c = Double.compare(((Number) a.get("added_fuel")).doubleValue(),
          ((Number) b.get("added_fuel")).doubleValue());
      if (c != 0) return c;
      return ((String) a.get("vehicle_id")).compareTo((String) b.get("vehicle_id"));
    });
    return out;
  }

  // ---------- events ----------

  public void event(User user, String kind, String orderId, Object clientTime, Object detail) {
    String detailJson;
    try {
      detailJson = mapper.writeValueAsString(detail == null ? Map.of() : detail);
    } catch (Exception e) {
      if (e instanceof DataAccessException databaseError) throw databaseError;
        throw new IllegalStateException(e);
    }
    run("INSERT INTO events(order_id,actor,kind,created,client_time,detail) VALUES(?,?,?,?,?,?::jsonb)",
        orderId, user.id(), kind, Instant.now().toString(),
        String.valueOf(clientTime == null ? "" : clientTime).substring(0,
            Math.min(100, String.valueOf(clientTime == null ? "" : clientTime).length())),
        detailJson);
  }

  // ---------- commands ----------

  public Map<String, Object> command(User user, Map<String, Object> cmd) {
    if (cmd == null) throw new DomainException("Invalid command.");
    String key = text(cmd.get("id"), 100);
    String fingerprint;
    try {
      fingerprint = Crypto.sha256Hex(mapper.writeValueAsString(cmd));
    } catch (Exception e) {
      if (e instanceof DataAccessException databaseError) throw databaseError;
        throw new IllegalStateException(e);
    }
    return transaction(() -> {
      Map<String, Object> old =
          get("SELECT * FROM commands WHERE id=? AND user_id=?", key, user.id());
      if (old != null) {
        if (!fingerprint.equals(String.valueOf(old.get("fingerprint")))) {
          throw new DomainException("Command identifier already used with different data.", 409);
        }
        try {
          return mapper.readValue(colToString(old.get("response")),
              new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
          if (e instanceof DataAccessException databaseError) throw databaseError;
        throw new IllegalStateException(e);
        }
      }
      Map<String, Object> result;
      try {
        result = apply(user, cmd);
      } catch (DomainException e) {
        throw e;
      } catch (RuntimeException e) {
        if (e.getMessage() != null && e.getMessage().matches("(?s).*(calendar|operating).*")) {
          throw new DomainException(e.getMessage());
        }
        throw e;
      }
      try {
        run("INSERT INTO commands VALUES(?,?,?,?::jsonb)", key, user.id(), fingerprint,
            mapper.writeValueAsString(result));
      } catch (Exception e) {
        if (e instanceof DataAccessException databaseError) throw databaseError;
        throw new IllegalStateException(e);
      }
      return result;
    });
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> apply(User user, Map<String, Object> cmd) {
    if (cmd == null || !(cmd.get("kind") instanceof String kind)) {
      throw new DomainException("kind: Invalid option.");
    }
    Object dayRaw = cmd.getOrDefault("day", "2026-02-14");
    String day = dayRaw instanceof String s ? s : "2026-02-14";
    if (!day.matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new DomainException("day: Expected YYYY-MM-DD.");

    if (List.of("plan", "publish", "move", "defer_note").contains(kind)) {
      return applyPlanning(user, cmd, kind, day);
    }
    if ("order".equals(kind)) {
      return applyOrder(user, cmd);
    }
    return applyField(user, cmd, kind);
  }

  private Map<String, Object> applyPlanning(
      User user, Map<String, Object> cmd, String kind, String day) {
    if (!"dispatcher".equals(user.role())) {
      throw new DomainException("Dispatcher access required.", 403);
    }
    Map<String, Object> row = get("SELECT body FROM plans WHERE day=?", day);
    ObjectNode plan = row == null ? null : parseBody(row.get("body"));
    if (plan != null && plan.has("published") && plan.get("published").asBoolean()) {
      throw new DomainException(
          "Published plans are locked. Resolve exceptions without rewriting the plan.", 409);
    }
    Object revisionRaw = cmd.get("revision");
    int storedRevision = plan == null || !plan.has("revision") ? 0 : plan.get("revision").asInt();
    if (revisionRaw != null && ((Number) revisionRaw).intValue() != storedRevision) {
      throw new DomainException("This draft changed. Refresh before applying your decision.", 409);
    }
    List<ObjectNode> dayOrders = new ArrayList<>();
    for (ObjectNode o : orders(null)) {
      if (day.equals(optText(o, "day"))) dayOrders.add(o);
    }
    Map<String, ObjectNode> byid = new HashMap<>();
    for (ObjectNode o : dayOrders) byid.put(optText(o, "id"), o);

    if ("plan".equals(kind)) {
      if (dayOrders.isEmpty()) throw new DomainException("No orders for this day.");
      long cutoff;
      try {
        cutoff = java.time.OffsetDateTime.parse(day + "T00:00:00+05:30").toInstant().toEpochMilli()
            - 8 * 3_600_000L;
      } catch (Exception e) {
        throw new DomainException("day: Expected YYYY-MM-DD.");
      }
      long nowMs;
      try {
        nowMs = java.time.OffsetDateTime.parse(now()).toInstant().toEpochMilli();
      } catch (Exception e) {
        nowMs = System.currentTimeMillis();
      }
      if (!demo && nowMs < cutoff) {
        throw new DomainException("Orders are still open. Plan after 16:00.");
      }
      plan = Planning.allocate(dayOrders, reference(day), day, reservations(day, null), mapper);
      plan.put("revision", storedRevision);
      ArrayNode snapshot = mapper.createArrayNode();
      for (ObjectNode o : dayOrders) snapshot.add(o.deepCopy());
      plan.set("orders", snapshot);
    } else {
      if (plan == null) throw new DomainException("Create a draft plan first.");
      if ("defer_note".equals(kind)) {
        Object orderId = cmd.get("order_id");
        if (!(orderId instanceof String)) throw new DomainException("order_id: Invalid input.");
        ObjectNode found = null;
        for (JsonNode d : plan.get("deferred")) {
          if (((ObjectNode) d).get("order_id").asText().equals(orderId)) found = (ObjectNode) d;
        }
        if (found == null) throw new DomainException("Deferred order not found.", 404);
        found.put("justification", text(cmd.get("note")));
      }
      if ("move".equals(kind)) {
        Object orderId = cmd.get("order_id");
        Object routeId = cmd.get("route_id");
        if (!(orderId instanceof String) || !(routeId instanceof String)) {
          throw new DomainException("order_id: Invalid input.");
        }
        if (!byid.containsKey(orderId)) throw new DomainException("Order not found.", 404);
        plan = assignment(plan, (String) orderId, (String) routeId, byid, null);
      }
      if (List.of("move", "publish").contains(kind)) {
        validatePlan(plan, byid, null);
        if ("publish".equals(kind)) {
          for (JsonNode d : plan.get("deferred")) {
            ObjectNode dd = (ObjectNode) d;
            boolean repeat = dd.has("repeat") && dd.get("repeat").asBoolean();
            String justification = dd.has("justification") ? dd.get("justification").asText() : "";
            if (repeat && (justification == null || justification.isEmpty())) {
              throw new DomainException("A repeated deferral needs a written justification.");
            }
          }
          List<String> covered = new ArrayList<>();
          for (JsonNode r : plan.get("routes")) {
            for (JsonNode id : r.get("order_ids")) covered.add(id.asText());
          }
          for (JsonNode d : plan.get("deferred")) covered.add(d.get("order_id").asText());
          Set<String> unique = new LinkedHashSet<>(covered);
          boolean everyKnown = covered.stream().allMatch(byid::containsKey);
          if (unique.size() != covered.size() || covered.size() != dayOrders.size() || !everyKnown) {
            throw new DomainException("Orders changed since drafting. Generate a fresh plan.", 409);
          }
          ArrayNode snapshot = mapper.createArrayNode();
          for (ObjectNode o : dayOrders) snapshot.add(o.deepCopy());
          plan.set("orders", snapshot);
          plan.put("published", true);
          for (JsonNode r : plan.get("routes")) {
            ObjectNode route = (ObjectNode) r;
            for (JsonNode stop : route.get("stops")) {
              ObjectNode o = byid.get(stop.get("order_id").asText());
              o.put("status", "planned");
              o.put("route_id", route.get("id").asText());
              o.put("vehicle_id", route.get("vehicle_id").asText());
              o.put("eta", stop.get("eta").asText());
              o.put("sequence", stop.get("sequence").asInt());
              o.put("version", o.get("version").asInt() + 1);
              save(o);
              Map<String, Object> detail = new LinkedHashMap<>();
              detail.put("route", route.get("id").asText());
              detail.put("eta", stop.get("eta").asText());
              event(user, kind, optText(o, "id"), cmd.get("client_time"), detail);
            }
          }
          for (JsonNode d : plan.get("deferred")) {
            ObjectNode dd = (ObjectNode) d;
            ObjectNode o = byid.get(dd.get("order_id").asText());
            String next = Planning.nextOperating(day, loader.get());
            while (hasPublishedPlan(next)) next = Planning.nextOperating(next, loader.get());
            dd.put("next_date", next);
            if (!o.hasNonNull("requested_day")) o.put("requested_day", optText(o, "day"));
            o.put("day", next);
            o.put("status", "deferred");
            ObjectNode deferral = mapper.createObjectNode();
            deferral.put("reason", dd.get("reason").asText());
            deferral.put("next_date", next);
            deferral.put("repeat", dd.has("repeat") && dd.get("repeat").asBoolean());
            deferral.put("justification",
                dd.has("justification") ? dd.get("justification").asText() : "");
            o.set("deferral", deferral);
            o.put("skips", o.get("skips").asInt() + 1);
            o.put("version", o.get("version").asInt() + 1);
            o.remove("route_id");
            o.remove("vehicle_id");
            o.remove("eta");
            o.remove("sequence");
            save(o);
            Map<String, Object> detail = new LinkedHashMap<>();
            try {
              detail = mapper.convertValue(dd, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {}
            event(user, "deferred", optText(o, "id"), cmd.get("client_time"), detail);
          }
        }
      }
    }
    plan.put("revision", plan.has("revision") ? plan.get("revision").asInt() + 1 : 1);
    try {
      run("INSERT INTO plans(day,body) VALUES(?,?::jsonb) ON CONFLICT(day) DO UPDATE SET body=excluded.body",
          day, mapper.writeValueAsString(plan));
    } catch (Exception e) {
      if (e instanceof DataAccessException databaseError) throw databaseError;
        throw new IllegalStateException(e);
    }
    event(user, kind, "*", cmd.get("client_time"), Map.of("day", day));
    return Map.of("ok", true);
  }

  private boolean hasPublishedPlan(String day) {
    for (ObjectNode p : plans()) {
      if (day.equals(optText(p, "day")) && p.has("published") && p.get("published").asBoolean()) {
        return true;
      }
    }
    return false;
  }

  private Map<String, Object> applyOrder(User user, Map<String, Object> cmd) {
    if (!"store".equals(user.role())) throw new DomainException("Store access required.", 403);
    ReferenceData.Outlet outlet = null;
    for (ReferenceData.Outlet o : loader.get().outlets()) {
      if (o.outletId().equals(user.scope())) outlet = o;
    }
    if (outlet == null) throw new DomainException("Store access required.", 403);
    String target = Planning.eligibleDay(now(), loader.get());
    while (hasPublishedPlan(target)) target = Planning.nextOperating(target, loader.get());
    Object temp = cmd.get("temp");
    if (!"ambient".equals(temp) && !"chilled".equals(temp)) {
      throw new DomainException("Invalid temperature.");
    }
    if ("chilled".equals(temp) && !"Fresh".equals(outlet.brand())) {
      throw new DomainException("Chilled orders are available for Fresh outlets.");
    }
    ObjectNode o = mapper.createObjectNode();
    putOutlet(o, outlet);
    o.put("id", "ORD-" + java.util.UUID.randomUUID());
    o.put("day", target);
    o.put("weight", number(cmd.get("weight"), 0.01));
    o.put("volume", number(cmd.get("volume"), 0.001, 10000, false));
    o.put("units", (int) number(cmd.get("units"), 1, 100000, true));
    o.put("temp", (String) temp);
    o.put("status", "confirmed_order");
    o.put("version", 0);
    o.put("skips", 0);
    insertOrder(o);
    event(user, "order", o.get("id").asText(), cmd.get("client_time"), Map.of("day", target));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", true);
    result.put("order_id", o.get("id").asText());
    result.put("day", target);
    return result;
  }

  private Map<String, Object> applyField(User user, Map<String, Object> cmd, String kind) {
    Map<String, List<String>> rules = Map.of(
        "load", List.of("loader", "planned"),
        "shortfall", List.of("loader", "planned", "loaded"),
        "resolve", List.of("dispatcher", "shortfall"),
        "depart", List.of("driver", "loaded"),
        "arrive", List.of("driver", "departed"),
        "deliver", List.of("driver", "arrived"),
        "receive", List.of("store", "delivered", "partial"),
        "dispute", List.of("store", "delivered", "partial"));
    List<String> rule = rules.get(kind);
    if (rule == null) throw new DomainException("Unknown command.");
    String role = rule.get(0);
    List<String> states = rule.subList(1, rule.size());
    if (!user.role().equals(role)) {
      throw new DomainException("Your role cannot perform this action.", 403);
    }
    Object orderId = cmd.get("order_id");
    if (!(orderId instanceof String) || ((String) orderId).isEmpty()) {
      throw new DomainException("order_id: Invalid input.");
    }
    Map<String, Object> row = get("SELECT body FROM orders WHERE id=?", orderId);
    if (row == null) throw new DomainException("Order not found.", 404);
    ObjectNode o = parseBody(row.get("body"));
    if (!allowed(user, o)) throw new DomainException("Record is outside your assignment.", 403);
    Object versionRaw = cmd.get("version");
    if (!(versionRaw instanceof Number) || ((Number) versionRaw).intValue() != o.get("version").asInt()) {
      throw new DomainException(
          "This record changed. Review the latest state before retrying.", 409);
    }
    if (!states.contains(optText(o, "status"))) {
      throw new DomainException("Action is unavailable in the current state.", 409);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    if ("shortfall".equals(kind)) {
      detail.put("note", text(cmd.get("note")));
      int count = (int) number(cmd.get("count"), 1, o.get("units").asInt(), true);
      detail.put("count", count);
      ObjectNode shortfall = mapper.createObjectNode();
      shortfall.put("note", (String) detail.get("note"));
      shortfall.put("count", count);
      o.set("shortfall", shortfall);
    }
    if ("resolve".equals(kind)) {
      detail.put("note", text(cmd.get("note")));
      ObjectNode resolution = mapper.createObjectNode();
      resolution.put("note", (String) detail.get("note"));
      o.set("resolution", resolution);
    }
    if ("depart".equals(kind)) {
      List<ObjectNode> same = new ArrayList<>();
      for (ObjectNode x : orders(null)) {
        if (optText(x, "route_id").equals(optText(o, "route_id"))
            && optText(x, "day").equals(optText(o, "day"))
            && !optText(x, "route_id").isEmpty()) same.add(x);
      }
      Set<String> ready = Set.of("loaded", "departed", "arrived", "delivered", "partial", "failed",
          "confirmed", "disputed");
      boolean every = same.stream().allMatch(x -> ready.contains(optText(x, "status")));
      // Shortfalls surface as status "shortfall", which is not in the ready set.
      if (!every) {
        throw new DomainException(
            "Every stop must be loaded and all shortfalls resolved before departure.");
      }
    }
    if ("deliver".equals(kind)) {
      Object outcome = cmd.get("outcome");
      if (!"delivered".equals(outcome) && !"partial".equals(outcome) && !"failed".equals(outcome)) {
        throw new DomainException("Select a delivery outcome.");
      }
      int count = (int) number(cmd.get("count"), 0, o.get("units").asInt(), true);
      boolean match = ("delivered".equals(outcome) && count == o.get("units").asInt())
          || ("partial".equals(outcome) && count > 0 && count < o.get("units").asInt())
          || ("failed".equals(outcome) && count == 0);
      if (!match) throw new DomainException("Delivered count does not match the outcome.");
      detail.put("outcome", outcome);
      detail.put("count", count);
      Object noteRaw = cmd.get("note");
      detail.put("note", noteRaw == null ? "" : String.valueOf(noteRaw).substring(0,
          Math.min(500, String.valueOf(noteRaw).length())));
      if (!"failed".equals(outcome)) {
        detail.put("receiver", text(cmd.get("receiver"), 100));
        detail.put("signature", imageData(cmd.get("signature")));
        detail.put("photo", imageData(cmd.get("photo")));
      } else {
        detail.put("note", text(cmd.get("note")));
      }
      ObjectNode proof = mapper.createObjectNode();
      proof.put("outcome", (String) outcome);
      proof.put("count", count);
      proof.put("note", (String) detail.get("note"));
      if (!"failed".equals(outcome)) {
        proof.put("receiver", (String) detail.get("receiver"));
        proof.put("signature", (String) detail.get("signature"));
        proof.put("photo", (String) detail.get("photo"));
      }
      o.set("proof", proof);
    }
    if ("dispute".equals(kind)) {
      detail.put("note", text(cmd.get("note")));
      ObjectNode dispute = mapper.createObjectNode();
      dispute.put("note", (String) detail.get("note"));
      o.set("dispute", dispute);
    }
    String next = switch (kind) {
      case "load" -> "loaded";
      case "shortfall" -> "shortfall";
      case "resolve" -> "planned";
      case "depart" -> "departed";
      case "arrive" -> "arrived";
      case "deliver" -> (String) cmd.get("outcome");
      case "receive" -> "confirmed";
      case "dispute" -> "disputed";
      default -> throw new DomainException("Unknown command.");
    };
    o.put("status", next);
    o.put("version", o.get("version").asInt() + 1);
    save(o);
    Map<String, Object> logged = new LinkedHashMap<>(detail);
    logged.remove("photo");
    logged.remove("signature");
    event(user, kind, optText(o, "id"), cmd.get("client_time"), logged);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", true);
    result.put("order_id", optText(o, "id"));
    result.put("version", o.get("version").asInt());
    return result;
  }

  static String optText(JsonNode node, String field) {
    if (node == null) return "";
    JsonNode v = node.get(field);
    return v == null || v.isNull() ? "" : v.asText();
  }

  static String optText(ObjectNode node, String field) {
    return optText((JsonNode) node, field);
  }
}
