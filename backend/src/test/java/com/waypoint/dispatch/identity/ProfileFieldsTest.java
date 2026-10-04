package com.waypoint.dispatch.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.waypoint.dispatch.identity.domain.ProfileFields;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R-IAM-32: what a person may say about themselves, normalised once. */
class ProfileFieldsTest {

  @Test
  void aNameIsTrimmedWithItsInnerSpacesCollapsed() {
    assertEquals("Nuwan Perera", ProfileFields.name("  Nuwan   Perera "));
  }

  @Test
  void aNameIsRequiredAndAtMostEightyCharacters() {
    DomainException blank = assertThrows(DomainException.class, () -> ProfileFields.name("   "));
    assertEquals(ErrorCode.VALIDATION_FAILED, blank.code());
    assertEquals(List.of("R-IAM-32"), blank.rules());
    assertThrows(DomainException.class, () -> ProfileFields.name("x".repeat(81)));
    assertEquals(80, ProfileFields.name("x".repeat(80)).length());
  }

  @Test
  void aPhoneNumberKeepsItsDigitsAndLeadingPlusAndABlankOneClearsIt() {
    assertEquals("+94771234567", ProfileFields.phone("+94 77 123-4567"));
    assertEquals("0771234567", ProfileFields.phone("(077) 123.4567"));
    assertNull(ProfileFields.phone("  "));
    assertNull(ProfileFields.phone(null));
  }

  @Test
  void aNumberThatIsNotSevenToFifteenDigitsIsRefused() {
    assertThrows(DomainException.class, () -> ProfileFields.phone("12ab"));
    assertThrows(DomainException.class, () -> ProfileFields.phone("123456"));
    assertThrows(DomainException.class, () -> ProfileFields.phone("1234567890123456"));
    assertThrows(DomainException.class, () -> ProfileFields.phone("94+771234567"));
  }
}
