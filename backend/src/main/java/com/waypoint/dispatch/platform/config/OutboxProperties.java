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
 * How the outbox relay delivers events (issue #6).
 *
 * @param enabled off in tests, which drive the relay by hand so a delivery cannot
 *     fire in the middle of an assertion
 * @param pollInterval pause between batches when the last one found nothing to do
 * @param batchSize events claimed at once; the claim is {@code SKIP LOCKED}, so
 *     several instances each take a different batch
 * @param lease how long a claimed event is reserved. A relay that dies mid batch
 *     is recovered after this, so it must outlast the slowest batch
 * @param maxAttempts failed deliveries before an event is dead-lettered
 * @param backoffBase delay after the first failure; it doubles on each further one
 * @param backoffCap the longest delay between attempts
 */
@Validated
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("1s") @NotNull Duration pollInterval,
    @DefaultValue("50") @Min(1) @Max(1000) int batchSize,
    @DefaultValue("5m") @NotNull Duration lease,
    @DefaultValue("8") @Min(1) @Max(100) int maxAttempts,
    @DefaultValue("2s") @NotNull Duration backoffBase,
    @DefaultValue("5m") @NotNull Duration backoffCap) {

  @AssertTrue(message = "app.outbox: durations must be positive and the cap no shorter than the base")
  public boolean isCoherent() {
    return positive(pollInterval)
        && positive(lease)
        && positive(backoffBase)
        && positive(backoffCap)
        && backoffCap.compareTo(backoffBase) >= 0;
  }

  private static boolean positive(Duration value) {
    return value != null && !value.isNegative() && !value.isZero();
  }
}
