package com.waypoint.dispatch.loading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.waypoint.dispatch.loading.application.LoadingFixture;
import com.waypoint.dispatch.loading.application.ManifestBuilder;
import com.waypoint.dispatch.loading.infrastructure.JdbcManifestWriter;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.DemandView;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.util.Clock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class LoadingFixtureTest {
  @Test
  void vanOnlyDemandUsesACompatibleVanEvenWhenAHeavierTruckIsAvailable() {
    LocalDate day = LocalDate.of(2026, 10, 1);
    Database database = mock(Database.class);
    OrderQuery orders = mock(OrderQuery.class);
    ReferenceQuery reference = mock(ReferenceQuery.class);
    JdbcManifestWriter writer = mock(JdbcManifestWriter.class);
    when(database.asSystem(eq(ModuleRole.LOADING), any(Supplier.class)))
        .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
    when(writer.beginFixtureIfAbsent("Peliyagoda", day)).thenReturn(true);
    when(orders.confirmedDemand("Peliyagoda", day)).thenReturn(List.of(new DemandView(
        UUID.randomUUID(), "TEST-ORDER", "OUT001", "Fresh", "Colombo", "chilled",
        new BigDecimal("20"), new BigDecimal("0.2"), 2, day, 0, 1)));
    when(reference.outlet("OUT001", null)).thenReturn(Optional.of(new OutletView(
        "OUT001", "Fresh", "Colombo", "Peliyagoda", "street", "van_only",
        LocalTime.of(5, 0), LocalTime.of(7, 30), Optional.empty(), Optional.empty(), true)));
    VehicleView truck = new VehicleView("TRUCK", "truck", "reefer", new BigDecimal("5000"),
        new BigDecimal("25"), BigDecimal.ONE, BigDecimal.ONE, "Peliyagoda", true, false);
    VehicleView van = new VehicleView("VAN", "van", "reefer", new BigDecimal("1000"),
        new BigDecimal("7"), BigDecimal.ONE, BigDecimal.ONE, "Peliyagoda", true, true);
    when(reference.availableVehicles("Peliyagoda", day, null)).thenReturn(List.of(truck, van));
    LoadingFixture fixture = new LoadingFixture(database, orders, reference,
        mock(EventPublisher.class), writer, mock(ManifestBuilder.class),
        Clock.fixed(Instant.parse("2026-10-01T08:00:00Z")));

    assertEquals(1, fixture.build("Peliyagoda", day));
  }
}
