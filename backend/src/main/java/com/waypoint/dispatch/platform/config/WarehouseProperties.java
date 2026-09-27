package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The external warehouse service.
 *
 * <p>The API key is a backend secret. It never reaches a browser bundle, because
 * the browser never calls the warehouse: every call goes through the adapter.
 *
 * <p>The key is allowed to be blank so the application runs before the warehouse
 * adapter exists. Once that adapter is built it validates the key on first use
 * and reports the port as unavailable rather than failing silently.
 */
@Validated
@ConfigurationProperties(prefix = "app.warehouse")
public record WarehouseProperties(
    @NotBlank String baseUrl, String apiKey, @Positive int timeoutMs) {

  public boolean isConfigured() {
    return apiKey != null && !apiKey.isBlank();
  }
}
