package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The speech service (R-MSG-10): Piper on our own server, which turns a typed
 * message into audio in its language. A blank URL turns it off, and every
 * screen falls back to the phone's own voice, saying so (rule 9). Nothing is
 * sent outside: the service runs beside the backend.
 *
 * @param baseUrl for example {@code http://speech:8090}; blank turns it off
 * @param timeout one message
 */
@Validated
@ConfigurationProperties(prefix = "app.speech")
public record SpeechProperties(@DefaultValue("") String baseUrl, @DefaultValue("20s") @NotNull Duration timeout) {

  public boolean enabled() {
    return baseUrl != null && !baseUrl.isBlank();
  }

  @AssertTrue(message = "app.speech.base-url must be an http(s) URL or blank")
  public boolean isUrlValid() {
    return !enabled() || baseUrl.matches("https?://.+");
  }
}
