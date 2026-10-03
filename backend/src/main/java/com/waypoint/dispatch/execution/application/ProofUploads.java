package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.domain.ProofStore;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository.StoredAttachment;
import com.waypoint.dispatch.execution.infrastructure.JdbcExecutionReads;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.config.ExecutionProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.ImageKind;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Takes a proof photo or signature into the proof store, and gives it back.
 *
 * <p>Binary, so not a command: it does not go through the bus. It keeps the
 * same guarantees by other means. The id is minted on the device and the
 * content is addressed by SHA-256, so an upload repeated from the offline queue
 * is a no-op and a different image under the same id is refused. The actor must
 * drive the stop's vehicle on its date. The type is read from the bytes, not
 * from the header.
 *
 * <p>The artifact is stored before the row is written: an artifact with no row
 * is harmless and is replaced by the retry; a row with no artifact would be a
 * proof that cannot be shown. If the store fails, nothing is recorded and the
 * device is told, so it keeps the image and tries again (EXE-10).
 */
@Component
public class ProofUploads {
  private static final Logger log = LoggerFactory.getLogger(ProofUploads.class);
  private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyy/MM");

  private final Database database;
  private final JdbcDeliveryRepository deliveries;
  private final JdbcExecutionReads reads;
  private final ProofStore store;
  private final ExecutionProperties properties;
  private final AuditLog audit;
  private final Metrics metrics;
  private final Clock clock;

  public ProofUploads(
      Database database,
      JdbcDeliveryRepository deliveries,
      JdbcExecutionReads reads,
      ProofStore store,
      ExecutionProperties properties,
      AuditLog audit,
      Metrics metrics,
      Clock clock) {
    this.audit = audit;
    this.database = database;
    this.deliveries = deliveries;
    this.reads = reads;
    this.store = store;
    this.properties = properties;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** @param stored false when this exact artifact was already here */
  public record Receipt(UUID attachmentId, String sha256, int sizeBytes, String contentType, boolean stored) {}

  /** What a signed link serves. */
  public record Content(byte[] bytes, String contentType) {}

  public Receipt store(Actor actor, UUID deliveryId, UUID attachmentId, String kind, byte[] content) {
    if (!"photo".equals(kind) && !"signature".equals(kind)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "kind must be photo or signature");
    }
    if (content == null || content.length == 0) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "The upload is empty");
    }
    if (content.length > properties.maxAttachmentBytes()) {
      // EXE-09: the device compresses and retries, or records a fallback reason.
      throw new DomainException(
          ErrorCode.PAYLOAD_TOO_LARGE,
          "A proof image is at most " + properties.maxAttachmentBytes() / 1024 + " KB");
    }
    ImageKind image =
        ImageKind.sniff(content)
            .orElseThrow(
                () -> new DomainException(ErrorCode.VALIDATION_FAILED, "A proof image is a JPEG, PNG or WebP"));
    String sha256 = sha256(content);
    Instant now = clock.now();
    String key = FOLDER.format(now.atZone(Clock.OPERATING_ZONE)) + "/" + attachmentId;

    Optional<StoredAttachment> existing;
    try {
      existing =
          database.readAs(
              ModuleRole.EXECUTION,
              actor.userId(),
              () -> {
                ExecutionMessages.loadForWrite(deliveries, deliveryId);
                return deliveries.attachment(attachmentId);
              });
    } catch (DomainException e) {
      if (e.code() == ErrorCode.FORBIDDEN) {
        // Policy allowed the action; scope refused the stop. Recorded like any other denial.
        audit.recordStandalone(
            AuditEntry.denied(
                actor.userId(), actor.deviceId(), ExecutionCommands.CAPTURE_PROOF,
                ExecutionMessages.deliveryResource(deliveryId), "outside the actor's scope"));
      }
      throw e;
    }
    if (existing.isPresent()) {
      return repeat(existing.get(), deliveryId, attachmentId, sha256, content.length);
    }

    try {
      store.put(key, content, image.contentType());
    } catch (RuntimeException e) {
      metrics.increment("waypoint.execution.proof_store", "outcome", "failed");
      log.warn("Proof store refused attachment {}: {}", attachmentId, e.toString());
      throw new DomainException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "The proof could not be stored. It is still on this device; try again.");
    }
    LocalDate retainUntil = now.plus(properties.proofRetention()).atZone(Clock.OPERATING_ZONE).toLocalDate();
    boolean inserted =
        database.asModule(
            ModuleRole.EXECUTION,
            actor.userId(),
            () -> {
              ExecutionMessages.loadForWrite(deliveries, deliveryId);
              return deliveries.insertAttachment(
                  attachmentId, deliveryId, kind, image.contentType(), content.length, sha256, key,
                  retainUntil, actor.userId(), now);
            });
    if (!inserted) {
      // Taken between the check and the write, or by a stop this actor cannot see.
      throw new DomainException(
          ErrorCode.CONFLICT, "A different artifact is already stored under this attachment id");
    }
    metrics.increment("waypoint.execution.proof_store", "outcome", "stored");
    return new Receipt(attachmentId, sha256, content.length, image.contentType(), true);
  }

  /** The bytes behind a signed link. The link was the authorization, so this reads as the process. */
  public Optional<Content> content(UUID attachmentId) {
    Map<String, Object> row =
        database.readAs(ModuleRole.EXECUTION, Actor.SYSTEM_ID, () -> reads.attachmentContent(attachmentId));
    if (row == null || "rejected".equals(row.get("scan_status"))) {
      return Optional.empty();
    }
    return store.get((String) row.get("storage_key"))
        .map(bytes -> new Content(bytes, (String) row.get("content_type")));
  }

  private Receipt repeat(
      StoredAttachment existing, UUID deliveryId, UUID attachmentId, String sha256, int size) {
    if (!existing.deliveryId().equals(deliveryId) || !existing.sha256().equals(sha256)) {
      throw new DomainException(
          ErrorCode.CONFLICT, "A different artifact is already stored under this attachment id");
    }
    metrics.increment("waypoint.execution.proof_store", "outcome", "duplicate");
    return new Receipt(attachmentId, sha256, size, existing.contentType(), false);
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
