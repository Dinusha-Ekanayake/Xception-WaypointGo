package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.domain.ProofOfDelivery;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository.Stamp;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Proof of delivery: a photo, a signature, who received it (R-EXE-01).
 *
 * <p>The artifacts are named here by ids minted on the device and travel
 * separately; this command binds them to the stop, before or after they arrive.
 * A capture is appended, never overwritten, and the newest is the stop's proof. With neither photo nor signature the driver says
 * why and carries on, and the stop is flagged lower-evidence (R-EXE-11).
 */
@Component
public class CaptureProofHandler extends DeliveryCommandHandler {
  private final JdbcDeliveryRepository deliveries;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public CaptureProofHandler(JdbcDeliveryRepository deliveries, Metrics metrics, Clock clock) {
    super(ExecutionCommands.CAPTURE_PROOF);
    this.deliveries = deliveries;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    long expected = ExecutionMessages.expectedVersion(command);
    CommandPayload payload = CommandPayload.of(command);
    UUID deliveryId = payload.uuid("deliveryId");
    ProofOfDelivery proof =
        new ProofOfDelivery(
            Optional.ofNullable(payload.optionalUuid("photoAttachmentId")),
            Optional.ofNullable(payload.optionalUuid("signatureAttachmentId")),
            Optional.ofNullable(payload.text("recipientName")),
            Optional.ofNullable(payload.text("fallbackReason")));
    Instant now = clock.now();

    DeliveryRecord before = ExecutionMessages.load(deliveries, deliveryId, expected);
    boolean uploadsOwed =
        proof.photoAttachmentId().map(id -> pending(id, deliveryId, "photo")).orElse(false)
            | proof.signatureAttachmentId().map(id -> pending(id, deliveryId, "signature")).orElse(false);

    UUID proofId = UuidV7.generate(now, random);
    DeliveryRecord proven = before.withProof(proofId, proof);
    Stamp stamp = ExecutionMessages.stamp(actor, command, now);
    deliveries.appendProof(proofId, deliveryId, proof, command.commandId(), stamp);
    long version = deliveries.save(proven, expected, stamp);

    metrics.increment(
        "waypoint.execution.proof",
        "evidence", proof.lowEvidence() ? "low" : "full",
        "uploads", uploadsOwed ? "pending" : "complete");
    Map<String, Object> result = ExecutionMessages.result(proven, version);
    result.put("proofId", proofId.toString());
    result.put("lowEvidence", proof.lowEvidence());
    result.put("uploadsPending", uploadsOwed);
    return result;
  }

  /**
   * The artifact may not have reached the server yet: a device with no signal
   * records the proof and uploads when it can (EXE-10). Until then the proof
   * shows it as pending. One that has arrived must be this stop's, of this kind.
   *
   * @return true when the upload is still owed
   */
  private boolean pending(UUID attachmentId, UUID deliveryId, String kind) {
    var stored = deliveries.attachment(attachmentId);
    if (stored.isEmpty()) {
      return true;
    }
    if (!stored.get().deliveryId().equals(deliveryId) || !stored.get().kind().equals(kind)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "That attachment is not a " + kind + " uploaded for this stop");
    }
    return false;
  }
}
