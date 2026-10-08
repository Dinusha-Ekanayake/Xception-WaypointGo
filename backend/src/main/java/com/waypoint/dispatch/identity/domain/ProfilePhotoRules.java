package com.waypoint.dispatch.identity.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.ImageKind;
import java.util.Set;
import java.util.UUID;

/**
 * Roadmap FF-07 and policy P-PD-1: what a profile photo must be, and who may see
 * one. A photo is personal data, so it is small, stored only with the person's
 * consent on record, and shown only to the people who work with them.
 */
public final class ProfilePhotoRules {
  /** A face at avatar size; the screen shrinks the camera picture before sending it. */
  public static final int MAX_BYTES = 256 * 1024;

  private ProfilePhotoRules() {}

  public static ImageKind requireImage(byte[] content) {
    if (content == null || content.length == 0) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "The photo is empty");
    }
    if (content.length > MAX_BYTES) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "The photo is larger than 256 KB");
    }
    return ImageKind.sniff(content)
        .orElseThrow(() -> new DomainException(ErrorCode.VALIDATION_FAILED, "The photo must be a JPEG, PNG or WebP image"));
  }

  /** How the person gave consent, in a few words: a photo without it is refused. */
  public static String requireConsent(String note) {
    return requireText(note, "Say how the person gave consent to their photo");
  }

  public static String requireReason(String reason) {
    return requireText(reason, "Give a reason for removing the photo");
  }

  /**
   * Whether {@code viewer} may see {@code subject}'s photo: themselves, an
   * administrator, someone who shares a depot with them, or a person at a depot
   * whose vehicle the subject drives today. A store sees its driver through
   * Execution, which knows the delivery.
   */
  public static boolean maySee(
      UUID viewer, UUID subject, boolean viewerIsAdmin, Set<String> viewerDepots, Set<String> subjectDepotsToday) {
    if (viewer.equals(subject) || viewerIsAdmin) {
      return true;
    }
    return subjectDepotsToday.stream().anyMatch(viewerDepots::contains);
  }

  private static String requireText(String text, String message) {
    String trimmed = text == null ? "" : text.strip();
    if (trimmed.length() < 3 || trimmed.length() > 300) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, message + " (3 to 300 characters)");
    }
    return trimmed;
  }
}
