package com.waypoint.dispatch.messaging.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.messaging.application.SpeechSynthesis;
import com.waypoint.dispatch.platform.config.SpeechProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The speech service (speech/server.py): Piper beside the backend. Nothing leaves our servers. */
@Component
public class PiperSpeech implements SpeechSynthesis {
  private final SpeechProperties properties;
  private final ObjectMapper json;
  private final Metrics metrics;
  private final HttpClient http;

  public PiperSpeech(SpeechProperties properties, ObjectMapper json, Metrics metrics) {
    this.properties = properties;
    this.json = json;
    this.metrics = metrics;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  @Override
  public boolean enabled() {
    return properties.enabled();
  }

  @Override
  public Optional<byte[]> speak(String text, String lang) {
    if (!properties.enabled()) return Optional.empty();
    long started = System.nanoTime();
    String status = "failed";
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl().replaceAll("/+$", "") + "/speak"))
          .timeout(properties.timeout())
          .header("content-type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("text", text, "lang", lang))))
          .build();
      HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200 || response.body().length == 0) {
        status = "http_" + response.statusCode();
        return Optional.empty();
      }
      status = "ok";
      return Optional.of(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      return Optional.empty();
    } finally {
      metrics.record("waypoint.speech.call", (System.nanoTime() - started) / 1_000_000, "lang", lang, "status", status);
    }
  }
}
