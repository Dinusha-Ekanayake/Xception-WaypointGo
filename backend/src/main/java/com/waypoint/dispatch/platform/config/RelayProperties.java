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
 * The outbox relay (issue #6).
 *
 * @param enabled whether this process polls the outbox. Tests turn it off and
 *     drive delivery themselves
 * @param pollInterval how long an idle relay waits before looking again. A
 *     commit in this process wakes it at once, so this bounds only events
 *     written by another replica
 * @param batchSize events claimed at a time
 * @param maxAttempts deliveries tried before an event is dead-lettered (PLT-03)
 * @param baseBackoff the wait after the first failure; it doubles from there
 * @param maxBackoff the longest wait between two attempts
 * @param lease how long a claimed event belongs to the relay that claimed it.
 *     Longer than any subscriber may take, or a slow delivery is repeated
 */
@Validated
@ConfigurationProperties(prefix = "app.relay")
public record RelayProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("1s") @NotNull Duration pollInterval,
    @DefaultValue("20") @Min(1) @Max(500) int batchSize,
    @DefaultValue("8") @Min(1) @Max(50) int maxAttempts,
    @DefaultValue("2s") @NotNull Duration baseBackoff,
    @DefaultValue("5m") @NotNull Duration maxBackoff,
    @DefaultValue("5m") @NotNull Duration lease) {

  @AssertTrue(message = "app.relay: intervals must be positive and base backoff no longer than max")
  public boolean isCoherent() {
    return positive(pollInterval)
        && positive(baseBackoff)
        && positive(maxBackoff)
        && positive(lease)
        && baseBackoff.compareTo(maxBackoff) <= 0;
  }

  private static boolean positive(Duration d) {
    return d != null && !d.isNegative() && !d.isZero();
  }
}
