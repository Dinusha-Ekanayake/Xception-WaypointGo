package com.waypoint.dispatch.intelligence.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.intelligence.application.DecisionPort;
import com.waypoint.dispatch.intelligence.domain.CircuitBreaker;
import com.waypoint.dispatch.platform.config.AiProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Jev through OpenRouter's Decisions API ({@code POST /api/alpha/decisions}),
 * pinned to {@code app.ai.jev-model}. An alpha endpoint from a new vendor: a
 * circuit breaker stops calling it when it keeps failing, and a failure is an
 * empty answer, so the rules stand.
 */
@Component
public class JevDecisions implements DecisionPort {
  static final String URL = "https://openrouter.ai/api/alpha/decisions";
  static final String QUESTION = "pick";

  private final AiProperties properties;
  private final ObjectMapper json;
  private final Metrics metrics;
  private final Clock clock;
  private final CircuitBreaker circuit;
  private final HttpClient http;
  private final URI url;

  @org.springframework.beans.factory.annotation.Autowired
  public JevDecisions(AiProperties properties, ObjectMapper json, Metrics metrics, Clock clock) {
    this(properties, json, metrics, clock, URI.create(URL));
  }

  /** A test's own address in place of OpenRouter's. */
  JevDecisions(AiProperties properties, ObjectMapper json, Metrics metrics, Clock clock, URI url) {
    this.url = url;
    this.properties = properties;
    this.json = json;
    this.metrics = metrics;
    this.clock = clock.realTime();
    this.circuit = new CircuitBreaker(3, Duration.ofSeconds(60));
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  @Override
  public Optional<Choice> choose(String state, String instructions, Map<String, String> options) {
    if (!properties.jevEnabled() || !circuit.allow(clock.now())) {
      return Optional.empty();
    }
    long started = System.nanoTime();
    String status = "failed";
    try {
      Map<String, Object> question = new LinkedHashMap<>();
      question.put("type", "choice");
      question.put("instructions", instructions);
      question.put("criteria", options);
      String body = json.writeValueAsString(Map.of(
          "model", properties.jevModel(), "state", state, "questions", Map.of(QUESTION, question)));
      HttpRequest request = HttpRequest.newBuilder(url)
          .timeout(properties.timeout())
          .header("authorization", "Bearer " + properties.openrouterKey())
          .header("content-type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        circuit.onFailure(clock.now());
        status = "http_" + response.statusCode();
        return Optional.empty();
      }
      JsonNode root = json.readTree(response.body());
      JsonNode answer = root.path("answers").path(QUESTION);
      if (!answer.path("choice").isTextual()) {
        circuit.onFailure(clock.now());
        return Optional.empty();
      }
      // Only an option we offered is an answer: anything else is the vendor's error, never a new type.
      if (!options.containsKey(answer.path("choice").asText())) {
        status = "off_options";
        return Optional.empty();
      }
      Map<String, Double> probabilities = new LinkedHashMap<>();
      answer.path("probabilities").fields().forEachRemaining(e -> probabilities.put(e.getKey(), e.getValue().asDouble()));
      circuit.onSuccess();
      status = "ok";
      return Optional.of(new Choice(
          answer.path("choice").asText(), answer.path("confidence").asDouble(), probabilities,
          root.path("model").asText(properties.jevModel())));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      circuit.onFailure(clock.now());
      return Optional.empty();
    } catch (Exception e) {
      circuit.onFailure(clock.now());
      return Optional.empty();
    } finally {
      metrics.record("waypoint.ai.decision", (System.nanoTime() - started) / 1_000_000, "status", status);
    }
  }
}
