package com.waypoint.dispatch.execution.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.ImageKind;
import java.time.LocalDate;

/**
 * What a Fuel Pass upload, revocation and read must satisfy (FF-06b). Pure: the
 * bytes, the reason and the days go in, the verdict comes out.
 *
 * <p>The government issues one QR per vehicle and enforces the quota at the
 * pump, so nothing here knows about litres or quotas: Waypoint only carries the
 * official image so a driver needs no paper copy.
 */
public final class FuelPassRules {
  private FuelPassRules() {}

  /** A photographed or exported QR is small; a larger file is not a pass. */
  public static final int MAX_BYTES = 512 * 1024;

  /** The image's type, read from its bytes; refused when empty, too large or not an image. */
  public static ImageKind requireImage(byte[] content) {
    if (content == null || content.length == 0) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "The upload is empty");
    }
    if (content.length > MAX_BYTES) {
      throw new DomainException(
          ErrorCode.PAYLOAD_TOO_LARGE, "A Fuel Pass image is at most " + MAX_BYTES / 1024 + " KB");
    }
    return ImageKind.sniff(content)
        .orElseThrow(
            () -> new DomainException(ErrorCode.VALIDATION_FAILED, "A Fuel Pass image is a PNG, JPEG or WebP"));
  }

  /** Why a pass was withdrawn, trimmed; at least three characters and at most 300. */
  public static String requireReason(String reason) {
    String trimmed = reason == null ? "" : reason.trim();
    if (trimmed.length() < 3 || trimmed.length() > 300) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "Say why the Fuel Pass is withdrawn, in 3 to 300 characters");
    }
    return trimmed;
  }

  /**
   * The last day the phone may keep the image: tomorrow when the driver also
   * drives the vehicle tomorrow, else today. The phone deletes it after that day.
   */
  public static LocalDate validUntil(LocalDate today, boolean drivesTomorrow) {
    return drivesTomorrow ? today.plusDays(1) : today;
  }
}
