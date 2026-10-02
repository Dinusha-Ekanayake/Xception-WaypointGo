package com.waypoint.dispatch.referencedata.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.identity.contract.IdentityQuery.ScopeView;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * R-IAM-28 with no database: who may read which depot's outlets and fleet. The
 * wiring, the audit row and the store policy are in
 * {@code IdentityHardeningIntegrationTest}.
 */
class ReferenceScopeTest {
  private static final String READ = "reference:Read";
  private static final OutletView KANDY_OUTLET = outlet("OUT076", "Kandy");
  private static final OutletView COLOMBO_OUTLET = outlet("OUT001", "Peliyagoda");
  private static final VehicleView KANDY_VAN = vehicle("VEH060", "Kandy");
  private static final VehicleView OTHER_KANDY_VAN = vehicle("VEH059", "Kandy");

  private final IdentityQuery identity = mock(IdentityQuery.class);
  private final ReferenceQuery reference = mock(ReferenceQuery.class);
  private final AuditLog audit = mock(AuditLog.class);
  private final Metrics metrics = mock(Metrics.class);
  /** 2026-10-02 at noon in Colombo. */
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-10-02T06:30:00Z"));
  private final ReferenceScope scope =
      new ReferenceScope(identity, reference, audit, metrics, now::get);

  private final Actor actor = new Actor(UUID.randomUUID(), UUID.randomUUID());

  @Test
  void aDepotGrantReadsThatDepotsListsOutletsAndVehiclesAndNoOthers() {
    holds(List.of("Kandy"), List.of());

    assertDoesNotThrow(() -> scope.requireDepot(actor, READ, "wpt:ref:depot:Kandy", "Kandy"));
    assertDoesNotThrow(() -> scope.requireOutlet(actor, READ, "wpt:ref:outlet:OUT076", KANDY_OUTLET));
    assertDoesNotThrow(() -> scope.requireVehicle(actor, READ, "wpt:ref:vehicle:VEH060", KANDY_VAN));
    verify(audit, never()).recordStandalone(any());

    refused(() -> scope.requireDepot(actor, READ, "wpt:ref:depot:Peliyagoda", "Peliyagoda"));
    refused(() -> scope.requireOutlet(actor, READ, "wpt:ref:outlet:OUT001", COLOMBO_OUTLET));
    refused(
        () ->
            scope.requireVehicle(
                actor, READ, "wpt:ref:vehicle:VEH001", vehicle("VEH001", "Peliyagoda")));
  }

  @Test
  void anOutletGrantReadsThatOutletOnly() {
    holds(List.of(), List.of("OUT001"));

    assertDoesNotThrow(() -> scope.requireOutlet(actor, READ, "wpt:ref:outlet:OUT001", COLOMBO_OUTLET));
    refused(
        () ->
            scope.requireOutlet(
                actor, READ, "wpt:ref:outlet:OUT002", outlet("OUT002", "Peliyagoda")));
    // The outlet's depot is not the manager's to list.
    refused(() -> scope.requireDepot(actor, READ, "wpt:ref:depot:Peliyagoda", "Peliyagoda"));
  }

  @Test
  void aDriverReadsTheirVehicleAndItsDepotsOutletsOnTheDayTheyDriveIt() {
    holds(List.of(), List.of());
    when(identity.driverVehicleOn(actor.userId(), LocalDate.parse("2026-10-02")))
        .thenReturn(Optional.of("VEH060"));
    when(reference.vehicle("VEH060", null)).thenReturn(Optional.of(KANDY_VAN));

    assertDoesNotThrow(() -> scope.requireVehicle(actor, READ, "wpt:ref:vehicle:VEH060", KANDY_VAN));
    assertDoesNotThrow(() -> scope.requireOutlet(actor, READ, "wpt:ref:outlet:OUT076", KANDY_OUTLET));

    // Not another vehicle of the same depot, not another depot's outlet, and
    // not the depot's lists: a driver's scope is a vehicle, not a depot.
    refused(() -> scope.requireVehicle(actor, READ, "wpt:ref:vehicle:VEH059", OTHER_KANDY_VAN));
    refused(() -> scope.requireOutlet(actor, READ, "wpt:ref:outlet:OUT001", COLOMBO_OUTLET));
    refused(() -> scope.requireDepot(actor, READ, "wpt:ref:depot:Kandy", "Kandy"));
  }

  /** The day is the operating day in Colombo, not the server's UTC date. */
  @Test
  void theDriversDayTurnsAtMidnightInTheOperatingZone() {
    holds(List.of(), List.of());
    when(identity.driverVehicleOn(actor.userId(), LocalDate.parse("2026-10-02")))
        .thenReturn(Optional.of("VEH060"));
    when(identity.driverVehicleOn(actor.userId(), LocalDate.parse("2026-10-03")))
        .thenReturn(Optional.empty());

    now.set(Instant.parse("2026-10-02T18:29:00Z")); // 23:59 on the 2nd in Colombo
    assertDoesNotThrow(() -> scope.requireVehicle(actor, READ, "wpt:ref:vehicle:VEH060", KANDY_VAN));

    now.set(Instant.parse("2026-10-02T18:31:00Z")); // 00:01 on the 3rd, still the 2nd in UTC
    refused(() -> scope.requireVehicle(actor, READ, "wpt:ref:vehicle:VEH060", KANDY_VAN));
  }

  @Test
  void aRefusalIsAForbiddenNamingTheRuleAndIsAuditedAndCounted() {
    holds(List.of("Kandy"), List.of());

    DomainException refusal =
        refused(() -> scope.requireOutlet(actor, READ, "wpt:ref:outlet:OUT001", COLOMBO_OUTLET));
    assertEquals("R-IAM-28", refusal.violations().get(0).rule());

    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).recordStandalone(entry.capture());
    assertEquals("DENY", entry.getValue().decision());
    assertEquals(actor.userId(), entry.getValue().actorId());
    assertEquals(actor.deviceId(), entry.getValue().deviceId());
    assertEquals(READ, entry.getValue().action());
    assertEquals("wpt:ref:outlet:OUT001", entry.getValue().resource());
    verify(metrics).increment(eq("waypoint.scope.denied"), eq("module"), eq("referencedata"));
  }

  @Test
  void theProcessItselfIsNotScoped() {
    assertDoesNotThrow(
        () -> scope.requireDepot(Actor.SYSTEM, READ, "wpt:ref:depot:Kandy", "Kandy"));
    assertDoesNotThrow(
        () -> scope.requireOutlet(Actor.SYSTEM, READ, "wpt:ref:outlet:OUT076", KANDY_OUTLET));
    assertDoesNotThrow(
        () -> scope.requireVehicle(Actor.SYSTEM, READ, "wpt:ref:vehicle:VEH060", KANDY_VAN));
  }

  private void holds(List<String> depots, List<String> outlets) {
    when(identity.scopeOf(actor.userId()))
        .thenReturn(new ScopeView(actor.userId(), List.of(), depots, outlets));
    when(identity.driverVehicleOn(eq(actor.userId()), any())).thenReturn(Optional.empty());
  }

  private static DomainException refused(Runnable read) {
    DomainException refusal = assertThrows(DomainException.class, read::run);
    assertEquals(ErrorCode.FORBIDDEN, refusal.code());
    return refusal;
  }

  private static OutletView outlet(String id, String depot) {
    return new OutletView(
        id,
        "Fresh",
        depot,
        depot,
        "street",
        "van_only",
        LocalTime.of(5, 30),
        LocalTime.of(8, 0),
        Optional.empty(),
        Optional.empty(),
        true);
  }

  private static VehicleView vehicle(String id, String depot) {
    return new VehicleView(
        id,
        "van",
        "ambient",
        new BigDecimal("1000"),
        new BigDecimal("8"),
        new BigDecimal("9"),
        new BigDecimal("60"),
        depot,
        false,
        true);
  }
}
