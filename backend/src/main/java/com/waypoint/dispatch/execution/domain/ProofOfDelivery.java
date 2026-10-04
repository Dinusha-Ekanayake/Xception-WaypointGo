package com.waypoint.dispatch.execution.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The evidence a driver leaves at a stop (R-EXE-01).
 *
 * <p>R-EXE-11: a device limitation never blocks the work. With neither a photo
 * nor a signature the driver says why, the proof is still recorded, and the
 * delivery is flagged lower-evidence instead of being refused.
 */
public record ProofOfDelivery(
    Optional<UUID> photoAttachmentId,
    Optional<UUID> signatureAttachmentId,
    Optional<String> recipientName,
    Optional<String> fallbackReason) {

  public static final int NAME_LIMIT = 120;
  public static final int REASON_LIMIT = 300;

  public ProofOfDelivery {
    if (photoAttachmentId.isEmpty() && signatureAttachmentId.isEmpty() && fallbackReason.isEmpty()) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "Without a photo or a signature, say why neither could be captured",
          List.of("R-EXE-11"));
    }
    if (recipientName.filter(n -> n.length() > NAME_LIMIT).isPresent()) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "recipientName is at most " + NAME_LIMIT + " characters");
    }
    if (fallbackReason.filter(r -> r.length() > REASON_LIMIT).isPresent()) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "fallbackReason is at most " + REASON_LIMIT + " characters");
    }
  }

  /** No photo and no signature: the outcome stands, on the driver's word. */
  public boolean lowEvidence() {
    return photoAttachmentId.isEmpty() && signatureAttachmentId.isEmpty();
  }
}
