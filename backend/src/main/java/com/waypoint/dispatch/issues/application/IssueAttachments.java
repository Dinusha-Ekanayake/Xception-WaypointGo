package com.waypoint.dispatch.issues.application;

import com.waypoint.dispatch.issues.contract.IssueCommands;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueAttachments;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueAttachments.NewAttachment;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueAttachments.Stored;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.config.IssuesProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.receipt.contract.ReceiptQuery;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptView;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Takes a photo of a delivery problem from the store, and gives it back to those
 * who can see the issue it belongs to (Figma store manager "06d", "08b3").
 *
 * <p>Binary, so not a command, with the same guarantees by other means, as
 * {@code ProofUploads} has for proof of delivery. The id is minted on the phone
 * and the content is addressed by SHA-256, so an upload repeated from the
 * offline queue is a no-op and a different image under the same id is refused.
 * The type is read from the bytes. The actor must hold the order's outlet; a
 * photo for someone else's order is {@code 403} with an audit row.
 *
 * <p>A photo taken while counting names the receipt. If that receipt's shortage
 * investigation is already open, the photo is linked to it at once; if not, the
 * investigation links it when it opens. Either order works.
 */
@Component
public class IssueAttachments {
  private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyy/MM");

  private final Database database;
  private final JdbcIssueAttachments attachments;
  private final JdbcIssueRepository issues;
  private final OrderQuery orders;
  private final ReceiptQuery receipts;
  private final IssuesProperties properties;
  private final AuditLog audit;
  private final Metrics metrics;
  private final Clock clock;

  public IssueAttachments(
      Database database,
      JdbcIssueAttachments attachments,
      JdbcIssueRepository issues,
      OrderQuery orders,
      ReceiptQuery receipts,
      IssuesProperties properties,
      AuditLog audit,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.attachments = attachments;
    this.issues = issues;
    this.orders = orders;
    this.receipts = receipts;
    this.properties = properties;
    this.audit = audit;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** @param stored false when this exact photo was already here */
  public record Receipt(UUID attachmentId, String sha256, int sizeBytes, String contentType, boolean stored) {}

  /** What a read serves. */
  public record Content(byte[] bytes, String contentType) {}

  public Receipt store(Actor actor, UUID attachmentId, UUID orderId, Optional<UUID> receiptId, byte[] content) {
    if (content == null || content.length == 0) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "The upload is empty");
    }
    if (content.length > properties.maxAttachmentBytes()) {
      throw new DomainException(
          ErrorCode.PAYLOAD_TOO_LARGE, "A photo is at most " + properties.maxAttachmentBytes() / 1024 + " KB");
    }
    ImageKind image =
        ImageKind.sniff(content)
            .orElseThrow(() -> new DomainException(ErrorCode.VALIDATION_FAILED, "A photo is a JPEG, PNG or WebP"));
    String sha256 = sha256(content);
    Instant now = clock.now();

    // The order and receipt as the actor sees them: row-level security decides, and nothing is
    // widened in code (rule 7).
    Scope scope = database.readAs(ModuleRole.ISSUES, actor.userId(), () -> scopeOf(attachmentId, orderId, receiptId));
    if (scope.order().isEmpty()) {
      metrics.increment("waypoint.issues.attachment_store", "outcome", "denied");
      audit.recordStandalone(
          AuditEntry.denied(
              actor.userId(), actor.deviceId(), IssueCommands.ATTACH_PHOTO, "wpt:issue:order:" + orderId,
              "outside the actor's scope"));
      throw new DomainException(ErrorCode.FORBIDDEN, "Order " + orderId + " is not within your scope");
    }
    OrderView order = scope.order().get();
    if (receiptId.isPresent() && scope.receipt().map(r -> !r.receiptId().equals(receiptId.get())).orElse(true)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "The receipt is not this order's");
    }
    if (scope.existing().isPresent()) {
      return repeat(scope.existing().get(), orderId, sha256, content.length);
    }

    // The bytes first, keyed by their own hash: a row with no bytes would be a photo that cannot be
    // shown, while bytes with no row are harmless and found again by the retry.
    String key = FOLDER.format(now.atZone(Clock.OPERATING_ZONE)) + "/" + attachmentId + "-" + sha256.substring(0, 16);
    database.asSystemSeparately(
        ModuleRole.ISSUES,
        () -> {
          attachments.putContent(key, image.contentType(), content, now);
          return key;
        });
    LocalDate retainUntil = now.plus(properties.attachmentRetention()).atZone(Clock.OPERATING_ZONE).toLocalDate();
    boolean inserted =
        database.asModule(
            ModuleRole.ISSUES,
            actor.userId(),
            () -> {
              boolean fresh =
                  attachments.insert(
                      new NewAttachment(
                          attachmentId, order.outletId(), order.depotCode(), orderId, receiptId, image.contentType(),
                          content.length, sha256, key, retainUntil, actor.userId()),
                      now);
              // A receipt whose shortage investigation is already open takes the photo now.
              if (fresh && receiptId.isPresent()) {
                issues.findBySourceKey("receipt:" + receiptId.get())
                    .ifPresent(s -> attachments.link(s.issue().issueId(), List.of(attachmentId), now));
              }
              return fresh;
            });
    if (!inserted) {
      throw new DomainException(ErrorCode.CONFLICT, "A different photo is already stored under this id");
    }
    metrics.increment("waypoint.issues.attachment_store", "outcome", "stored");
    return new Receipt(attachmentId, sha256, content.length, image.contentType(), true);
  }

  /** The bytes of a photo linked to an issue the actor can see; absent otherwise, and never said why. */
  public Optional<Content> content(Actor actor, UUID issueId, UUID attachmentId) {
    Map<String, Object> row =
        database.readAs(ModuleRole.ISSUES, actor.userId(), () -> attachments.linked(issueId, attachmentId));
    if (row == null) {
      return Optional.empty();
    }
    byte[] bytes =
        database.readAs(ModuleRole.ISSUES, Actor.SYSTEM_ID, () -> attachments.content((String) row.get("storage_key")));
    return Optional.ofNullable(bytes).map(b -> new Content(b, (String) row.get("content_type")));
  }

  private record Scope(Optional<OrderView> order, Optional<ReceiptView> receipt, Optional<Stored> existing) {}

  /** A photo already under this id that the actor can see; one under another outlet is refused at insert. */
  private Scope scopeOf(UUID attachmentId, UUID orderId, Optional<UUID> receiptId) {
    Optional<OrderView> order = orders.order(orderId);
    Optional<ReceiptView> receipt = receiptId.isPresent() ? receipts.receiptFor(orderId) : Optional.empty();
    return new Scope(order, receipt, attachments.find(attachmentId));
  }

  private Receipt repeat(Stored existing, UUID orderId, String sha256, int size) {
    if (!existing.orderId().equals(orderId) || !existing.sha256().equals(sha256)) {
      throw new DomainException(ErrorCode.CONFLICT, "A different photo is already stored under this id");
    }
    metrics.increment("waypoint.issues.attachment_store", "outcome", "duplicate");
    return new Receipt(existing.attachmentId(), sha256, size, existing.contentType(), false);
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
