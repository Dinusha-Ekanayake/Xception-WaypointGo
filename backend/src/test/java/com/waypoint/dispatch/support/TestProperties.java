package com.waypoint.dispatch.support;

import com.waypoint.dispatch.platform.config.AppProperties;
import java.time.Duration;

/** {@link AppProperties} with the production defaults, for tests that build beans by hand. */
public final class TestProperties {
  private TestProperties() {}

  public static AppProperties app() {
    return withMaxBody(4L * 1024 * 1024);
  }

  public static AppProperties withMaxBody(long maxBodyBytes) {
    return new AppProperties(
        "postgresql://localhost/test",
        "../data",
        "../migrations",
        "",
        false,
        java.util.List.of(),
        "urn:waypoint:problem:",
        new AppProperties.Session(Duration.ofHours(12), Duration.ofHours(2), Duration.ofMinutes(1)),
        new AppProperties.LoginThrottle(8, 40, 40, Duration.ofMinutes(15)),
        new AppProperties.Http(maxBodyBytes),
        new AppProperties.Observability(false, "", 0.1));
  }
}
