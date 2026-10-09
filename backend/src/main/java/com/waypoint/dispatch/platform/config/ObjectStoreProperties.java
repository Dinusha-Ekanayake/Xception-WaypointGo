package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The S3-compatible object store (roadmap M-01): R2, B2, AWS or a store of our
 * own. Used when {@code OBJECT_STORE=s3}; then all four values must be set or
 * the process refuses to start. The secret is never logged.
 *
 * @param endpoint for example {@code https://<account>.r2.cloudflarestorage.com}
 * @param region {@code auto} for R2
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
    @DefaultValue("30s") @NotNull Duration timeout) {

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

  @Override
  public String toString() {
    return "ObjectStoreProperties[objectStore=" + objectStore + ", endpoint=" + endpoint + ", bucket=" + bucket
        + ", region=" + region + "]";
  }
}
