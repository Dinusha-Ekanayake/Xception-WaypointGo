package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The model service (issue #16, ADR-001).
 *
 * <p>A blank base URL means no model is served: every estimate is the
 * deterministic one, and every plan and forecast says so (rule 9). That is a
 * working state, not a misconfiguration.
 *
 * @param baseUrl for example {@code http://ml:8000}; blank turns model serving off
 * @param healthTimeout asking the service which models it has loaded
 * @param scoringTimeout one plan's routes; the route simulator takes seconds per depot-day
 * @param circuitFailures consecutive failures before the circuit opens
 * @param circuitOpenFor how long an open circuit refuses calls before one trial
 * @param maxAttempts tries to rescore a degraded plan while a model is active (P-28)
 * @param scoringBatch plans claimed per run of the scoring job
 */
@Validated
@ConfigurationProperties(prefix = "app.ml")
public record IntelligenceProperties(
    @DefaultValue("") String baseUrl,
    @DefaultValue("3s") @NotNull Duration healthTimeout,
    @DefaultValue("180s") @NotNull Duration scoringTimeout,
    @DefaultValue("3") @Min(1) @Max(20) int circuitFailures,
    @DefaultValue("60s") @NotNull Duration circuitOpenFor,
    @DefaultValue("6") @Min(1) @Max(50) int maxAttempts,
    @DefaultValue("5") @Min(1) @Max(100) int scoringBatch) {

  public boolean configured() {
    return baseUrl != null && !baseUrl.isBlank();
  }

  @AssertTrue(message = "app.ml.base-url must be an http(s) URL or blank")
  public boolean isUrlValid() {
    return !configured() || baseUrl.matches("https?://.+");
  }

  @AssertTrue(message = "app.ml: timeouts and the open period must be positive")
  public boolean isCoherent() {
    return positive(healthTimeout) && positive(scoringTimeout) && positive(circuitOpenFor);
  }

  private static boolean positive(Duration d) {
    return d != null && !d.isNegative() && !d.isZero();
  }
}
