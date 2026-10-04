package com.waypoint.dispatch.intelligence.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The model service as the backend sees it, answering as a test tells it to.
 * Delivery risk answers every stop it is sent, so a test can tell the model's
 * numbers from the deterministic ones by value.
 */
final class StubModelServer {
  static final double SERVICE = 12.34;
  static final double LATE = 0.2345;

  final AtomicReference<String> riskLabel = new AtomicReference<>("stub-risk@1");
  final AtomicReference<String> demandLabel = new AtomicReference<>("stub-demand@1");
  /** When true every call answers 500, as a service that is down. */
  volatile boolean failing;
  volatile String roadConditions = "used";
  final AtomicInteger riskCalls = new AtomicInteger();
  final AtomicReference<JsonNode> lastRiskRequest = new AtomicReference<>();

  private final ObjectMapper json = new ObjectMapper();
  private final HttpServer server;

  StubModelServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/health", this::health);
    server.createContext("/v1/delivery-risk", this::risk);
    server.createContext("/v1/demand-forecast", this::forecast);
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void reset() {
    riskLabel.set("stub-risk@1");
    demandLabel.set("stub-demand@1");
    failing = false;
    roadConditions = "used";
  }

  private void health(HttpExchange ex) throws IOException {
    if (failing) {
      send(ex, 500, "{}");
      return;
    }
    ObjectNode body = json.createObjectNode().put("status", "ok");
    ArrayNode models = body.putArray("models");
    models.addObject().put("kind", "delivery_risk").put("name", name(riskLabel.get())).put("version", version(riskLabel.get()));
    models.addObject().put("kind", "demand_forecast").put("name", name(demandLabel.get())).put("version", version(demandLabel.get()));
    send(ex, 200, json.writeValueAsString(body));
  }

  private void risk(HttpExchange ex) throws IOException {
    riskCalls.incrementAndGet();
    JsonNode req = json.readTree(ex.getRequestBody());
    lastRiskRequest.set(req);
    if (failing) {
      send(ex, 500, "{}");
      return;
    }
    ObjectNode body = json.createObjectNode()
        .put("kind", "delivery_risk").put("modelName", name(riskLabel.get())).put("modelVersion", version(riskLabel.get()))
        .put("roadConditions", roadConditions);
    ArrayNode predictions = body.putArray("predictions");
    for (JsonNode route : req.path("routes")) {
      for (JsonNode stop : route.path("stops")) {
        predictions.addObject().put("deliveryId", stop.path("deliveryId").asText()).put("serviceMin", SERVICE)
            .put("lateProb", LATE).put("roadConditions", "used".equals(roadConditions) ? "used" : "fallback");
      }
    }
    send(ex, 200, json.writeValueAsString(body));
  }

  private void forecast(HttpExchange ex) throws IOException {
    JsonNode req = json.readTree(ex.getRequestBody());
    if (failing) {
      send(ex, 500, "{}");
      return;
    }
    ObjectNode body = json.createObjectNode()
        .put("kind", "demand_forecast").put("modelName", name(demandLabel.get())).put("modelVersion", version(demandLabel.get()));
    ArrayNode forecasts = body.putArray("forecasts");
    for (JsonNode w : req.path("weeks")) {
      boolean fresh = "Fresh".equals(w.path("brand").asText());
      forecasts.addObject().put("depot", w.path("depot").asText()).put("brand", w.path("brand").asText())
          .put("isoYear", w.path("isoYear").asInt()).put("isoWeek", w.path("isoWeek").asInt())
          .put("totalM3", 10.5).put("chilledM3", fresh ? 2.25 : 0.0);
    }
    send(ex, 200, json.writeValueAsString(body));
  }

  private static String name(String label) {
    return label.substring(0, label.indexOf('@'));
  }

  private static String version(String label) {
    return label.substring(label.indexOf('@') + 1);
  }

  private static void send(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }
}
