package com.waypoint.dispatch.shared.util;

import java.util.Optional;

/**
 * The image types accepted as evidence (proof of delivery, photos of a delivery
 * problem), recognised from the bytes themselves. The Content-Type header is the
 * client's claim; the first bytes are the file.
 */
public enum ImageKind {
  JPEG("image/jpeg"),
  PNG("image/png"),
  WEBP("image/webp");

  private final String contentType;

  ImageKind(String contentType) {
    this.contentType = contentType;
  }

  public String contentType() {
    return contentType;
  }

  public static Optional<ImageKind> sniff(byte[] content) {
    if (content == null) {
      return Optional.empty();
    }
    if (starts(content, 0xFF, 0xD8, 0xFF)) {
      return Optional.of(JPEG);
    }
    if (starts(content, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
      return Optional.of(PNG);
    }
    if (content.length >= 12
        && starts(content, 'R', 'I', 'F', 'F')
        && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
      return Optional.of(WEBP);
    }
    return Optional.empty();
  }

  private static boolean starts(byte[] content, int... prefix) {
    if (content.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if ((content[i] & 0xFF) != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
