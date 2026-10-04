package com.waypoint.dispatch.warehouse.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waypoint.dispatch.platform.config.WarehouseProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.warehouse.contract.StockPort.StockLine;
import com.waypoint.dispatch.warehouse.domain.CatalogueEntry;
import com.waypoint.dispatch.warehouse.domain.CircuitBreaker;
import com.waypoint.dispatch.warehouse.domain.WarehouseOrder;
import com.waypoint.dispatch.warehouse.domain.WarehouseOrder.Item;
import com.waypoint.dispatch.warehouse.domain.WarehouseOrder.Shortfall;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Answered;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Failed;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Refused;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * The only code that speaks the warehouse's HTTP API. Everything it returns is
 * already in this module's own terms ({@link WarehouseReply},
 * {@link WarehouseOrder}, {@link CatalogueEntry}); no warehouse JSON leaves it.
 *
 * <p>Every call carries the API key, a correlation id, and a timeout. Every call
 * passes the circuit breaker: an open circuit answers {@code Failed(sent=false)}
 * without touching the network. A {@code 4xx} the warehouse chose counts as the
 * warehouse being up; only no answer, a {@code 5xx} or a {@code 401} counts as a
 * failure. The API key is never logged.
 */
@Component
public class WarehouseHttpClient {
  private static final String CORRELATION_HEADER = "X-Correlation-Id";

  private final WarehouseProperties properties;
  private final ObjectMapper mapper;
  private final Metrics metrics;
  private final Clock clock;
  /** The circuit's timing: the process's own, never the demo clock, which can jump. */
  private final Clock realClock;
  private final CircuitBreaker circuit;
  private final HttpClient http;

  public WarehouseHttpClient(
      WarehouseProperties properties, ObjectMapper mapper, Metrics metrics, Clock clock) {
    this.properties = properties;
    this.mapper = mapper;
    this.metrics = metrics;
    this.clock = clock;
    this.realClock = clock.realTime();
    this.circuit = new CircuitBreaker(properties.circuitFailures(), properties.circuitOpenFor());
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.min(properties.timeoutMs(), 2000)))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    metrics.gauge(
        "waypoint.warehouse.circuit_open",
        () -> circuit.state(realClock.now()) == CircuitBreaker.State.CLOSED ? 0 : 1);
  }

  public boolean configured() {
    return properties.isConfigured();
  }

  public CircuitBreaker.State circuitState() {
    return circuit.state(realClock.now());
  }

  // ---- orders ---------------------------------------------------------------

  /** {@code POST /orders}. Not idempotent (R-STK-11): the caller records the attempt first. */
  public WarehouseReply placeOrder(String warehouse, List<StockLine> lines) {
    ObjectNode body = mapper.createObjectNode();
    body.put("warehouse", warehouse);
    ArrayNode items = body.putArray("items");
    for (StockLine line : lines) {
      items.addObject().put("product_id", line.productId()).put("quantity", line.quantity());
    }
    return orderCall("place", "POST", "/orders", body, properties.timeoutMs());
  }

  public WarehouseReply order(String warehouseOrderRef) {
    return orderCall("read", "GET", "/orders/" + encode(warehouseOrderRef), null, properties.statusTimeoutMs());
  }

  public WarehouseReply confirm(String warehouseOrderRef) {
    return orderCall(
        "confirm", "POST", "/orders/" + encode(warehouseOrderRef) + "/confirm", null, properties.timeoutMs());
  }

  public WarehouseReply setStatus(String warehouseOrderRef, String status) {
    ObjectNode body = mapper.createObjectNode().put("status", status);
    return orderCall(
        "status_" + status, "PUT", "/orders/" + encode(warehouseOrderRef) + "/status", body,
        properties.statusTimeoutMs());
  }

  /** One page of {@code GET /orders}, newest first. Items are not included in a listing. */
  public Listing<WarehouseOrder> listOrders(String warehouse, String status, int page, int limit) {
    String path = "/orders?warehouse=" + encode(warehouse) + "&status=" + encode(status)
        + "&page=" + page + "&limit=" + limit;
    Response response = send("list", "GET", path, null, properties.statusTimeoutMs());
    if (response.failure().isPresent()) {
      return Listing.failed(response.failure().get());
    }
    if (response.status() != 200) {
      return Listing.failed(new Failed("warehouse answered " + response.status() + " to a listing", true));
    }
    List<WarehouseOrder> orders = new ArrayList<>();
    for (JsonNode node : response.body().path("data")) {
      orders.add(order(node));
    }
    return Listing.of(orders, response.body().path("total").asInt(orders.size()));
  }

  // ---- catalogue ------------------------------------------------------------

  public Listing<CatalogueEntry> products(int page, int limit) {
    Response response =
        send("catalogue", "GET", "/products?page=" + page + "&limit=" + limit, null,
            properties.catalogueTimeoutMs());
    if (response.failure().isPresent()) {
      return Listing.failed(response.failure().get());
    }
    if (response.status() != 200) {
      return Listing.failed(new Failed("warehouse answered " + response.status() + " to the catalogue", true));
    }
    List<CatalogueEntry> entries = new ArrayList<>();
    for (JsonNode p : response.body().path("data")) {
      String temperature = text(p, "temp_requirement");
      entries.add(
          new CatalogueEntry(
              p.path("product_id").asText(),
              p.path("brand").asText(),
              "chilled".equals(temperature) || "ambient".equals(temperature) ? temperature : null,
              new BigDecimal(p.path("unit_weight_kg").asText("0")),
              new BigDecimal(p.path("unit_volume_m3").asText("0")),
              text(p, "base_product_id"),
              p.path("basis").asText("unknown"),
              p.path("verified_real_sku").asBoolean(false)));
    }
    return Listing.of(entries, response.body().path("total").asInt(entries.size()));
  }

  /** A page of a listing, or why there is none. */
  public record Listing<T>(List<T> items, int total, Optional<Failed> failure) {

    static <T> Listing<T> of(List<T> items, int total) {
      return new Listing<>(List.copyOf(items), total, Optional.empty());
    }

    static <T> Listing<T> failed(Failed failure) {
      return new Listing<>(List.of(), 0, Optional.of(failure));
    }
  }

  // ---- internals ------------------------------------------------------------

  private WarehouseReply orderCall(
      String operation, String method, String path, JsonNode body, int timeoutMs) {
    Response response = send(operation, method, path, body, timeoutMs);
    if (response.failure().isPresent()) {
      return response.failure().get();
    }
    JsonNode json = response.body();
    int status = response.status();
    if (status >= 200 && status < 300) {
      JsonNode data = json.path("data");
      if (!data.hasNonNull("order_id")) {
        return new Failed("warehouse answered " + status + " without an order", true);
      }
      return new Answered(status, order(data), shortfall(json.path("shortfall")));
    }
    JsonNode error = json.path("error");
    return new Refused(
        status,
        error.path("code").asText("http_" + status),
        error.path("message").asText(""),
        shortfall(json.has("shortfall") ? json.path("shortfall") : error.path("shortfall")));
  }

  private record Response(int status, JsonNode body, Optional<Failed> failure) {}

  private Response send(String operation, String method, String path, JsonNode body, int timeoutMs) {
    if (!properties.isConfigured()) {
      return failed(operation, new Failed("warehouse not configured: app.warehouse.api-key is blank", false), "unconfigured");
    }
    Instant now = realClock.now();
    if (!circuit.allow(now)) {
      return failed(operation, new Failed("warehouse circuit open", false), "circuit_open");
    }
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(properties.baseUrl().replaceAll("/+$", "") + path))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("x-api-key", properties.apiKey())
            .header(CORRELATION_HEADER, correlationId())
            .header("Accept", "application/json");
    if (body != null) {
      request.header("Content-Type", "application/json");
      request.method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
    } else {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    }
    long started = System.nanoTime();
    try {
      HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      long ms = (System.nanoTime() - started) / 1_000_000;
      metrics.record("waypoint.warehouse.call", ms, "operation", operation, "status", String.valueOf(response.statusCode()));
      int status = response.statusCode();
      if (status >= 500 || status == 401 || status == 403) {
        circuit.onFailure(realClock.now());
        return failed(operation, new Failed("warehouse answered " + status, true), "http_" + status);
      }
      JsonNode json;
      try {
        json = response.body() == null || response.body().isBlank()
            ? mapper.createObjectNode()
            : mapper.readTree(response.body());
      } catch (IOException e) {
        circuit.onFailure(realClock.now());
        return failed(operation, new Failed("warehouse answered " + status + " with a body that does not parse", true), "unparseable");
      }
      circuit.onSuccess();
      return new Response(status, json, Optional.empty());
    } catch (HttpTimeoutException e) {
      circuit.onFailure(realClock.now());
      return failed(operation, new Failed("warehouse timed out after " + timeoutMs + " ms", true), "timeout");
    } catch (IOException e) {
      circuit.onFailure(realClock.now());
      return failed(operation, new Failed("warehouse unreachable: " + e.getClass().getSimpleName(), true), "io");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return failed(operation, new Failed("interrupted while calling the warehouse", true), "interrupted");
    }
  }

  private Response failed(String operation, Failed failure, String reason) {
    metrics.increment("waypoint.warehouse.unavailable", "operation", operation, "reason", reason);
    return new Response(0, null, Optional.of(failure));
  }

  private WarehouseOrder order(JsonNode data) {
    List<Item> items = new ArrayList<>();
    for (JsonNode item : data.path("items")) {
      int quantity = item.path("quantity").asInt();
      items.add(new Item(
          item.path("product_id").asText(),
          quantity,
          item.path("requested_quantity").asInt(quantity)));
    }
    String expires = text(data, "expires_at");
    String created = text(data, "created_at");
    JsonNode warehouse = data.path("warehouse");
    return new WarehouseOrder(
        data.path("order_id").asText(),
        data.path("status").asText(),
        warehouse.isObject() ? warehouse.path("code").asText() : warehouse.asText(),
        text(data, "temp_requirement"),
        decimal(data, "total_weight_kg"),
        decimal(data, "total_volume_m3"),
        Optional.ofNullable(expires).map(Instant::parse),
        created == null ? clock.now() : Instant.parse(created),
        data.path("item_count").asInt(items.size()),
        items);
  }

  private static List<Shortfall> shortfall(JsonNode node) {
    List<Shortfall> lines = new ArrayList<>();
    if (node == null || !node.isArray()) {
      return lines;
    }
    for (JsonNode s : node) {
      JsonNode other = s.path("other_warehouse");
      lines.add(new Shortfall(
          s.path("product_id").asText(),
          s.path("requested").asInt(),
          s.path("reserved").asInt(0),
          other.isObject() ? other.path("code").asText(null) : null,
          other.path("available").asInt(0)));
    }
    return lines;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static BigDecimal decimal(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : new BigDecimal(value.asText());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String correlationId() {
    String current = MDC.get("correlationId");
    return current == null ? UUID.randomUUID().toString() : current;
  }
}
