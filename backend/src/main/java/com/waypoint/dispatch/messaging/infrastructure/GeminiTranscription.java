package com.waypoint.dispatch.messaging.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.messaging.application.Transcription;
import com.waypoint.dispatch.messaging.domain.TranscriptText;
import com.waypoint.dispatch.messaging.domain.TranscriptText.Heard;
import com.waypoint.dispatch.platform.config.TranscribeProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Transcription by Google's Gemini API (FF-05). The audio goes inline with an
 * instruction to write down what is said in its own script and name the
 * language; the answer is JSON. The key travels in a header, never in the URL,
 * and neither the audio nor the words are logged.
 */
@Component
public class GeminiTranscription implements Transcription {
  static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
  static final String PROMPT =
      "This is a voice note from a delivery driver, loader or store in Sri Lanka. Write down exactly what is said,"
          + " in the language and script it is spoken in (Sinhala, Tamil or English; keep English words said inside"
          + " Sinhala or Tamil as they are). Do not translate, summarise or add anything. Answer as JSON:"
          + " {\"language\": \"si\" | \"ta\" | \"en\", \"text\": \"...\", \"clear\": true | false}. If there is no speech,"
          + " text is empty. clear is false when noise, wind, engine sound, cross-talk or cut-off words leave any part of"
          + " what is said uncertain.";

  private final TranscribeProperties properties;
  private final ObjectMapper json;
  private final Metrics metrics;
  private final HttpClient http;

  public GeminiTranscription(TranscribeProperties properties, ObjectMapper json, Metrics metrics) {
    this.properties = properties;
    this.json = json;
    this.metrics = metrics;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  @Override
  public boolean enabled() {
    return properties.enabled();
  }

  @Override
  public String engine() {
    return "gemini:" + properties.model();
  }

  @Override
  public Optional<Heard> transcribe(byte[] audio, String contentType) {
    if (!properties.enabled()) return Optional.empty();
    long started = System.nanoTime();
    String status = "failed";
    try {
      Map<String, Object> body = Map.of(
          "contents", List.of(Map.of("parts", List.of(
              Map.of("text", PROMPT),
              Map.of("inline_data", Map.of("mime_type", mime(contentType), "data", Base64.getEncoder().encodeToString(audio)))))),
          "generationConfig", generation());
      HttpRequest request = HttpRequest.newBuilder(
              URI.create(BASE + URLEncoder.encode(properties.model(), StandardCharsets.UTF_8) + ":generateContent"))
          .timeout(properties.timeout())
          .header("content-type", "application/json")
          .header("x-goog-api-key", properties.geminiKey())
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
          .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        status = "http_" + response.statusCode();
        return Optional.empty();
      }
      Optional<Heard> heard = parse(json, response.body());
      status = heard.isPresent() ? "ok" : "no_speech";
      return heard;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      return Optional.empty();
    } finally {
      metrics.record("waypoint.transcribe.call", (System.nanoTime() - started) / 1_000_000, "engine", "gemini", "status", status);
    }
  }

  private Map<String, Object> generation() {
    Map<String, Object> config = new java.util.LinkedHashMap<>();
    config.put("responseMimeType", "application/json");
    config.put("temperature", 0);
    if (!properties.thinking().isBlank()) config.put("thinkingConfig", Map.of("thinkingLevel", properties.thinking().strip()));
    return config;
  }

  /** The last part of the first candidate, itself the JSON the prompt asked for (a thinking model may put its notes first). */
  static Optional<Heard> parse(ObjectMapper json, String responseBody) throws java.io.IOException {
    JsonNode parts = json.readTree(responseBody).path("candidates").path(0).path("content").path("parts");
    JsonNode text = parts.path(Math.max(0, parts.size() - 1)).path("text");
    if (!text.isTextual()) return Optional.empty();
    JsonNode answer = json.readTree(text.asText());
    // An answer without "clear" is taken as unclear: a word in doubt is reviewed, never trusted by default.
    return TranscriptText.heard(answer.path("text").asText(""), answer.path("language").asText(""),
        !answer.path("clear").asBoolean(false));
  }

  /** Gemini names Opus in WebM as audio/webm and AAC in MP4 as audio/mp4; the rest pass as they are. */
  static String mime(String contentType) {
    return contentType == null ? "audio/webm" : contentType.split(";")[0].strip();
  }
}
