package com.waypoint.dispatch.identity.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Mints session tokens and hashes them for storage.
 *
 * <p>The token is 256 random bits, so a plain SHA-256 is enough: there is nothing
 * to guess and nothing a salt or a slow hash would add. What the hash buys is
 * that the table is no longer a list of usable credentials.
 */
@Component
public class SessionTokens {
  private static final int TOKEN_BYTES = 32;

  private final SecureRandom random = new SecureRandom();

  public String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Lowercase hex, the form {@code iam.sessions.token_hash} holds. */
  public String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
