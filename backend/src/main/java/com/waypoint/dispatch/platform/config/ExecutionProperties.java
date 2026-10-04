package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Execution: where proof of delivery is kept and for how long (issue #12).
 *
 * @param proofStore where proof bytes are kept: {@code database} (the default,
 *     decision 2026-10-01: evidence lives in the database every deployment
 *     already backs up) or {@code local} (files under {@code proofDir})
 * @param proofDir the directory the local proof store writes under, and where
 *     the database store still finds artifacts written before it. On a deployed
 *     instance that uses it, this must be a persistent volume: it holds evidence
 * @param proofRetention how long a proof artifact is kept (P-14)
 * @param proofUrlSecret signs proof links. Blank means a random key per process:
 *     links then stop working at a restart, which is safe, and differ between
 *     replicas, which is not usable, so set it wherever more than one runs
 * @param proofUrlTtl how long a proof link opens its artifact
 * @param maxAttachmentBytes the largest photo or signature accepted
 */
@Validated
@ConfigurationProperties(prefix = "app.execution")
public record ExecutionProperties(
    @DefaultValue("database") @NotBlank @Pattern(regexp = "database|local") String proofStore,
    @DefaultValue("../var/proofs") @NotBlank String proofDir,
    @DefaultValue("400d") @NotNull Duration proofRetention,
    @DefaultValue("") String proofUrlSecret,
    @DefaultValue("5m") @NotNull Duration proofUrlTtl,
    @DefaultValue("3145728") @Min(1024) @Max(3_500_000) int maxAttachmentBytes) {

  @AssertTrue(message = "PROOF_URL_SECRET, when set, must be at least 16 characters")
  public boolean isSecretLongEnough() {
    return proofUrlSecret == null || proofUrlSecret.isBlank() || proofUrlSecret.trim().length() >= 16;
  }

  @AssertTrue(message = "app.execution: proof retention and link lifetime must be positive")
  public boolean isCoherent() {
    return proofRetention != null && !proofRetention.isNegative() && !proofRetention.isZero()
        && proofUrlTtl != null && !proofUrlTtl.isNegative() && !proofUrlTtl.isZero();
  }

  /** The configured key, or a fresh random one when none is configured. */
  public byte[] proofUrlKey() {
    if (proofUrlSecret != null && !proofUrlSecret.isBlank()) {
      return proofUrlSecret.trim().getBytes(StandardCharsets.UTF_8);
    }
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return key;
  }
}
