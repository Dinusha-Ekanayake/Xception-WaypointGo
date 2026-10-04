package com.waypoint.dispatch.planning.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * A hash of the demand a run was built on: every order and the version it was
 * at. Publication compares it with the demand as it is now, so an order placed,
 * amended or cancelled after the draft blocks the publish (PLN-07) instead of
 * the plan silently missing it.
 */
public final class DemandFingerprint {
  private DemandFingerprint() {}

  /** @param versions order id to its row version; the order of the map does not matter */
  public static String of(Map<UUID, Long> versions) {
    StringBuilder canonical = new StringBuilder();
    versions.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(e -> canonical.append(e.getKey()).append(':').append(e.getValue()).append('\n'));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
    }
  }
}
