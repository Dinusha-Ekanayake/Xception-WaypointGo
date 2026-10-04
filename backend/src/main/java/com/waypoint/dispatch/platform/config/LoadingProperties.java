package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The warehouse docks. Planning does not assign docks, so Loading spreads a
 * depot's trips over its docks in departure order (assumption A-26). The loader
 * filters the dock board by the dock they stand at.
 */
@Validated
@ConfigurationProperties(prefix = "app.loading")
public record LoadingProperties(
    @Min(1) @Max(20) int docksPerDepot,
    Map<@NotBlank String, @NotNull @Min(1) @Max(20) Integer> docks,
    boolean fixtureEnabled) {
  public LoadingProperties {
    docks = docks == null ? Map.of() : Map.copyOf(docks);
  }

  public int docksFor(String depotCode) {
    return docks.getOrDefault(depotCode, docksPerDepot);
  }
}
