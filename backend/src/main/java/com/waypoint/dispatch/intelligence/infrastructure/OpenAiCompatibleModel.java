package com.waypoint.dispatch.intelligence.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.intelligence.application.LanguageModel;
import com.waypoint.dispatch.intelligence.domain.CircuitBreaker;
import com.waypoint.dispatch.platform.config.AiProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Groq or OpenRouter, both OpenAI-compatible chat completions (roadmap AI-0).
 * The key stays in this process. A circuit breaker stops calling a provider that
 * keeps failing; a failure is an empty answer, so the screen keeps the rules' text.
 */
@Component
public class OpenAiCompatibleModel implements LanguageModel {
  private final AiProperties properties;
  private final ObjectMapper json;
  private final Metrics metrics;
  private final Clock clock;
  private final CircuitBreaker circuit;
  private final HttpClient http;

  public OpenAiCompatibleModel(AiProperties properties, ObjectMapper json, Metrics metrics, Clock clock) {
    this.properties = properties;
    this.json = json;
    this.metrics = metrics;
    this.clock = clock.realTime();
    this.circuit = new CircuitBreaker(3, Duration.ofSeconds(60));
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  @Override
  public String provider() {
    return properties.provider();
  }

  @Override
  public String model() {
    return properties.pinnedModel();
  }

  @Override
  public Optional<String> complete(String instruction, String facts) {
    if (!properties.enabled() || !circuit.allow(clock.now())) {
      return Optional.empty();
    }
    long started = System.nanoTime();
    String status = "failed";
    try {
      String body = json.writeValueAsString(Map.of(
          "model", properties.pinnedModel(),
          "temperature", 0.2,
          "max_tokens", 320,
          "messages", List.of(
              Map.of("role", "system", "content", instruction),
              Map.of("role", "user", "content", "Facts:\n" + facts))));
      HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/chat/completions"))
          .timeout(properties.timeout())
          .header("authorization", "Bearer " + properties.key())
          .header("content-type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        circuit.onFailure(clock.now());
        status = "http_" + response.statusCode();
        return Optional.empty();
      }
      JsonNode answer = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
      circuit.onSuccess();
      status = "ok";
      return answer.isTextual() ? Optional.of(answer.asText()) : Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      circuit.onFailure(clock.now());
      return Optional.empty();
    } catch (Exception e) {
      circuit.onFailure(clock.now());
      return Optional.empty();
    } finally {
      metrics.record("waypoint.ai.call", (System.nanoTime() - started) / 1_000_000, "provider", properties.provider(), "status", status);
    }
  }
}
