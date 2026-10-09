package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The S3-compatible object store (roadmap M-01): R2, B2, AWS or a store of our
 * own. Used when {@code OBJECT_STORE=s3}; then all four values and the
 * encryption key must be set or the process refuses to start. Neither secret is
 * ever logged.
 *
 * <p>Every object is encrypted here before it leaves the server (AES-256-GCM,
 * {@code S3_ENCRYPTION_KEY}), so the storage provider holds only ciphertext.
 * Lose the key and the objects cannot be read: keep it with the database
 * backups, never in the bucket.
 *
 * @param endpoint for example {@code https://<account>.r2.cloudflarestorage.com}
 * @param region {@code auto} for R2
 * @param encryptionKey 32 random bytes, base64: {@code openssl rand -base64 32}
 * @param moveAfter how long a backfilled database copy is kept before it is cleared
 */
@Validated
@ConfigurationProperties(prefix = "app.storage")
public record ObjectStoreProperties(
    @DefaultValue("") String objectStore,
    @DefaultValue("") String endpoint,
    @DefaultValue("") String bucket,
    @DefaultValue("auto") @NotNull String region,
    @DefaultValue("") String accessKeyId,
    @DefaultValue("") String secretAccessKey,
    @DefaultValue("30s") @NotNull Duration timeout,
    @DefaultValue("") String encryptionKey,
    @DefaultValue("30d") @NotNull Duration moveAfter) {

  public boolean s3() {
    return "s3".equalsIgnoreCase(objectStore == null ? "" : objectStore.strip());
  }

  @AssertTrue(message = "app.storage.object-store must be blank, database or s3")
  public boolean isStoreKnown() {
    String s = objectStore == null ? "" : objectStore.strip().toLowerCase(java.util.Locale.ROOT);
    return s.isEmpty() || s.equals("database") || s.equals("s3");
  }

  @AssertTrue(message = "OBJECT_STORE=s3 needs S3_ENDPOINT (https), S3_BUCKET, S3_ACCESS_KEY_ID and S3_SECRET_ACCESS_KEY")
  public boolean isComplete() {
    return !s3()
        || (endpoint != null && endpoint.matches("https?://[^/\\s]+/?")
            && !bucket.isBlank() && !accessKeyId.isBlank() && !secretAccessKey.isBlank());
  }

  @AssertTrue(message = "OBJECT_STORE=s3 needs S3_ENCRYPTION_KEY: 32 random bytes in base64 (openssl rand -base64 32)")
  public boolean isKeyValid() {
    if (!s3()) return true;
    try {
      return encryptionKey != null && java.util.Base64.getDecoder().decode(encryptionKey.strip()).length == 32;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  @Override
  public String toString() {
    return "ObjectStoreProperties[objectStore=" + objectStore + ", endpoint=" + endpoint + ", bucket=" + bucket
        + ", region=" + region + "]";
  }
}
