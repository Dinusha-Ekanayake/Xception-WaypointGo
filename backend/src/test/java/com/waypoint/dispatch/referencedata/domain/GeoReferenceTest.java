package com.waypoint.dispatch.referencedata.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GeoReferenceTest {
  private static GeoPoint point(String precision) {
    return new GeoPoint(new BigDecimal("7.123456"), new BigDecimal("80.123456"), precision);
  }
  private static GeoReference.Row row(String kind, String code, String precision) {
    return new GeoReference.Row(kind, code, point(precision), "fixture source");
  }
  private static GeoReference geo(List<GeoReference.Row> rows) {
    return new GeoReference(rows, Set.of("Kandy"), Set.of("Kandy"), Set.of("OUT001", "OUT002"));
  }

  @Test
  void fallbackIsTheSamePointForEveryOutletInADistrict() {
    var data = geo(List.of(row("depot", "Kandy", "approximate"), row("district", "Kandy", "centroid")));
    assertEquals(point("district"), data.outlet("OUT001", "Kandy"));
    assertEquals(data.outlet("OUT001", "Kandy"), data.outlet("OUT002", "Kandy"));
    assertThrows(IllegalArgumentException.class, () -> data.outlet("OUT001", "Unknown"));
  }

  @Test
  void missingDepotOrWrongDistrictPrecisionRefusesTheDataset() {
    assertThrows(IllegalArgumentException.class, () -> geo(List.of(row("district", "Kandy", "centroid"))));
    assertThrows(IllegalArgumentException.class, () -> geo(List.of(
        row("depot", "Kandy", "approximate"), row("district", "Kandy", "exact"))));
  }

  @Test
  void coordinateBoundsAreInclusiveAndMustFitStoredPrecision() {
    assertDoesNotThrow(() -> new GeoPoint(new BigDecimal("90"), new BigDecimal("180"), "exact"));
    assertDoesNotThrow(() -> new GeoPoint(new BigDecimal("-90"), new BigDecimal("-180"), "exact"));
    assertThrows(IllegalArgumentException.class, () -> new GeoPoint(null, BigDecimal.ZERO, "exact"));
    assertThrows(IllegalArgumentException.class, () -> new GeoPoint(BigDecimal.ZERO, new BigDecimal("180.000001"), "exact"));
    assertThrows(IllegalArgumentException.class, () -> new GeoPoint(BigDecimal.ZERO, BigDecimal.ZERO, "guessed"));
  }

  @Test
  void storeDetailsNeverDiscardThePublishedPoint() {
    var published = new Outlet("OUT001", "Fresh", "Kandy", DockType.STREET, ParkingConstraint.NORMAL,
        new DeliveryWindow(LocalTime.of(5, 0), LocalTime.of(9, 0)), Optional.empty(), Optional.of(point("exact")));
    var details = new OutletDetails("OUT001", Optional.of(new DeliveryWindow(LocalTime.of(6, 0), LocalTime.of(8, 0))),
        Optional.empty(), null, null, null);
    assertEquals(Optional.of(point("exact")), details.applyTo(published).location());
  }
}
