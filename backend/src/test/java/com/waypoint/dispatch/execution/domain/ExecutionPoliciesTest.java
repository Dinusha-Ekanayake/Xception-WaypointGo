package com.waypoint.dispatch.execution.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.shared.util.ImageKind;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ETA shift, proof links and image sniffing: pure, so no database and no clock. */
class ExecutionPoliciesTest {
  private static final Instant PLANNED = Instant.parse("2027-03-01T04:00:00Z");

  @Test
  void theDelayIsHowFarBehindPlanTheArrivalWasAndNeverNegative() {
    assertEquals(25, EtaPolicy.delayMinutes(PLANNED.plusSeconds(25 * 60 + 40), PLANNED));
    assertEquals(0, EtaPolicy.delayMinutes(PLANNED, PLANNED));
    assertEquals(0, EtaPolicy.delayMinutes(PLANNED.minusSeconds(900), PLANNED), "ahead of plan is not announced as early");
  }

  @Test
  void aDelayIsAnnouncedOnlyWhenItHasMovedTenMinutes() {
    assertFalse(EtaPolicy.worthAnnouncing(9, 0));
    assertTrue(EtaPolicy.worthAnnouncing(10, 0));
    assertFalse(EtaPolicy.worthAnnouncing(34, 25));
    assertTrue(EtaPolicy.worthAnnouncing(35, 25));
    assertTrue(EtaPolicy.worthAnnouncing(5, 25), "catching up is news too");
  }

  @Test
  void theExpectedArrivalIsThePlanShiftedByTheDelay() {
    assertEquals(PLANNED.plusSeconds(25 * 60), EtaPolicy.expectedArrival(PLANNED, 25));
  }

  @Test
  void aProofLinkOpensItsOwnArtifactUntilItExpiresAndNothingElse() {
    ProofLink link = new ProofLink("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    UUID attachment = UUID.randomUUID();
    Instant now = Instant.parse("2027-03-01T05:00:00Z");
    Instant expires = now.plusSeconds(300);
    String signature = link.sign(attachment, expires);

    assertTrue(link.verify(attachment, expires.getEpochSecond(), signature, now));
    assertTrue(link.verify(attachment, expires.getEpochSecond(), signature, expires));
    assertFalse(link.verify(attachment, expires.getEpochSecond(), signature, expires.plusSeconds(1)), "expired");
    assertFalse(link.verify(UUID.randomUUID(), expires.getEpochSecond(), signature, now), "another artifact");
    assertFalse(link.verify(attachment, expires.getEpochSecond() + 3600, signature, now), "a stretched expiry");
    assertFalse(link.verify(attachment, expires.getEpochSecond(), "zz", now), "not hex");
    assertFalse(link.verify(attachment, expires.getEpochSecond(), null, now));

    ProofLink otherKey = new ProofLink("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
    assertFalse(otherKey.verify(attachment, expires.getEpochSecond(), signature, now), "signed by another key");
    assertThrows(IllegalArgumentException.class, () -> new ProofLink(new byte[8]));
  }

  @Test
  void anImageIsRecognisedFromItsBytesNotItsLabel() {
    assertEquals(Optional.of(ImageKind.JPEG), ImageKind.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0)));
    assertEquals(Optional.of(ImageKind.PNG), ImageKind.sniff(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0)));
    assertEquals(
        Optional.of(ImageKind.WEBP),
        ImageKind.sniff(bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P', 'V', 'P')));
    assertEquals(Optional.empty(), ImageKind.sniff("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8)));
    assertEquals(Optional.empty(), ImageKind.sniff(bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E')));
    assertEquals(Optional.empty(), ImageKind.sniff(new byte[0]));
    assertEquals(Optional.empty(), ImageKind.sniff(null));
  }

  private static byte[] bytes(int... values) {
    byte[] out = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = (byte) values[i];
    }
    return out;
  }
}
