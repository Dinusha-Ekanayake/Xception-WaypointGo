package com.waypoint.dispatch.intelligence.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.intelligence.domain.CircuitBreaker;
import com.waypoint.dispatch.platform.config.IntelligenceProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * The model service over HTTP (ADR-001: the port that keeps Intelligence
 * extractable). Behind a circuit breaker, so a service that is down costs one
 * timeout, not one per plan.
 *
 * <p>Never called inside a transaction: the scoring and forecast jobs gather
 * their inputs, call this with nothing open, and write the answer afterwards
 * (R-ML-01). Every failure is an answer, never an exception, so the caller
 * falls back to the deterministic estimate with the reason.
 */
@Component
public class ModelServingAdapter {
  private final IntelligenceProperties properties;
  private final ObjectMapper json;
  private final Metrics metrics;
  private final Clock clock;
  private final CircuitBreaker circuit;
  private final HttpClient http;

  public ModelServingAdapter(IntelligenceProperties properties, ObjectMapper json, Metrics metrics, Clock clock) {
    this.properties = properties;
    this.json = json;
    this.metrics = metrics;
    // The circuit's timing is the process's own, never the demo clock, which can jump.
    this.clock = clock.realTime();
    this.circuit = new CircuitBreaker(properties.circuitFailures(), properties.circuitOpenFor());
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    metrics.gauge(
        "waypoint.ml.circuit_open", () -> circuit.state(this.clock.now()) == CircuitBreaker.State.CLOSED ? 0 : 1);
  }

  /** A value, or why there is none. */
  public record Answer<T>(Optional<T> value, Optional<String> failure) {
    static <T> Answer<T> ok(T value) {
      return new Answer<>(Optional.of(value), Optional.empty());
    }

    static <T> Answer<T> failed(String reason) {
      return new Answer<>(Optional.empty(), Optional.of(reason));
    }
  }

  public record StopRisk(String deliveryId, BigDecimal serviceMinutes, BigDecimal lateProbability, String roadConditions) {}

  /** @param roadConditions {@code used}, {@code fallback} or {@code mixed} */
  public record RiskAnswer(String modelLabel, String roadConditions, List<StopRisk> stops) {}

  public record WeekAnswer(String depot, String brand, int isoYear, int isoWeek, BigDecimal totalM3, BigDecimal chilledM3) {}

  public record ForecastAnswer(String modelLabel, List<WeekAnswer> weeks) {}

  public boolean configured() {
    return properties.configured();
  }

  public CircuitBreaker.State circuitState() {
    return circuit.state(clock.now());
  }

  /** Kind to {@code name@version}, as the service reports it has loaded. */
  public Answer<Map<String, String>> loadedModels() {
    Answer<JsonNode> res = call("health", "GET", "/health", null, properties.healthTimeout());
    if (res.value().isEmpty()) {
      return Answer.failed(res.failure().orElseThrow());
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (JsonNode m : res.value().get().path("models")) {
      out.put(m.path("kind").asText(), m.path("name").asText() + "@" + m.path("version").asText());
    }
    return Answer.ok(out);
  }

  public Answer<RiskAnswer> deliveryRisk(Map<String, Object> body) {
    Answer<JsonNode> res = call("delivery_risk", "POST", "/v1/delivery-risk", body, properties.scoringTimeout());
    if (res.value().isEmpty()) {
      return Answer.failed(res.failure().orElseThrow());
    }
    JsonNode n = res.value().get();
    List<StopRisk> stops = new ArrayList<>();
    for (JsonNode p : n.path("predictions")) {
      stops.add(new StopRisk(
          p.path("deliveryId").asText(),
          p.path("serviceMin").decimalValue(),
          p.path("lateProb").decimalValue(),
          p.path("roadConditions").asText()));
    }
    return Answer.ok(new RiskAnswer(
        n.path("modelName").asText() + "@" + n.path("modelVersion").asText(), n.path("roadConditions").asText(), stops));
  }

  public Answer<ForecastAnswer> demandForecast(Map<String, Object> body) {
    Answer<JsonNode> res = call("demand_forecast", "POST", "/v1/demand-forecast", body, properties.scoringTimeout());
    if (res.value().isEmpty()) {
      return Answer.failed(res.failure().orElseThrow());
    }
    JsonNode n = res.value().get();
    List<WeekAnswer> weeks = new ArrayList<>();
    for (JsonNode f : n.path("forecasts")) {
      weeks.add(new WeekAnswer(
          f.path("depot").asText(), f.path("brand").asText(), f.path("isoYear").asInt(), f.path("isoWeek").asInt(),
          f.path("totalM3").decimalValue(), f.path("chilledM3").decimalValue()));
    }
    return Answer.ok(new ForecastAnswer(n.path("modelName").asText() + "@" + n.path("modelVersion").asText(), weeks));
  }

  private Answer<JsonNode> call(String operation, String method, String path, Object body, Duration timeout) {
    if (!properties.configured()) {
      metrics.increment("waypoint.ml.unavailable", "operation", operation, "reason", "unconfigured");
      return Answer.failed("model serving is not configured");
    }
    if (!circuit.allow(clock.now())) {
      metrics.increment("waypoint.ml.unavailable", "operation", operation, "reason", "circuit_open");
      return Answer.failed("circuit open after repeated failures");
    }
    long started = System.nanoTime();
    String status = "error";
    try {
      HttpRequest.Builder req =
          HttpRequest.newBuilder(URI.create(properties.baseUrl().replaceAll("/+$", "") + path))
              .timeout(timeout)
              .header("Accept", "application/json")
              .header("X-Correlation-Id", Optional.ofNullable(MDC.get("correlationId")).orElse("ml-job"));
      if ("POST".equals(method)) {
        req.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      } else {
        req.GET();
      }
      HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
      status = Integer.toString(res.statusCode());
      if (res.statusCode() >= 500) {
        circuit.onFailure(clock.now());
        metrics.increment("waypoint.ml.unavailable", "operation", operation, "reason", "http_" + status);
        return Answer.failed("model service answered " + status);
      }
      // A 4xx is the service saying no: it is up, so the circuit stays closed.
      circuit.onSuccess();
      if (res.statusCode() >= 400) {
        metrics.increment("waypoint.ml.unavailable", "operation", operation, "reason", "rejected");
        return Answer.failed("model service rejected the request (" + status + "): " + abbreviate(res.body()));
      }
      return Answer.ok(json.readTree(res.body()));
    } catch (HttpTimeoutException e) {
      status = "timeout";
      circuit.onFailure(clock.now());
      metrics.increment("waypoint.ml.unavailable", "operation", operation, "reason", "timeout");
      return Answer.failed("model service timed out after " + timeout.toSeconds() + " s");
    } catch (IOException e) {
      status = "io";
      circuit.onFailure(clock.now());
      metrics.increment("waypoint.ml.unavailable", "operation", operation, "reason", "io");
      return Answer.failed("model service unreachable: " + e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Answer.failed("interrupted");
    } finally {
      metrics.record("waypoint.ml.call", (System.nanoTime() - started) / 1_000_000, "operation", operation, "status", status);
    }
  }

  private static String abbreviate(String s) {
    return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
  }
}
