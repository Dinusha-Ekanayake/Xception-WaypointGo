package com.waypoint.dispatch.identity.contract;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A person's current profile photo, for a module that has already decided its
 * reader works with that person (FF-07): Execution shows a store the driver
 * delivering to it today. The caller records the read in its own audit.
 */
public interface ProfilePhotoQuery {
  String SET = "iam:SetProfilePhoto";
  String REMOVE = "iam:RemoveProfilePhoto";
  String READ = "iam:ReadProfilePhoto";

  Optional<PhotoView> photoOf(UUID userId);

  /** The image as stored, with its type and digest so a reader can cache it by content. */
  record PhotoView(UUID userId, UUID photoId, String contentType, String sha256, Instant uploadedAt, byte[] content) {}

  /** For the administrator: whether a person has a photo now, never the image. {@code version} counts every photo they had. */
  record PhotoStatusView(
      UUID userId,
      long version,
      Optional<UUID> photoId,
      Optional<Instant> uploadedAt,
      Optional<Integer> sizeBytes,
      Optional<Long> photoRowVersion) {}
}
