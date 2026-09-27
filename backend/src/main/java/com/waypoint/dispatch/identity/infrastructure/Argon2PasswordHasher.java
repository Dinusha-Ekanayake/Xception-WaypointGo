package com.waypoint.dispatch.identity.infrastructure;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id password hashing.
 *
 * <p>The parameters are encoded into the hash itself, so they can be raised
 * later without invalidating existing passwords: an old hash keeps verifying
 * under its own parameters while new ones use the stronger settings.
 *
 * <p>Verification is deliberately not short-circuited on an unknown account.
 * See {@link #verifyDummy()}: skipping the hash when no user exists makes login
 * measurably faster for unknown emails, which hands an attacker a way to
 * enumerate valid accounts.
 */
@Component
public class Argon2PasswordHasher {
  /** Defaults from Spring Security: 16 byte salt, 32 byte hash, 1 iteration, 4096 KB, 2 lanes. */
  private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

  /** A hash of a value nobody knows, used to spend the same time on a missing account. */
  private final String dummyHash = encoder.encode("waypoint-timing-equaliser");

  public String hash(String rawPassword) {
    return encoder.encode(rawPassword);
  }

  public boolean matches(String rawPassword, String storedHash) {
    return encoder.matches(rawPassword, storedHash);
  }

  /** Burns the same work as a real verification so timing does not reveal account existence. */
  public void verifyDummy(String rawPassword) {
    encoder.matches(rawPassword, dummyHash);
  }
}
