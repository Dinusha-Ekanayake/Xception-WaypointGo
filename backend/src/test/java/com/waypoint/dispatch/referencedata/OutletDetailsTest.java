package com.waypoint.dispatch.referencedata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.referencedata.domain.DeliveryWindow;
import com.waypoint.dispatch.referencedata.domain.DockType;
import com.waypoint.dispatch.referencedata.domain.Outlet;
import com.waypoint.dispatch.referencedata.domain.OutletDetails;
import com.waypoint.dispatch.referencedata.domain.ParkingConstraint;
import com.waypoint.dispatch.shared.error.DomainException;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** R-REF-01: what a store says about itself, laid over the published outlet. */
class OutletDetailsTest {

  private static final Outlet REAR =
      new Outlet("OUT005", "Fresh", "Colombo", DockType.REAR_DOCK, ParkingConstraint.NORMAL, window("04:00", "07:45"), Optional.empty());

  private static final Outlet MALL =
      new Outlet(
          "OUT015", "Style", "Colombo", DockType.MALL_BAY, ParkingConstraint.MALL_DOCK, window("09:00", "11:00"), Optional.of(window("09:00", "11:00")));

  @Test
  void aStoresWindowAndDockReplaceThePublishedOnesAndItsContactsAreKept() {
    OutletDetails details = OutletDetails.of(REAR, t("05:30"), t("08:00"), "street", " Nuwan ", "077 123 4567", "Ring the bell");
    Outlet seen = details.applyTo(REAR);
    assertEquals(window("05:30", "08:00"), seen.window());
    assertEquals(DockType.STREET, seen.dockType());
    assertEquals("Nuwan", details.contactName());
    assertEquals("0771234567", details.contactPhone());
    assertEquals("Ring the bell", details.receivingNotes());
  }

  @Test
  void blanksAndThePublishedValuesThemselvesLeaveThePublishedOutletAsItIs() {
    OutletDetails same = OutletDetails.of(REAR, t("04:00"), t("07:45"), "rear_dock", "", null, "  ");
    assertTrue(same.window().isEmpty(), "the published window is not stored again");
    assertTrue(same.dockType().isEmpty());
    assertNull(same.contactName());
    assertNull(same.receivingNotes());
    Outlet seen = OutletDetails.of(REAR, null, null, null, null, null, null).applyTo(REAR);
    assertEquals(REAR, seen);
  }

  @Test
  void aWindowNeedsBothEndsInOrder() {
    assertThrows(DomainException.class, () -> OutletDetails.of(REAR, t("05:00"), null, null, null, null, null));
    assertThrows(DomainException.class, () -> OutletDetails.of(REAR, t("08:00"), t("06:00"), null, null, null, null));
  }

  @Test
  void aMallBayBelongsToTheBuilding() {
    DomainException leave = assertThrows(DomainException.class, () -> OutletDetails.of(MALL, null, null, "street", null, null, null));
    assertEquals(List.of("R-REF-01"), leave.rules());
    assertThrows(DomainException.class, () -> OutletDetails.of(REAR, null, null, "mall_bay", null, null, null));
    assertTrue(OutletDetails.of(MALL, null, null, "mall_bay", null, null, null).dockType().isEmpty());
  }

  @Test
  void aMallOutletsWindowMustStillOverlapTheMalls() {
    DomainException outside = assertThrows(DomainException.class, () -> OutletDetails.of(MALL, t("12:00"), t("13:00"), null, null, null, null));
    assertEquals(List.of("R-REF-01", "R-PLN-29"), outside.rules());
    Outlet seen = OutletDetails.of(MALL, t("08:00"), t("10:00"), null, null, null, null).applyTo(MALL);
    assertEquals(Optional.of(window("09:00", "10:00")), seen.effectiveWindow());
  }

  @Test
  void contactsHaveTheirLimitsAndAnUnknownDockIsRefused() {
    assertThrows(DomainException.class, () -> OutletDetails.of(REAR, null, null, null, "x".repeat(81), null, null));
    assertThrows(DomainException.class, () -> OutletDetails.of(REAR, null, null, null, null, "12ab", null));
    assertThrows(DomainException.class, () -> OutletDetails.of(REAR, null, null, null, null, null, "x".repeat(301)));
    assertThrows(DomainException.class, () -> OutletDetails.of(REAR, null, null, "loading_bay", null, null, null));
    assertSame(REAR.parkingConstraint(), OutletDetails.of(REAR, null, null, "street", null, null, null).applyTo(REAR).parkingConstraint());
  }

  private static LocalTime t(String hhmm) {
    return LocalTime.parse(hhmm);
  }

  private static DeliveryWindow window(String open, String close) {
    return new DeliveryWindow(t(open), t(close));
  }
}
