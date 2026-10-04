package com.waypoint.dispatch.identity.domain.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Proof Key for Code Exchange, S256 only (RFC 7636).
 *
 * <p>The client sends a hash when the user signs in and the value behind it when
 * it collects the token, so a code lifted from a redirect is useless to whoever
 * lifted it. The plain method is not accepted: it would make the challenge the
 * secret itself.
 */
public final class Pkce {
  public static final String METHOD = "S256";
  private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

  private Pkce() {}

  /** A base64url SHA-256, which is always 43 characters. */
  public static boolean validChallenge(String challenge) {
    return challenge != null && CHALLENGE.matcher(challenge).matches();
  }

  public static boolean verifies(String verifier, String challenge) {
    if (verifier == null || !VERIFIER.matcher(verifier).matches() || !validChallenge(challenge)) {
      return false;
    }
    return MessageDigest.isEqual(
        challenge.getBytes(StandardCharsets.US_ASCII),
        challengeOf(verifier).getBytes(StandardCharsets.US_ASCII));
  }

  public static String challengeOf(String verifier) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
