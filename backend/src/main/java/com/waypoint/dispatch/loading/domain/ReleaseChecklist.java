package com.waypoint.dispatch.loading.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;

/**
 * What the loader confirms before the vehicle leaves (Figma 04 Confirm and
 * release): doors sealed, orders secured, and driver at the vehicle.
 */
public record ReleaseChecklist(boolean doorsSealed, boolean ordersSecured, boolean driverPresent) {

  /** Refuses with every failed item named, so the screen can show them all at once. */
  public void requireSatisfied() {
    List<String> failed = new ArrayList<>();
    List<String> rules = new ArrayList<>();
    if (!doorsSealed) {
      failed.add("doors are not marked sealed");
      rules.add("R-LOD-10");
    }
    if (!ordersSecured) {
      failed.add("orders are not marked secured");
      rules.add("R-LOD-10");
    }
    if (!driverPresent) {
      failed.add("the driver is not marked at the vehicle");
      rules.add("R-LOD-10");
    }
    if (!failed.isEmpty()) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "Can't release yet: " + String.join("; ", failed) + ".",
          List.copyOf(new java.util.LinkedHashSet<>(rules)));
    }
  }
}
