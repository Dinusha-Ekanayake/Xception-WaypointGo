package com.waypoint.dispatch.warehouse.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which warehouse serves a depot. Each depot orders from its own warehouse and an
 * order is never split (A-25). The warehouse accepts the depot name as well as
 * the code, but listings answer with the code, so matching needs the code.
 */
public final class WarehouseCode {
  private WarehouseCode() {}

  private static final Map<String, String> BY_DEPOT =
      Map.of("kandy", "KDY", "kdy", "KDY", "peliyagoda", "PLG", "plg", "PLG");

  public static Optional<String> forDepot(String depotCode) {
    if (depotCode == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(BY_DEPOT.get(depotCode.trim().toLowerCase(Locale.ROOT)));
  }
}
