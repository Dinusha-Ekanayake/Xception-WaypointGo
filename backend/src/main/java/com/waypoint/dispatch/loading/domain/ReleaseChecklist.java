package com.waypoint.dispatch.loading.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the loader confirms before the vehicle leaves (Figma 04 Confirm and
 * release): orders secured with the first stop at the doors, doors closed and
 * sealed with the seal number, and the driver at the vehicle.
 *
 * <p>A chilled trip also needs the reefer box at or below {@link #MAX_REEFER_C}
 * (R-LOD-10, workflow map E10). The reading is typed by the loader; there is no
 * sensor feed.
 */
public record ReleaseChecklist(
    boolean ordersSecured, String sealNumber, boolean driverPresent, Optional<BigDecimal> reeferTempC) {

  public static final BigDecimal MAX_REEFER_C = new BigDecimal("4.0");

  /** Refuses with every failed item named, so the screen can show them all at once. */
  public void requireSatisfied(boolean chilled) {
    List<String> failed = new ArrayList<>();
    List<String> rules = new ArrayList<>();
    if (!ordersSecured) {
      failed.add("orders are not marked secured");
      rules.add("R-LOD-10");
    }
    if (sealNumber == null || sealNumber.isBlank()) {
      failed.add("the seal number is missing");
      rules.add("R-LOD-10");
    }
    if (!driverPresent) {
      failed.add("the driver is not marked at the vehicle");
      rules.add("R-LOD-10");
    }
    if (chilled) {
      if (reeferTempC.isEmpty()) {
        failed.add("a chilled trip needs the reefer temperature");
        rules.add("R-LOD-10");
      } else if (reeferTempC.get().compareTo(MAX_REEFER_C) > 0) {
        failed.add("the reefer is at " + reeferTempC.get().toPlainString() + " °C, above "
            + MAX_REEFER_C.toPlainString() + " °C");
        rules.add("R-LOD-10");
      }
    }
    if (!failed.isEmpty()) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "Can't release yet: " + String.join("; ", failed) + ".",
          List.copyOf(new java.util.LinkedHashSet<>(rules)));
    }
  }
}
