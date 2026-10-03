package com.waypoint.dispatch.receipt.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The one-time PIN the store shows and the driver types on their own phone
 * (R-RCP-09).
 *
 * <p>It is evidence that the person who handed the goods over stood at the store
 * when it was entered. It is never a gate: nothing about the trip, the delivery
 * or the receipt waits for it, and a handover nobody confirms is shown as not
 * confirmed, not as a failure.
 *
 * <p>The PIN has four digits, so the hash does not protect it; the limits do:
 * {@link #MAX_ATTEMPTS} wrong entries lock it, it expires after {@link #LIFETIME},
 * and the store can issue a new one, which starts the count again. Only a salted
 * hash is held, and it is compared in constant time.
 *
 * <p>Pure: the PIN, the salt and the time are arguments. Whoever calls this
 * decides how a PIN is drawn; {@link #newPin} is the one way, given a source of
 * randomness.
 *
 * @param vehicleId the vehicle that carried the order, copied from the delivery
 *     record so the driver's reach (the vehicle on that date) can be decided here
 * @param attempts wrong entries since the PIN was issued
 */
public record Handover(
    UUID receiptId,
    UUID orderId,
    String outletId,
    String depotCode,
    String vehicleId,
    LocalDate serviceDate,
    String salt,
    String pinHash,
    State state,
    int attempts,
    Instant issuedAt,
    Instant expiresAt,
    Optional<Instant> confirmedAt,
    Optional<UUID> confirmedBy,
    long rowVersion) {

  public enum State {
    AWAITING,
    CONFIRMED,
    LOCKED
  }

  public enum Outcome {
    /** The PIN matched: the handover is confirmed. */
    VERIFIED,
    /** Confirmed earlier; entering it again changes nothing. */
    ALREADY_CONFIRMED,
    WRONG,
    /** The fifth wrong entry, or an entry on a PIN already locked. */
    LOCKED,
    EXPIRED
  }

  /** What an entry did: the handover afterwards, and what to tell the driver. */
  public record Verification(Handover next, Outcome outcome) {

    public int attemptsLeft() {
      return Math.max(0, MAX_ATTEMPTS - next.attempts());
    }
  }

  public static final int PIN_LENGTH = 4;
  public static final int MAX_ATTEMPTS = 5;
  public static final Duration LIFETIME = Duration.ofMinutes(15);
  private static final Pattern WELL_FORMED = Pattern.compile("\\d{" + PIN_LENGTH + "}");

  /** A PIN drawn from {@code uniform}, which returns a number in {@code [0, bound)}. */
  public static String newPin(java.util.function.IntUnaryOperator uniform) {
    return String.format("%0" + PIN_LENGTH + "d", uniform.applyAsInt((int) Math.pow(10, PIN_LENGTH)));
  }

  public static boolean wellFormed(String pin) {
    return pin != null && WELL_FORMED.matcher(pin).matches();
  }

  /** The handover a recorded delivery's answered receipt opens. */
  public static Handover issue(
      UUID receiptId,
      UUID orderId,
      String outletId,
      String depotCode,
      String vehicleId,
      LocalDate serviceDate,
      String pin,
      String salt,
      Instant now) {
    requireWellFormed(pin);
    return new Handover(
        receiptId, orderId, outletId, depotCode, vehicleId, serviceDate, salt, hash(salt, pin), State.AWAITING, 0,
        now, now.plus(LIFETIME), Optional.empty(), Optional.empty(), 1);
  }

  /**
   * A new PIN, from the store, because the first was lost, expired or locked. A
   * confirmed handover is final: there is nothing left to confirm.
   */
  public Handover reissue(String pin, String newSalt, Instant now) {
    if (state == State.CONFIRMED) {
      throw new DomainException(
          ErrorCode.CONFLICT, "the handover for order " + orderId + " is already confirmed", List.of("R-RCP-09"));
    }
    requireWellFormed(pin);
    return new Handover(
        receiptId, orderId, outletId, depotCode, vehicleId, serviceDate, newSalt, hash(newSalt, pin), State.AWAITING, 0,
        now, now.plus(LIFETIME), Optional.empty(), Optional.empty(), rowVersion);
  }

  /**
   * The driver's entry. A wrong or late entry is an answer, not an error: it is
   * counted and recorded, which a thrown refusal would roll back. A PIN that is
   * not four digits is a malformed request and counts for nothing.
   */
  public Verification verify(String pin, UUID driver, Instant now) {
    requireWellFormed(pin);
    if (state == State.CONFIRMED) {
      return new Verification(this, Outcome.ALREADY_CONFIRMED);
    }
    if (state == State.LOCKED) {
      return new Verification(this, Outcome.LOCKED);
    }
    if (!now.isBefore(expiresAt)) {
      return new Verification(this, Outcome.EXPIRED);
    }
    if (matches(pin)) {
      Handover confirmed =
          new Handover(
              receiptId, orderId, outletId, depotCode, vehicleId, serviceDate, salt, pinHash, State.CONFIRMED,
              attempts, issuedAt, expiresAt, Optional.of(now), Optional.of(driver), rowVersion);
      return new Verification(confirmed, Outcome.VERIFIED);
    }
    int wrong = attempts + 1;
    boolean locks = wrong >= MAX_ATTEMPTS;
    Handover next =
        new Handover(
            receiptId, orderId, outletId, depotCode, vehicleId, serviceDate, salt, pinHash,
            locks ? State.LOCKED : State.AWAITING, wrong, issuedAt, expiresAt, confirmedAt, confirmedBy, rowVersion);
    return new Verification(next, locks ? Outcome.LOCKED : Outcome.WRONG);
  }

  /** What a screen shows: a PIN that is awaiting but past its time reads as expired. */
  public Status status(Instant now) {
    return switch (state) {
      case CONFIRMED -> Status.CONFIRMED;
      case LOCKED -> Status.LOCKED;
      case AWAITING -> now.isBefore(expiresAt) ? Status.AWAITING : Status.EXPIRED;
    };
  }

  public enum Status {
    AWAITING,
    CONFIRMED,
    LOCKED,
    EXPIRED
  }

  // ---- internals -----------------------------------------------------------

  private boolean matches(String pin) {
    return MessageDigest.isEqual(
        hash(salt, pin).getBytes(StandardCharsets.UTF_8), pinHash.getBytes(StandardCharsets.UTF_8));
  }

  private static void requireWellFormed(String pin) {
    if (!wellFormed(pin)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "the PIN is " + PIN_LENGTH + " digits", List.of("R-RCP-09"));
    }
  }

  static String hash(String salt, String pin) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
    }
  }
}
