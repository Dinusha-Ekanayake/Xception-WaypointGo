package com.waypoint.dispatch.warehouse.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.warehouse.contract.StockPort.Insufficient;
import com.waypoint.dispatch.warehouse.contract.StockPort.PartiallyReserved;
import com.waypoint.dispatch.warehouse.contract.StockPort.Rejected;
import com.waypoint.dispatch.warehouse.contract.StockPort.Reserved;
import com.waypoint.dispatch.warehouse.contract.StockPort.StockLine;
import com.waypoint.dispatch.warehouse.contract.StockPort.Unavailable;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher.Ambiguous;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher.Attempt;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher.Found;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher.NotFound;
import com.waypoint.dispatch.warehouse.domain.Placement.OnRequest;
import com.waypoint.dispatch.warehouse.domain.PlacementDecision.Outcome;
import com.waypoint.dispatch.warehouse.domain.RetryPolicy.Action;
import com.waypoint.dispatch.warehouse.domain.WarehouseLifecycle.Step;
import com.waypoint.dispatch.warehouse.domain.WarehouseOrder.Item;
import com.waypoint.dispatch.warehouse.domain.WarehouseOrder.Shortfall;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Answered;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Failed;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Refused;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The anti-corruption layer's decisions, with no database and no network. */
class WarehouseDomainTest {
  private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
  private static final List<StockLine> LINES = List.of(new StockLine("P-1", 4), new StockLine("P-2", 2));

  private static WarehouseOrder order(String id, String status, String temp, Instant created, List<Item> items) {
    return new WarehouseOrder(id, status, "KDY", temp, new BigDecimal("70.770"), new BigDecimal("0.4023"),
        "reserved".equals(status) ? Optional.of(created.plusSeconds(900)) : Optional.empty(),
        created, items.size(), items);
  }

  private static List<Item> items(int p1Got) {
    return List.of(new Item("P-1", p1Got, 4), new Item("P-2", 2, 2));
  }

  // ---- placement decision ---------------------------------------------------

  @Test
  void a201IsReservedWithTheWarehousesTotals() {
    Outcome o = PlacementDecision.decide(new Answered(201, order("ORD1", "pending", "ambient", T0, items(4)), List.of()), LINES);
    Reserved r = assertInstanceOf(Reserved.class, o.result());
    assertEquals("ORD1", r.warehouseOrderRef());
    assertEquals(new BigDecimal("70.770"), r.weightKg());
    assertEquals(6, r.itemCount());
    assertTrue(o.release().isEmpty());
  }

  @Test
  void a202IsKeptAsPartiallyReservedWithShortfallAndAlternatives() {
    Outcome o = PlacementDecision.decide(
        new Answered(202, order("ORD2", "reserved", "chilled", T0, items(3)),
            List.of(new Shortfall("P-1", 4, 3, "PLG", 1000))),
        LINES);
    PartiallyReserved p = assertInstanceOf(PartiallyReserved.class, o.result());
    assertEquals(T0.plusSeconds(900), p.expiresAt());
    assertEquals(3, p.lines().get(0).available());
    assertEquals("PLG", p.alternatives().get(0).warehouse());
    assertTrue(o.release().isEmpty());
  }

  @Test
  void a409ReturnsPerLineAvailability() {
    Outcome o = PlacementDecision.decide(new Refused(409, "insufficient_stock", "none", List.of()), LINES);
    Insufficient i = assertInstanceOf(Insufficient.class, o.result());
    assertEquals(2, i.lines().size());
    assertEquals(0, i.lines().get(0).available());
    assertEquals(4, i.lines().get(0).requested());
  }

  @Test
  void aMixedTemperatureOrderIsRejectedAndReleased() {
    Outcome o = PlacementDecision.decide(new Answered(201, order("ORD3", "pending", "mixed", T0, items(4)), List.of()), LINES);
    assertInstanceOf(Rejected.class, o.result());
    assertEquals(Optional.of("ORD3"), o.release());
  }

  @Test
  void aReplyWithoutTotalsIsNeverReserved() {
    WarehouseOrder bare = new WarehouseOrder("ORD4", "pending", "KDY", "ambient", null, null,
        Optional.empty(), T0, 1, items(4));
    Outcome o = PlacementDecision.decide(new Answered(201, bare, List.of()), LINES);
    assertInstanceOf(Rejected.class, o.result());
    assertEquals(Optional.of("ORD4"), o.release());
  }

  @Test
  void aTimeoutIsUnavailableWithUnknownOutcomeButAnOpenCircuitIsNot() {
    Outcome timeout = PlacementDecision.decide(new Failed("timed out", true), LINES);
    assertInstanceOf(Unavailable.class, timeout.result());
    assertTrue(timeout.outcomeUnknown());
    assertFalse(PlacementDecision.decide(new Failed("circuit open", false), LINES).outcomeUnknown());
  }

  @Test
  void anUnknownProductIsRejectedNotInsufficient() {
    assertInstanceOf(Rejected.class,
        PlacementDecision.decide(new Refused(404, "product_not_found", "Unknown", List.of()), LINES).result());
  }

  // ---- circuit breaker ------------------------------------------------------

  @Test
  void theCircuitOpensAfterTheThresholdAndClosesAfterASuccessfulTrial() {
    CircuitBreaker c = new CircuitBreaker(3, Duration.ofSeconds(30));
    for (int i = 0; i < 3; i++) {
      assertTrue(c.allow(T0));
      c.onFailure(T0);
    }
    assertFalse(c.allow(T0.plusSeconds(1)));
    assertEquals(CircuitBreaker.State.OPEN, c.state(T0.plusSeconds(1)));
    assertTrue(c.allow(T0.plusSeconds(31)), "one trial once the open period ends");
    assertFalse(c.allow(T0.plusSeconds(31)), "only one trial at a time");
    c.onSuccess();
    assertEquals(CircuitBreaker.State.CLOSED, c.state(T0.plusSeconds(32)));
  }

  @Test
  void aFailedTrialReopensTheCircuit() {
    CircuitBreaker c = new CircuitBreaker(1, Duration.ofSeconds(30));
    c.onFailure(T0);
    assertTrue(c.allow(T0.plusSeconds(30)));
    c.onFailure(T0.plusSeconds(30));
    assertFalse(c.allow(T0.plusSeconds(40)));
  }

  // ---- orphan matching (R-STK-11) --------------------------------------------

  @Test
  void aLostAttemptIsFoundByContentWarehouseAndTime() {
    var match = OrphanMatcher.match("KDY", List.of(new Attempt(LINES, T0)),
        List.of(order("ORD9", "pending", "ambient", T0.plusSeconds(2), items(4))), Set.of(), Duration.ofMinutes(3));
    assertEquals("ORD9", assertInstanceOf(Found.class, match).order().orderId());
  }

  @Test
  void differentGoodsAnotherWarehouseOrAClaimedOrderIsNotAMatch() {
    List<Attempt> attempts = List.of(new Attempt(LINES, T0));
    WarehouseOrder other = order("ORD9", "pending", "ambient", T0, List.of(new Item("P-1", 5, 5), new Item("P-2", 2, 2)));
    assertInstanceOf(NotFound.class, OrphanMatcher.match("KDY", attempts, List.of(other), Set.of(), Duration.ofMinutes(3)));
    assertInstanceOf(NotFound.class, OrphanMatcher.match("PLG", attempts,
        List.of(order("ORD9", "pending", "ambient", T0, items(4))), Set.of(), Duration.ofMinutes(3)));
    assertInstanceOf(NotFound.class, OrphanMatcher.match("KDY", attempts,
        List.of(order("ORD9", "pending", "ambient", T0, items(4))), Set.of("ORD9"), Duration.ofMinutes(3)));
    assertInstanceOf(NotFound.class, OrphanMatcher.match("KDY", attempts,
        List.of(order("ORD9", "pending", "ambient", T0.plusSeconds(600), items(4))), Set.of(), Duration.ofMinutes(3)));
  }

  @Test
  void twoCandidatesAreAmbiguousNeverAGuess() {
    var match = OrphanMatcher.match("KDY", List.of(new Attempt(LINES, T0)),
        List.of(order("ORD8", "pending", "ambient", T0, items(4)), order("ORD9", "pending", "ambient", T0, items(4))),
        Set.of(), Duration.ofMinutes(3));
    assertEquals(List.of("ORD8", "ORD9"), assertInstanceOf(Ambiguous.class, match).candidates());
  }

  // ---- retry decisions -------------------------------------------------------

  private static Placement placement(List<Attempt> attempts, boolean compensateOnly) {
    return new Placement(UUID.randomUUID(), "WPO-1", UUID.randomUUID(), "Kandy", "KDY", LINES, attempts,
        Placement.State.UNKNOWN, compensateOnly, Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), 1, Optional.empty(), T0, 2);
  }

  @Test
  void aFoundOrderIsAdoptedNotPlacedAgain() {
    Placement p = placement(List.of(new Attempt(LINES, T0)), false);
    var found = new Found(order("ORD9", "pending", "ambient", T0, items(4)), 0);
    assertEquals(Action.ADOPT, RetryPolicy.decide(p, found, T0.plusSeconds(60), Duration.ofMinutes(3)).action());
  }

  @Test
  void nothingFoundWaitsForTheWindowThenPlaces() {
    Placement p = placement(List.of(new Attempt(LINES, T0)), false);
    assertEquals(Action.WAIT, RetryPolicy.decide(p, new NotFound(), T0.plusSeconds(60), Duration.ofMinutes(3)).action());
    assertEquals(Action.PLACE, RetryPolicy.decide(p, new NotFound(), T0.plusSeconds(200), Duration.ofMinutes(3)).action());
  }

  @Test
  void anUnwantedPlacementIsOnlyEverCancelled() {
    Placement p = placement(List.of(new Attempt(LINES, T0)), true);
    var found = new Found(order("ORD9", "pending", "ambient", T0, items(4)), 0);
    assertEquals(Action.CANCEL_ONLY, RetryPolicy.decide(p, found, T0, Duration.ofMinutes(3)).action());
    assertEquals(Action.GIVE_UP, RetryPolicy.decide(p, new NotFound(), T0.plusSeconds(600), Duration.ofMinutes(3)).action());
  }

  @Test
  void aSecondRequestReplaysTheFirstAnswerOrWaitsOnAnUnknownOne() {
    assertEquals(OnRequest.SEND, Placement.onRequest(Optional.empty(), LINES));
    Placement unknown = placement(List.of(new Attempt(LINES, T0)), false);
    assertEquals(OnRequest.WAIT, Placement.onRequest(Optional.of(unknown), LINES));
    Placement placed = new Placement(unknown.placementId(), "WPO-1", unknown.orderId(), "Kandy", "KDY",
        LINES, unknown.attempts(), Placement.State.PLACED, false, Optional.of("ORD9"), Optional.of("pending"),
        Optional.empty(), Optional.of(new Reserved("ORD9", BigDecimal.ONE, BigDecimal.ONE, "ambient", 6)), 0,
        Optional.empty(), T0, 3);
    assertEquals(OnRequest.REPLAY, Placement.onRequest(Optional.of(placed), List.of(LINES.get(1), LINES.get(0))));
  }

  // ---- lifecycle -------------------------------------------------------------

  @Test
  void aTargetAlreadyReachedIsDoneAndAnUnreachableOneDiverged() {
    assertEquals(Step.CALL, WarehouseLifecycle.step("pending", "shipped"));
    assertEquals(Step.DONE, WarehouseLifecycle.step("delivered", "shipped"));
    assertEquals(Step.DONE, WarehouseLifecycle.step("cancelled", "cancelled"));
    assertEquals(Step.DONE, WarehouseLifecycle.step("expired", "cancelled"));
    assertEquals(Step.DIVERGED, WarehouseLifecycle.step("delivered", "cancelled"));
    assertEquals(Step.DIVERGED, WarehouseLifecycle.step("cancelled", "shipped"));
  }

  @Test
  void aWaypointStatusMapsToTheWarehouseStatusesItAllows() {
    assertEquals(Optional.of(Set.of("pending")), WarehouseLifecycle.expectedFor("CONFIRMED"));
    assertTrue(WarehouseLifecycle.expectedFor("STOCK_UNKNOWN").isEmpty());
  }

  // ---- webhook signature -----------------------------------------------------

  @Test
  void aSignatureVerifiesOnlyOverTheExactBodyInsideTheWindow() {
    byte[] secret = "s3cret".getBytes(StandardCharsets.UTF_8);
    byte[] body = "{\"id\":\"e1\"}".getBytes(StandardCharsets.UTF_8);
    String ts = String.valueOf(T0.getEpochSecond());
    String sig = "sha256=" + HexFormat.of().formatHex(WebhookSignature.sign(secret, ts, body));
    Duration window = Duration.ofMinutes(5);
    assertTrue(WebhookSignature.problem(secret, ts, sig, body, T0, window).isEmpty());
    assertTrue(WebhookSignature.problem(secret, ts, sig, "{\"id\":\"e2\"}".getBytes(StandardCharsets.UTF_8), T0, window).isPresent());
    assertTrue(WebhookSignature.problem(secret, ts, sig, body, T0.plusSeconds(600), window).isPresent());
    assertTrue(WebhookSignature.problem(secret, null, null, body, T0, window).isPresent());
  }

  @Test
  void aDepotNamesItsOwnWarehouse() {
    assertEquals(Optional.of("KDY"), WarehouseCode.forDepot("Kandy"));
    assertEquals(Optional.of("PLG"), WarehouseCode.forDepot("peliyagoda"));
    assertTrue(WarehouseCode.forDepot("Galle").isEmpty());
  }

  @Test
  void theCatalogueVersionIsAContentHash() {
    var a = new CatalogueEntry("P-1", "Fresh", "chilled", new BigDecimal("6.6182"), new BigDecimal("0.03355"), "B", "x", false);
    var b = new CatalogueEntry("P-2", "Tech", "ambient", new BigDecimal("1.0"), new BigDecimal("0.01"), "C", "x", false);
    assertEquals(CatalogueEntry.version(List.of(a, b)), CatalogueEntry.version(List.of(b, a)));
    assertFalse(CatalogueEntry.version(List.of(a)).equals(CatalogueEntry.version(List.of(a, b))));
  }
}
