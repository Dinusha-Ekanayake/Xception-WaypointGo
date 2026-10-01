package com.waypoint.dispatch.platform.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
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
 *
 * @param migrationDatabaseUrl the owner's connection, read only by {@code migrate}. Blank means
 *     {@code databaseUrl} is used for both, which is how local development runs
 * @param cookieSecure on unless told otherwise, so a deployment that forgets the setting gets
 *     the safe cookie rather than one a plain HTTP hop can read
 * @param allowedOrigins origins a state-changing request may come from, beyond the host it was
 *     sent to. Empty means only that host
 * @param problemTypeBase prefix of the RFC 9457 {@code type} of every problem body. A URN
 *     until the public documentation host exists; clients branch on {@code code}, not on this
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    @NotBlank(message = "DATABASE_URL must be set, even if the database is currently down")
        String databaseUrl,
    @NotBlank String dataDir,
    @NotBlank String migrationsDir,
    @DefaultValue("") String migrationDatabaseUrl,
    @DefaultValue("true") boolean cookieSecure,
    @DefaultValue("") List<String> allowedOrigins,
    @DefaultValue("urn:waypoint:problem:") @NotBlank String problemTypeBase,
    @DefaultValue @Valid @NotNull Session session,
    @DefaultValue @Valid @NotNull LoginThrottle loginThrottle,
    @DefaultValue @Valid @NotNull Http http,
    @DefaultValue @Valid @NotNull Observability observability) {

  /**
   * @param absoluteLifetime how long one sign-in can last, however active
   * @param idleLifetime how long a session nobody uses survives
   * @param touchInterval how often, at most, a session in use writes that it was seen
   */
  public record Session(
      @DefaultValue("12h") @NotNull Duration absoluteLifetime,
      @DefaultValue("2h") @NotNull Duration idleLifetime,
      @DefaultValue("1m") @NotNull Duration touchInterval) {

    @AssertTrue(message = "app.session: idle lifetime must be positive and no longer than absolute")
    public boolean isCoherent() {
      return absoluteLifetime != null
          && idleLifetime != null
          && !idleLifetime.isNegative()
          && !idleLifetime.isZero()
          && idleLifetime.compareTo(absoluteLifetime) <= 0;
    }
  }

  /**
   * Failed sign-ins tolerated within the window before lockout (SEC-05, P-13).
   *
   * @param maxFailures for one identity from one address
   * @param addressMaxFailures for one address, whatever identity it tried
   * @param identityMaxFailures for one identity, wherever the attempts came from
   */
  public record LoginThrottle(
      @DefaultValue("8") @Min(1) @Max(100) int maxFailures,
      @DefaultValue("40") @Min(1) @Max(10000) int addressMaxFailures,
      @DefaultValue("40") @Min(1) @Max(10000) int identityMaxFailures,
      @DefaultValue("15m") @NotNull Duration window) {

    @AssertTrue(message = "app.login-throttle.window must be positive")
    public boolean isWindowPositive() {
      return window != null && !window.isNegative() && !window.isZero();
    }
  }

  /** Largest request body accepted, enforced before the body is read (SEC-08). */
  public record Http(@DefaultValue("4194304") @Positive long maxBodyBytes) {}

  /**
   * Trace export. Off unless asked for, so an instance with no collector does not
   * fail an export every few seconds. Trace ids are still generated and logged.
   */
  public record Observability(
      @DefaultValue("false") boolean otlpExport,
      @DefaultValue("") String otlpEndpoint,
      @DefaultValue("0.1") @DecimalMin("0.0") @DecimalMax("1.0") double sampleProbability) {

    @AssertTrue(message = "OTLP_EXPORT=true needs OTLP_ENDPOINT")
    public boolean isExportConfigured() {
      return !otlpExport || (otlpEndpoint != null && !otlpEndpoint.isBlank());
    }
  }
}
