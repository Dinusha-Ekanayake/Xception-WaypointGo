package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.contract.ProfilePhotoQuery;
import com.waypoint.dispatch.identity.domain.ProfilePhotoRules;
import com.waypoint.dispatch.identity.infrastructure.JdbcProfilePhotos;
import com.waypoint.dispatch.identity.infrastructure.JdbcProfilePhotos.Photo;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.ImageKind;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Profile photos (roadmap FF-07, policy P-PD-1).
 *
 * <p>The administrator uploads a person's photo with how they consented on
 * record. The upload is binary, so it keeps the command bus's guarantees by the
 * means the Fuel Pass uses: the photo id is minted by the screen and the content
 * is addressed by SHA-256, so the same upload sent twice is one photo, and the
 * expected version is the number of photos the person has had, so two
 * administrators replacing one photo collide. Replacing or removing a photo
 * deletes its image.
 *
 * <p>A photo is shown only to the people who work with that person today; every
 * other read is refused and audited, never answered empty.
 */
@Component
public class ProfilePhotos implements ProfilePhotoQuery {
  private final Database database;
  private final JdbcProfilePhotos photos;
  private final ReferenceQuery reference;
  private final AuditLog audit;
  private final Metrics metrics;
  private final Clock clock;

  public ProfilePhotos(
      Database database,
      JdbcProfilePhotos photos,
      ReferenceQuery reference,
      AuditLog audit,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.photos = photos;
    this.reference = reference;
    this.audit = audit;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** @param stored false when this exact photo was already here */
  public record Receipt(UUID userId, UUID photoId, String sha256, int sizeBytes, long version, boolean stored) {}

  public Receipt upload(Actor actor, UUID userId, UUID photoId, long expectedVersion, String consent, byte[] content) {
    ImageKind image = ProfilePhotoRules.requireImage(content);
    String consentNote = ProfilePhotoRules.requireConsent(consent);
    String sha256 = sha256(content);
    Instant now = clock.now();
    Receipt receipt =
        database.asModule(
            ModuleRole.IAM,
            actor.userId(),
            () -> {
              if (!photos.exists(userId)) {
                throw new DomainException(ErrorCode.NOT_FOUND, "No account " + userId);
              }
              Optional<Photo> existing = photos.byId(photoId);
              if (existing.isPresent()) {
                Photo photo = existing.get();
                if (!photo.userId().equals(userId) || !photo.sha256().equals(sha256)) {
                  throw new DomainException(ErrorCode.CONFLICT, "A different image is already stored under this photo id");
                }
                return new Receipt(userId, photoId, sha256, photo.sizeBytes(), photos.version(userId), false);
              }
              long version = photos.version(userId);
              if (version != expectedVersion) {
                throw new DomainException(
                    ErrorCode.VERSION_CONFLICT, "This person's photo changed since this screen loaded; reload and try again");
              }
              photos.removeCurrent(userId, actor.userId(), now, "replaced by a new photo");
              photos.insert(photoId, userId, content, image.contentType(), sha256, consentNote, actor.userId(), now);
              audit.record(AuditEntry.allowed(actor.userId(), actor.deviceId(), SET, resource(userId), "uploaded photo " + photoId));
              return new Receipt(userId, photoId, sha256, content.length, version + 1, true);
            });
    metrics.increment("waypoint.iam.profile_photo", "outcome", receipt.stored() ? "uploaded" : "duplicate");
    return receipt;
  }

  /** Inside the command bus's transaction: deletes the image, keeps who, when and why. */
  void applyRemove(Actor actor, UUID userId, String reason, Long expectedVersion) {
    String why = ProfilePhotoRules.requireReason(reason);
    Photo current =
        photos.current(userId).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "This person has no photo"));
    if (expectedVersion == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Removing a photo needs the version the screen showed");
    }
    if (expectedVersion != current.rowVersion()) {
      throw new DomainException(
          ErrorCode.VERSION_CONFLICT, "This person's photo changed since this screen loaded; reload and try again");
    }
    photos.remove(current.photoId(), expectedVersion, actor.userId(), clock.now(), why);
    metrics.increment("waypoint.iam.profile_photo", "outcome", "removed");
  }

  /**
   * The photo for a reader who works with the person today. Anyone else is
   * refused and the refusal audited; a person with no photo is not found, so the
   * screen draws initials.
   */
  public PhotoView forViewer(Actor actor, UUID userId) {
    LocalDate today = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate();
    record Read(boolean allowed, Optional<Photo> photo) {}
    Read read =
        database.readAs(
            ModuleRole.IAM,
            actor.userId(),
            () -> {
              boolean allowed =
                  ProfilePhotoRules.maySee(
                      actor.userId(), userId, photos.isAdministrator(actor.userId()), photos.depotsOf(actor.userId()),
                      depotsOn(userId, today));
              return new Read(allowed, allowed ? photos.current(userId) : Optional.empty());
            });
    if (!read.allowed()) {
      audit.recordStandalone(
          AuditEntry.denied(actor.userId(), actor.deviceId(), READ, resource(userId), "does not work with this person today"));
      metrics.increment("waypoint.iam.profile_photo", "outcome", "denied");
      throw new DomainException(ErrorCode.FORBIDDEN, "You do not work with this person today");
    }
    Photo photo = read.photo().orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No photo"));
    metrics.increment("waypoint.iam.profile_photo", "outcome", "read");
    return view(photo);
  }

  @Override
  public Optional<PhotoView> photoOf(UUID userId) {
    // A separate read: the caller is usually inside its own module's transaction.
    return database.readAs(ModuleRole.IAM, database.ambientActor().orElse(null), () -> photos.current(userId))
        .map(ProfilePhotos::view);
  }

  /** Who has a photo, for the administrator; never the image. */
  public List<PhotoStatusView> statuses(Actor actor) {
    List<Map<String, Object>> rows = database.readAs(ModuleRole.IAM, actor.userId(), photos::statuses);
    return rows.stream()
        .map(
            row ->
                new PhotoStatusView(
                    (UUID) row.get("user_id"),
                    ((Number) row.get("version")).longValue(),
                    Optional.ofNullable((UUID) row.get("photo_id")),
                    Optional.ofNullable((Timestamp) row.get("uploaded_at")).map(Timestamp::toInstant),
                    Optional.ofNullable((Number) row.get("size_bytes")).map(Number::intValue),
                    Optional.ofNullable((Number) row.get("row_version")).map(Number::longValue)))
        .toList();
  }

  /** The depot grants of the person and the depot of any vehicle they drive that day. */
  private Set<String> depotsOn(UUID userId, LocalDate day) {
    Set<String> depots = new HashSet<>(photos.depotsOf(userId));
    for (String vehicle : photos.vehiclesOn(userId, day)) {
      reference.vehicle(vehicle, null).map(VehicleView::depotCode).ifPresent(depots::add);
    }
    return depots;
  }

  private static PhotoView view(Photo photo) {
    return new PhotoView(photo.userId(), photo.photoId(), photo.contentType(), photo.sha256(), photo.uploadedAt(), photo.content());
  }

  static String resource(UUID userId) {
    return "wpt:iam:user:" + userId;
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
