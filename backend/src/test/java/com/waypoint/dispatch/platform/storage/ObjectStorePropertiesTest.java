package com.waypoint.dispatch.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.platform.config.ObjectStoreProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** M-01: the process refuses to start with the store half configured, and the secret is never printed. */
class ObjectStorePropertiesTest {
  static ObjectStoreProperties of(String store, String endpoint, String bucket, String key, String secret) {
    return new ObjectStoreProperties(store, endpoint, bucket, "auto", key, secret, Duration.ofSeconds(30));
  }

  @Test
  void blankOrDatabaseNeedsNothingAndS3NeedsAllFour() {
    assertTrue(of("", "", "", "", "").isComplete());
    assertTrue(of("database", "", "", "", "").isComplete());
    assertTrue(of("s3", "https://acc.r2.cloudflarestorage.com", "storage", "id", "secret").isComplete());
    assertFalse(of("s3", "https://acc.r2.cloudflarestorage.com", "", "id", "secret").isComplete());
    assertFalse(of("s3", "acc.r2.cloudflarestorage.com", "storage", "id", "secret").isComplete());
    assertFalse(of("r2", "", "", "", "").isStoreKnown());
  }

  @Test
  void theSecretIsNotInTheTextForm() {
    assertFalse(of("s3", "https://e", "b", "id", "very-secret").toString().contains("very-secret"));
  }

  @Test
  void keysAreEncodedSegmentBySegment() {
    assertEquals("proofs/d1/photo%201", S3Objects.encodePath("proofs/d1/photo 1"));
  }
}
