package com.waypoint.dispatch.shared.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.error.Violation;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * The opaque keyset cursor every paginated read hands back.
 *
 * <p>Encodes the sort key of the last row returned. Opaque so a client cannot
 * build one by hand and come to depend on its shape, and so the key can change
 * without an API version.
 *
 * <p>Opaque is not secret: base64 is an encoding. The key parts must therefore be
 * non-personal, such as ids, codes and dates, never an email or a name. The
 * cursor travels in a query string and lands in access logs.
 */
public final class Cursor {
  private static final String SEPARATOR = "\u001f";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private Cursor() {}

  public static String encode(String... keyParts) {
    return ENCODER.encodeToString(String.join(SEPARATOR, keyParts).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * @return the key parts, or an empty list when {@code cursor} is absent (the first page)
   * @throws DomainException {@code BAD_REQUEST} when the cursor was not one this API issued
   */
  public static List<String> decode(String cursor, int expectedParts) {
    if (cursor == null || cursor.isBlank()) {
      return List.of();
    }
    try {
      String raw = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
      List<String> parts = Arrays.asList(raw.split(SEPARATOR, -1));
      if (parts.size() != expectedParts) {
        throw invalid();
      }
      return List.copyOf(parts);
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  /** For a cursor that decodes but whose key does not parse: forged, not issued here. */
  public static DomainException invalid() {
    return DomainException.withViolations(
        ErrorCode.BAD_REQUEST,
        "after is not a cursor this API issued",
        List.of(Violation.onField("request:cursor", "after", "unrecognised cursor")));
  }
}
