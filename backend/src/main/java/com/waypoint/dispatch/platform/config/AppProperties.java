package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application configuration, typed and validated.
 *
 * <p>Convention B8: the process refuses to start rather than run misconfigured.
 * A missing value is a deployment mistake, and finding it at 03:30 through odd
 * behaviour is worse than finding it at startup through a clear message.
 *
 * <p>Note the distinction from a database that is merely down: `DATABASE_URL`
 * must be present, but it may point at a database that is unreachable. That is
 * an outage, reported by readiness, not a misconfiguration.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    @NotBlank(message = "DATABASE_URL must be set, even if the database is currently down")
        String databaseUrl,
    @NotBlank String dataDir,
    @NotBlank String migrationsDir,
    boolean cookieSecure) {}
