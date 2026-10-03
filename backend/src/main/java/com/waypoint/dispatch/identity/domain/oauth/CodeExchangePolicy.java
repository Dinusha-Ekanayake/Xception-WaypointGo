package com.waypoint.dispatch.identity.domain.oauth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether a stored authorization code may become a token (R-IAM-31).
 *
 * <p>Every refusal is the same {@code invalid_grant} to the caller. The reason is
 * for the metric and the audit row only: telling a client which check failed
 * tells an attacker which part of a stolen code is still worth trying.
 */
public final class CodeExchangePolicy {
  private CodeExchangePolicy() {}

  /** What the code was issued for, as stored. */
  public record Issued(
      UUID clientId, String redirectUri, String codeChallenge, Instant expiresAt, Instant consumedAt) {}

  /** What the token request presented. */
  public record Presented(UUID clientId, String redirectUri, String codeVerifier) {}

  public enum Refusal {
    REPLAYED,
    EXPIRED,
    WRONG_CLIENT,
    WRONG_REDIRECT,
    WRONG_VERIFIER
  }

  /** Empty when the exchange may go ahead. A replay is reported before anything else. */
  public static Optional<Refusal> refusal(Issued issued, Presented presented, Instant now) {
    if (issued.consumedAt() != null) {
      return Optional.of(Refusal.REPLAYED);
    }
    if (!now.isBefore(issued.expiresAt())) {
      return Optional.of(Refusal.EXPIRED);
    }
    if (!issued.clientId().equals(presented.clientId())) {
      return Optional.of(Refusal.WRONG_CLIENT);
    }
    // The code remembers the exact redirect the user was sent to, port included.
    if (!issued.redirectUri().equals(presented.redirectUri())) {
      return Optional.of(Refusal.WRONG_REDIRECT);
    }
    if (!Pkce.verifies(presented.codeVerifier(), issued.codeChallenge())) {
      return Optional.of(Refusal.WRONG_VERIFIER);
    }
    return Optional.empty();
  }
}
