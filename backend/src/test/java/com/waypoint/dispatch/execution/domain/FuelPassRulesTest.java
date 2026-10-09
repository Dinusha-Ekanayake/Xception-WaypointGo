package com.waypoint.dispatch.execution.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.ImageKind;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** FF-06b: what a Fuel Pass upload and revocation must satisfy, with no database. */
class FuelPassRulesTest {

  private static byte[] png(int size) {
    byte[] out = new byte[size];
    byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(header, 0, out, 0, header.length);
    return out;
  }

  @Test
  void anImageIsReadFromItsBytesAndRefusedWhenEmptyTooLargeOrNotAnImage() {
    assertEquals(ImageKind.PNG, FuelPassRules.requireImage(png(64)));
    assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(DomainException.class, () -> FuelPassRules.requireImage(new byte[0])).code());
    assertEquals(
        ErrorCode.PAYLOAD_TOO_LARGE,
        assertThrows(DomainException.class, () -> FuelPassRules.requireImage(png(FuelPassRules.MAX_BYTES + 1))).code());
    assertEquals(
        ErrorCode.VALIDATION_FAILED,
        assertThrows(DomainException.class, () -> FuelPassRules.requireImage("%PDF-1.7 not an image".getBytes())).code());
  }

  @Test
  void aWithdrawalNeedsAReason() {
    assertEquals("QR reissued", FuelPassRules.requireReason("  QR reissued "));
    assertThrows(DomainException.class, () -> FuelPassRules.requireReason(" x "));
    assertThrows(DomainException.class, () -> FuelPassRules.requireReason(null));
    assertThrows(DomainException.class, () -> FuelPassRules.requireReason("x".repeat(301)));
  }

  @Test
  void thePhoneKeepsThePassToTheLastDayItsDriverDrivesTheVehicle() {
    LocalDate today = LocalDate.of(2026, 10, 7);
    assertEquals(today, FuelPassRules.validUntil(today, false));
    assertEquals(today.plusDays(1), FuelPassRules.validUntil(today, true));
  }
}
