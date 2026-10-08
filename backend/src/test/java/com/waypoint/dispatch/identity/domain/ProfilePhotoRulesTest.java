package com.waypoint.dispatch.identity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.util.ImageKind;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfilePhotoRulesTest {
  final UUID viewer = UUID.randomUUID();
  final UUID subject = UUID.randomUUID();

  @Test
  void aPhotoIsASmallImageRecognisedByItsBytes() {
    byte[] jpeg = new byte[1000];
    jpeg[0] = (byte) 0xFF;
    jpeg[1] = (byte) 0xD8;
    jpeg[2] = (byte) 0xFF;
    assertEquals(ImageKind.JPEG, ProfilePhotoRules.requireImage(jpeg));
    assertThrows(DomainException.class, () -> ProfilePhotoRules.requireImage(new byte[0]));
    assertThrows(DomainException.class, () -> ProfilePhotoRules.requireImage("GIF89a not an accepted image".getBytes()));
    byte[] large = new byte[ProfilePhotoRules.MAX_BYTES + 1];
    System.arraycopy(jpeg, 0, large, 0, 3);
    assertThrows(DomainException.class, () -> ProfilePhotoRules.requireImage(large));
  }

  @Test
  void consentAndARemovalReasonAreSaidInWords() {
    assertEquals("Signed the form", ProfilePhotoRules.requireConsent("  Signed the form "));
    assertThrows(DomainException.class, () -> ProfilePhotoRules.requireConsent(" "));
    assertThrows(DomainException.class, () -> ProfilePhotoRules.requireConsent(null));
    assertThrows(DomainException.class, () -> ProfilePhotoRules.requireReason("x".repeat(301)));
  }

  @Test
  void onlyThePeopleWhoWorkWithThePersonTodaySeeThePhoto() {
    assertTrue(ProfilePhotoRules.maySee(subject, subject, false, Set.of(), Set.of()), "themselves");
    assertTrue(ProfilePhotoRules.maySee(viewer, subject, true, Set.of(), Set.of()), "an administrator");
    assertTrue(ProfilePhotoRules.maySee(viewer, subject, false, Set.of("D1", "D2"), Set.of("D2")), "the same depot");
    assertFalse(ProfilePhotoRules.maySee(viewer, subject, false, Set.of("D1"), Set.of("D2")), "another depot");
    assertFalse(ProfilePhotoRules.maySee(viewer, subject, false, Set.of(), Set.of()), "no depot in common");
  }
}
