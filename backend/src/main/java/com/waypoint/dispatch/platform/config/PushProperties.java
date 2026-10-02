package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Web push for notifications (issue #14, D-N).
 *
 * <p>Both VAPID keys blank turns push off: the inbox still works, and the API
 * says push is unavailable rather than pretending (rule 9). One key without the
 * other, or a key that is not a P-256 key, refuses to start. Generate a pair with
 * {@code npx web-push generate-vapid-keys}; both are base64url, the public key
 * the 65-byte uncompressed point and the private key the 32-byte scalar.
 *
 * @param vapidPublicKey the application server key browsers subscribe with
 * @param vapidPrivateKey a backend secret; never reaches a browser
 * @param subject a {@code mailto:} or {@code https:} contact the push service can reach
 * @param maxAttempts tries before a push is dead (PLT-03)
 * @param baseBackoff the wait after the first failure; it doubles from there
 * @param maxBackoff the longest wait between two tries
 * @param ttl how long a push service keeps a message for an offline device
 * @param timeout one request to a push service
 * @param batchSize pushes claimed per run of the delivery job
 */
@Validated
@ConfigurationProperties(prefix = "app.push")
public record PushProperties(
    @DefaultValue("") String vapidPublicKey,
    @DefaultValue("") String vapidPrivateKey,
    @DefaultValue("mailto:operations@waypoint.invalid") @NotNull String subject,
    @DefaultValue("6") @Min(1) @Max(50) int maxAttempts,
    @DefaultValue("30s") @NotNull Duration baseBackoff,
    @DefaultValue("30m") @NotNull Duration maxBackoff,
    @DefaultValue("24h") @NotNull Duration ttl,
    @DefaultValue("10s") @NotNull Duration timeout,
    @DefaultValue("50") @Min(1) @Max(500) int batchSize) {

  public boolean enabled() {
    return !blank(vapidPublicKey) && !blank(vapidPrivateKey);
  }

  @AssertTrue(message = "app.push: set both VAPID keys or neither")
  public boolean isPaired() {
    return blank(vapidPublicKey) == blank(vapidPrivateKey);
  }

  @AssertTrue(
      message =
          "app.push: the VAPID public key must be a base64url 65-byte uncompressed P-256 point"
              + " and the private key a base64url 32-byte scalar")
  public boolean isWellFormed() {
    if (!enabled()) {
      return true;
    }
    byte[] pub = decode(vapidPublicKey);
    byte[] priv = decode(vapidPrivateKey);
    return pub != null && pub.length == 65 && pub[0] == 4 && priv != null && priv.length == 32;
  }

  @AssertTrue(message = "app.push: subject must start with mailto: or https://")
  public boolean isSubjectContactable() {
    return subject != null && (subject.startsWith("mailto:") || subject.startsWith("https://"));
  }

  @AssertTrue(message = "app.push: durations must be positive and base backoff no longer than max")
  public boolean isCoherent() {
    return positive(baseBackoff)
        && positive(maxBackoff)
        && positive(ttl)
        && positive(timeout)
        && baseBackoff.compareTo(maxBackoff) <= 0;
  }

  private static byte[] decode(String value) {
    try {
      return Base64.getUrlDecoder().decode(value.strip().replace('+', '-').replace('/', '_').replace("=", ""));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static boolean positive(Duration d) {
    return d != null && !d.isNegative() && !d.isZero();
  }
}
