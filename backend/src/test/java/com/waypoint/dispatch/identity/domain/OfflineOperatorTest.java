package com.waypoint.dispatch.identity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.identity.domain.OfflineSwitchPolicy.Switch;
import com.waypoint.dispatch.shared.error.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfflineOperatorTest {
  private static final byte[] SALT = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
  private static final Instant NOW = Instant.parse("2026-10-01T20:00:00Z");
  private static final UUID ISURU = UUID.fromString("00000000-0000-7000-8000-000000000001");
  private static final UUID KASUN = UUID.fromString("00000000-0000-7000-8000-000000000002");
  private static final Set<UUID> CREW = Set.of(ISURU, KASUN);

  /** The same vector frontend/tests/loader-offline-pin.test.ts derives with WebCrypto. */
  static final String VECTOR = "pbkdf2-sha256$1000$AQIDBAUGBwgJCgsMDQ4PEA==$";
  static final String FULL_VECTOR = VECTOR + "q/XmQuQ246Dwn9znhO4Ubr59wowIXIZcj+uxlcc2AwQ=";

  @Test
  void aVerifierChecksThePinWithoutHoldingIt() {
    String verifier = PinVerifier.create("2468", SALT, 1_000);
    assertEquals(FULL_VECTOR, verifier);
    assertFalse(verifier.substring(VECTOR.length()).contains("2468"));
    assertTrue(PinVerifier.matches("2468", verifier));
    assertFalse(PinVerifier.matches("2469", verifier));
    assertFalse(PinVerifier.matches("2468", "garbage"));
    assertFalse(PinVerifier.matches("2468", "pbkdf2-sha256$0$AA==$AA=="));
  }

  @Test
  void theDerivationIsStandardPbkdf2HmacSha256() {
    // RFC 7914 section 11 test vector: P="passwd", S="salt", c=1, first 32 bytes.
    String v = "pbkdf2-sha256$1$" + java.util.Base64.getEncoder().encodeToString("salt".getBytes()) + "$"
        + java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"));
    assertTrue(PinVerifier.matches("passwd", v));
  }

  @Test
  void offlineSwitchesReplayInOrderForCrewOnly() {
    var switches = List.of(
        new Switch(Optional.of(KASUN), NOW.minusSeconds(600)),
        new Switch(Optional.empty(), NOW.minusSeconds(300)),
        new Switch(Optional.of(ISURU), NOW.minusSeconds(120)));
    OfflineSwitchPolicy.requireReplayable(switches, CREW, Optional.of(NOW.minusSeconds(3600)), NOW);
  }

  @Test
  void aSwitchToSomeoneOffTheCrewIsRefused() {
    var switches = List.of(new Switch(Optional.of(UUID.randomUUID()), NOW.minusSeconds(60)));
    DomainException e = assertThrows(DomainException.class,
        () -> OfflineSwitchPolicy.requireReplayable(switches, CREW, Optional.empty(), NOW));
    assertEquals(List.of("R-IAM-27"), e.rules());
  }

  @Test
  void switchesCannotRewriteHistoryTheServerAlreadyHas() {
    var switches = List.of(new Switch(Optional.of(KASUN), NOW.minusSeconds(600)));
    assertThrows(DomainException.class,
        () -> OfflineSwitchPolicy.requireReplayable(switches, CREW, Optional.of(NOW.minusSeconds(300)), NOW));
  }

  @Test
  void switchesMustBeInOrderRecentAndNotInTheFuture() {
    var outOfOrder = List.of(
        new Switch(Optional.of(KASUN), NOW.minusSeconds(60)), new Switch(Optional.of(ISURU), NOW.minusSeconds(120)));
    var stale = List.of(
        new Switch(Optional.of(KASUN), NOW.minus(OfflineSwitchPolicy.CREW_LIST_LIFETIME).minusSeconds(1)));
    var future = List.of(new Switch(Optional.of(KASUN), NOW.plus(Duration.ofMinutes(10))));
    for (var s : List.of(outOfOrder, stale, future, List.<Switch>of())) {
      assertThrows(DomainException.class,
          () -> OfflineSwitchPolicy.requireReplayable(s, CREW, Optional.empty(), NOW));
    }
  }
}
