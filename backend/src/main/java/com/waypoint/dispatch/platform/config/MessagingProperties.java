package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Messaging: a thread per trip (issue #136).
 *
 * @param voiceRetention how long a voice note's audio is kept (P-33, as proof of
 *     delivery and issue photos); the message, its length and SHA-256 stay after
 */
@Validated
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(@DefaultValue("400d") @NotNull Duration voiceRetention) {

  @AssertTrue(message = "app.messaging: voice retention must be positive")
  public boolean isCoherent() {
    return voiceRetention != null && !voiceRetention.isNegative() && !voiceRetention.isZero();
  }
}
