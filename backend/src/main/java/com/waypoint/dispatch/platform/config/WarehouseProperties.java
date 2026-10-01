package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The external warehouse service.
 *
 * <p>The API key is a backend secret. It never reaches a browser bundle, because
 * the browser never calls the warehouse: every call goes through the adapter.
 *
 * <p>The key may be blank: placement then degrades to {@code STOCK_UNKNOWN},
 * visibly, and the warehouse jobs do nothing. The webhook secret may be blank
 * too, which turns the inbound webhook off; polling covers it (MODULES
 * "External warehouse integration contract").
 *
 * @param timeoutMs the placement budget; a store manager is waiting (MODULES: 2 s)
 * @param statusTimeoutMs cancel, ship, deliver and order reads, made by jobs (5 s)
 * @param catalogueTimeoutMs one catalogue page (60 s)
 * @param catalogueStaleAfter older than this the catalogue is shown as stale (CAT-01)
 * @param circuitFailures consecutive failures that open the circuit
 * @param circuitOpenFor how long the circuit stays open before one trial call
 * @param matchWindow how far either side of a lost attempt a warehouse order's
 *     creation time may fall and still be taken for ours (R-STK-11)
 * @param webhookSecret HMAC-SHA256 secret shared with the warehouse; blank disables the webhook
 * @param webhookWindow the replay window for a signed timestamp (SEC-19)
 */
@Validated
@ConfigurationProperties(prefix = "app.warehouse")
public record WarehouseProperties(
    @NotBlank @Pattern(regexp = "https?://.+", message = "WAREHOUSE_BASE_URL must be an http(s) URL")
        String baseUrl,
    String apiKey,
    @DefaultValue("2000") @Positive int timeoutMs,
    @DefaultValue("5000") @Positive int statusTimeoutMs,
    @DefaultValue("60000") @Positive int catalogueTimeoutMs,
    @DefaultValue("2h") @NotNull Duration catalogueStaleAfter,
    @DefaultValue("3") @Min(1) int circuitFailures,
    @DefaultValue("30s") @NotNull Duration circuitOpenFor,
    @DefaultValue("3m") @NotNull Duration matchWindow,
    String webhookSecret,
    @DefaultValue("5m") @NotNull Duration webhookWindow) {

  public boolean isConfigured() {
    return apiKey != null && !apiKey.isBlank();
  }

  public boolean webhookEnabled() {
    return webhookSecret != null && !webhookSecret.isBlank();
  }

  public byte[] webhookSecretBytes() {
    return webhookEnabled() ? webhookSecret.getBytes(StandardCharsets.UTF_8) : new byte[0];
  }

  /** Never print the key or the secret, including through a log of this record. */
  @Override
  public String toString() {
    return "WarehouseProperties[baseUrl=" + baseUrl
        + ", apiKey=" + (isConfigured() ? "present" : "absent")
        + ", webhookSecret=" + (webhookEnabled() ? "present" : "absent") + "]";
  }
}
