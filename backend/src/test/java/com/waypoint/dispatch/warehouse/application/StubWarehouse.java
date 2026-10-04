package com.waypoint.dispatch.warehouse.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A warehouse the tests control: the API of docs 2026-10-01 in a JDK HTTP server,
 * with the same lifecycle (reserved, pending, shipped, delivered, cancelled,
 * expired), the same {@code 201}/{@code 202}/{@code 409} placement, and no
 * idempotency key, exactly like the real one. The test can make it slow, make it
 * fail, and change an order behind Waypoint's back.
 */
final class StubWarehouse implements AutoCloseable {
  static final String KEY = "test-key";

  private static final Map<String, Set<String>> EDGES =
      Map.of(
          "reserved", Set.of("pending", "cancelled", "expired"),
          "pending", Set.of("shipped", "cancelled"),
          "shipped", Set.of("delivered"));

  record Item(String productId, int quantity, int requested) {}

  static final class Order {
    final String id;
    final String warehouse;
    volatile String status;
    final Instant createdAt = Instant.now();
    final Instant expiresAt;
    final List<Item> items;

    Order(String id, String warehouse, String status, Instant expiresAt, List<Item> items) {
      this.id = id;
      this.warehouse = warehouse;
      this.status = status;
      this.expiresAt = expiresAt;
      this.items = items;
    }
  }

  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpServer server;
  private final Map<String, Order> orders = new ConcurrentHashMap<>();
  private final Map<String, Integer> available = new ConcurrentHashMap<>();
  private final List<String> products = new ArrayList<>();
  /** Unique per stub: the test database outlives a run, and real warehouse ids are never reused. */
  private final String prefix = "ORD" + Long.toString(System.nanoTime(), 36).toUpperCase() + "-";
  private final AtomicInteger sequence = new AtomicInteger();

  final AtomicInteger posts = new AtomicInteger();
  final AtomicInteger calls = new AtomicInteger();
  volatile int failWith;
  volatile long slowPostMs;
  volatile boolean catalogueDown;

  StubWarehouse() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newFixedThreadPool(6));
    server.createContext("/api/v1", this::handle);
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
  }

  void reset() {
    orders.clear();
    available.clear();
    products.clear();
    posts.set(0);
    calls.set(0);
    failWith = 0;
    slowPostMs = 0;
    catalogueDown = false;
  }

  void stock(String productId, int units) {
    available.put(productId, units);
  }

  void listProduct(String productId) {
    products.add(productId);
  }

  Order order(String id) {
    return orders.get(id);
  }

  List<Order> all() {
    return new ArrayList<>(orders.values());
  }

  /** A change Waypoint did not make: someone used the warehouse's own console. */
  void forceStatus(String id, String status) {
    orders.get(id).status = status;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  // ---- the API ---------------------------------------------------------------

  private void handle(HttpExchange x) throws IOException {
    calls.incrementAndGet();
    try {
      if (!KEY.equals(x.getRequestHeaders().getFirst("x-api-key"))) {
        send(x, 401, error("unauthorized", "missing or revoked key"));
        return;
      }
      String path = x.getRequestURI().getPath().substring("/api/v1".length());
      String method = x.getRequestMethod();
      String query = x.getRequestURI().getQuery() == null ? "" : x.getRequestURI().getQuery();
      if (path.equals("/orders") && method.equals("POST")) {
        place(x);
      } else if (path.equals("/orders") && method.equals("GET")) {
        list(x, query);
      } else if (path.startsWith("/orders/") && path.endsWith("/status") && method.equals("PUT")) {
        transition(x, id(path, "/status"), body(x).path("status").asText());
      } else if (path.startsWith("/orders/") && path.endsWith("/confirm") && method.equals("POST")) {
        transition(x, id(path, "/confirm"), "pending");
      } else if (path.startsWith("/orders/") && method.equals("GET")) {
        Order o = orders.get(path.substring("/orders/".length()));
        if (o == null) {
          send(x, 404, error("order_not_found", "no such order"));
        } else {
          ObjectNode response = mapper.createObjectNode();
          response.set("data", data(o, true));
          send(x, 200, response);
        }
      } else if (path.equals("/products") && method.equals("GET")) {
        products(x, query);
      } else {
        send(x, 404, error("not_found", path));
      }
    } catch (RuntimeException e) {
      send(x, 500, error("stub_error", String.valueOf(e)));
    }
  }

  private void place(HttpExchange x) throws IOException {
    posts.incrementAndGet();
    JsonNode body = body(x);
    if (failWith != 0) {
      send(x, failWith, error("down", "the warehouse is down"));
      return;
    }
    String warehouse = body.path("warehouse").asText();
    List<Item> items = new ArrayList<>();
    ArrayNode shortfall = mapper.createArrayNode();
    boolean anything = false;
    boolean short_ = false;
    for (JsonNode line : body.path("items")) {
      String product = line.path("product_id").asText();
      int wanted = line.path("quantity").asInt();
      int got = Math.min(wanted, available.getOrDefault(product, 1000));
      anything |= got > 0;
      if (got < wanted) {
        short_ = true;
        shortfall.addObject().put("product_id", product).put("requested", wanted).put("reserved", got)
            .put("shortfall", wanted - got).putObject("other_warehouse").put("code", "KDY".equals(warehouse) ? "PLG" : "KDY").put("available", 1000);
      }
      items.add(new Item(product, got, wanted));
    }
    if (!anything) {
      send(x, 409, error("insufficient_stock", "nothing available"));
      return;
    }
    items.forEach(i -> available.compute(i.productId(), (k, v) -> (v == null ? 1000 : v) - i.quantity()));
    Order order =
        new Order(prefix + sequence.incrementAndGet(), warehouse, short_ ? "reserved" : "pending",
            short_ ? Instant.now().plusSeconds(900) : null, items);
    orders.put(order.id, order);
    sleep(slowPostMs);
    ObjectNode response = mapper.createObjectNode();
    response.set("data", data(order, true));
    if (short_) {
      response.set("shortfall", shortfall);
    }
    send(x, short_ ? 202 : 201, response);
  }

  private void list(HttpExchange x, String query) throws IOException {
    String status = param(query, "status");
    String warehouse = param(query, "warehouse");
    List<Order> matching = new ArrayList<>();
    for (Order o : orders.values()) {
      if ((status == null || status.equals(o.status)) && (warehouse == null || warehouse.equals(o.warehouse))) {
        matching.add(o);
      }
    }
    matching.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
    ObjectNode response = mapper.createObjectNode();
    ArrayNode data = response.putArray("data");
    matching.forEach(o -> data.add(data(o, false)));
    response.put("page", 1).put("total", matching.size());
    send(x, 200, response);
  }

  private void transition(HttpExchange x, String id, String target) throws IOException {
    Order o = orders.get(id);
    if (o == null) {
      send(x, 404, error("order_not_found", "no such order"));
    } else if (!EDGES.getOrDefault(o.status, Set.of()).contains(target)) {
      send(x, 409, error("invalid_transition", "Cannot change status from " + o.status + " to " + target));
    } else {
      o.status = target;
      ObjectNode response = mapper.createObjectNode();
      response.set("data", data(o, true));
      send(x, 200, response);
    }
  }

  private void products(HttpExchange x, String query) throws IOException {
    if (catalogueDown) {
      send(x, 503, error("down", "catalogue unavailable"));
      return;
    }
    int page = Integer.parseInt(param(query, "page") == null ? "1" : param(query, "page"));
    int limit = Integer.parseInt(param(query, "limit") == null ? "100" : param(query, "limit"));
    ObjectNode response = mapper.createObjectNode();
    ArrayNode data = response.putArray("data");
    for (int i = (page - 1) * limit; i < Math.min(products.size(), page * limit); i++) {
      data.addObject().put("product_id", products.get(i)).put("brand", "Fresh")
          .put("temp_requirement", i % 2 == 0 ? "chilled" : "ambient")
          .put("unit_weight_kg", 6.6182).put("unit_volume_m3", 0.03355)
          .put("base_product_id", "BASE").put("basis", "cluster_center").put("verified_real_sku", false);
    }
    response.put("page", page).put("limit", limit).put("total", products.size());
    send(x, 200, response);
  }

  private ObjectNode data(Order o, boolean withItems) {
    ObjectNode n = mapper.createObjectNode();
    int units = o.items.stream().mapToInt(Item::quantity).sum();
    n.put("order_id", o.id).put("status", o.status);
    n.putObject("warehouse").put("code", o.warehouse).put("name", "Kandy");
    n.put("temp_requirement", "ambient").put("source", "api");
    n.put("total_weight_kg", units * 2.5).put("total_volume_m3", units * 0.01);
    n.put("expires_at", "reserved".equals(o.status) && o.expiresAt != null ? o.expiresAt.toString() : null);
    n.put("created_at", o.createdAt.toString()).put("item_count", o.items.size());
    if (withItems) {
      ArrayNode items = n.putArray("items");
      o.items.forEach(i -> items.addObject().put("product_id", i.productId()).put("quantity", i.quantity())
          .put("requested_quantity", i.requested()));
    }
    return n;
  }

  private ObjectNode error(String code, String message) {
    ObjectNode n = mapper.createObjectNode();
    n.putObject("error").put("code", code).put("message", message);
    return n;
  }

  private JsonNode body(HttpExchange x) throws IOException {
    byte[] raw = x.getRequestBody().readAllBytes();
    return raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw);
  }

  private void send(HttpExchange x, int status, JsonNode body) throws IOException {
    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().add("Content-Type", "application/json");
    try {
      x.sendResponseHeaders(status, bytes.length);
      x.getResponseBody().write(bytes);
    } catch (IOException e) {
      // the client already gave up (a timeout test); the order stays created
    } finally {
      x.close();
    }
  }

  private static String id(String path, String suffix) {
    return path.substring("/orders/".length(), path.length() - suffix.length());
  }

  private static String param(String query, String name) {
    for (String pair : query.split("&")) {
      if (pair.startsWith(name + "=")) {
        return pair.substring(name.length() + 1);
      }
    }
    return null;
  }

  private static void sleep(long ms) {
    if (ms > 0) {
      try {
        Thread.sleep(ms);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
