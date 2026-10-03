package com.waypoint.dispatch.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.loading.contract.LoadingEvents.InterchangeRequested;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrderPlaced;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrdersClosed;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.referencedata.contract.ReferenceEvents.VehicleStatusChanged;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Changing a published plan: revisions, trip replans, the consumers of decision
 * 7 and the previews, end to end against PostgreSQL. A published plan is never
 * edited; every change is a new version, and a trip that carries the same orders
 * keeps its id (PLN-04, R-LOD-06).
 */
class PlanningRevisionIntegrationTest extends PlanningIntegrationSupport {

  @Autowired List<EventSubscriber<?>> subscribers;

  @Test
  void aRevisionIsPublishedAsPlanRevisedAndUnchangedTripsKeepTheirIds() throws Exception {
    demand("ambient");
    demand("ambient");
    UUID original = publishedPlan();
    Set<UUID> trips = tripIds(original);

    String stale = send(dispatcher, revise(original, 1L, "a stale screen"), 409);
    assertTrue(stale.contains("VERSION_CONFLICT"), stale);

    JsonNode draft = mapper.readTree(send(dispatcher, revise(original, 2L, "store asked for a later slot"), 200)).get("result");
    UUID revision = UUID.fromString(draft.get("planId").asText());
    assertEquals(trips, tripIds(revision), "trips that did not change keep their ids");
    assertEquals("PUBLISHED", plan(original).get("status").asText(), "the published plan is untouched until replaced");

    send(dispatcher, publish(revision, 1L), 200);

    assertEquals("SUPERSEDED", plan(original).get("status").asText());
    assertEquals(1, outbox(revision.toString(), "plan.revised"));
    assertEquals(0, outbox(revision.toString(), "plan.published"), "a revision is announced as one");
    assertTrue(
        outboxRows(revision.toString(), "plan.revised").get(0).get("payload").toString()
            .contains("store asked for a later slot"));
  }

  @Test
  void aRevisionThatMovesATripNamesItAndOneThatChangesNothingNamesNone() throws Exception {
    demand("ambient");
    demand("ambient");
    UUID original = publishedPlan();
    JsonNode trip = plan(original).get("trips").get(0);
    UUID tripId = UUID.fromString(trip.get("tripId").asText());

    UUID nothing =
        UUID.fromString(mapper.readTree(send(dispatcher, revise(original, 2L, "a second look"), 200)).get("result").get("planId").asText());
    send(dispatcher, publish(nothing, 1L), 200);
    JsonNode unchanged = mapper.readTree(outboxRows(nothing.toString(), "plan.revised").get(0).get("payload").toString());
    assertEquals(0, unchanged.get("changedTripIds").size(), "nothing a driver would see differently");
    assertEquals(0, unchanged.get("affectedOutletIds").size());

    String replacement = spareTruck(nothing);
    JsonNode moved =
        mapper.readTree(send(dispatcher, replan(nothing, 2L, tripId, replacement, "tyre puncture"), 200)).get("result");
    UUID revision = UUID.fromString(moved.get("planId").asText());
    send(dispatcher, publish(revision, 1L), 200);

    JsonNode event = mapper.readTree(outboxRows(revision.toString(), "plan.revised").get(0).get("payload").toString());
    assertEquals(1, event.get("changedTripIds").size(), "the trip on another vehicle");
    assertEquals(tripId.toString(), event.get("changedTripIds").get(0).asText());
    assertTrue(event.get("affectedOutletIds").size() >= 1, "the outlet on it now sees another vehicle and time");
  }

  @Test
  void replanningAPublishedTripMovesItWholeAndKeepsItsId() throws Exception {
    demand("ambient");
    demand("ambient");
    UUID original = publishedPlan();
    JsonNode trip = plan(original).get("trips").get(0);
    UUID tripId = UUID.fromString(trip.get("tripId").asText());
    String replacement = spareTruck(original);

    JsonNode result =
        mapper.readTree(send(dispatcher, replan(original, 2L, tripId, replacement, "tyre puncture"), 200)).get("result");

    assertEquals(replacement, result.get("movedTo").asText());
    assertFalse(result.get("deferredAsUnit").asBoolean());
    UUID revision = UUID.fromString(result.get("planId").asText());
    JsonNode moved = tripById(revision, tripId);
    assertEquals(replacement, moved.get("vehicleId").asText(), "R-LOD-06: the trip changes vehicle, not identity");
    assertEquals(trip.get("stops").size(), moved.get("stops").size());
  }

  @Test
  void anInterchangeThatMovesOnlyItsTripIsPublishedByPlanningItself() throws Exception {
    demand("ambient");
    demand("ambient");
    UUID original = publishedPlan();
    JsonNode trip = plan(original).get("trips").get(0);
    UUID tripId = UUID.fromString(trip.get("tripId").asText());
    String replacement = spareTruck(original);

    deliver(
        "planning.on-interchange-requested",
        new InterchangeRequested(tripId, original, trip.get("vehicleId").asText(), replacement, "door jammed"));

    JsonNode current =
        mapper.readTree(read(dispatcher, "/api/plans/published?depot=" + depot + "&date=" + serviceDate, 200));
    assertNotEquals(original.toString(), current.get("planId").asText(), "a loader at the dock does not wait");
    assertEquals(original.toString(), current.get("supersedes").asText());
    assertEquals(replacement, tripById(UUID.fromString(current.get("planId").asText()), tripId).get("vehicleId").asText());
    assertEquals(1, outbox(current.get("planId").asText(), "plan.revised"));
  }

  @Test
  void aVehicleLostAfterPublicationDraftsARevisionWithoutIt() throws Exception {
    demand("ambient");
    demand("ambient");
    UUID original = publishedPlan();
    JsonNode trip = plan(original).get("trips").get(0);
    String lost = trip.get("vehicleId").asText();
    UUID tripId = UUID.fromString(trip.get("tripId").asText());
    database.asModule(
        ModuleRole.REF,
        null,
        () ->
            database.update(
                "INSERT INTO ref.vehicle_day_status (vehicle_id, service_date, status, reason) VALUES (?, ?, 'in_workshop', 'brakes')",
                lost,
                java.sql.Date.valueOf(serviceDate)));

    deliver("planning.on-vehicle-status-changed", new VehicleStatusChanged(lost, serviceDate, "in_workshop", Optional.of("brakes")));

    Map<String, Object> draft = openDraft();
    assertEquals(original, draft.get("supersedes"), "a revision of the published plan, for the dispatcher");
    UUID revision = (UUID) draft.get("plan_id");
    JsonNode view = plan(revision);
    assertTrue(
        StreamSupport.stream(view.get("trips").spliterator(), false).noneMatch(t -> t.get("vehicleId").asText().equals(lost)),
        "FLT-01: nothing left on the lost vehicle");
    assertNotEquals(lost, tripById(revision, tripId).get("vehicleId").asText(), "the trip moved whole and kept its id");
    assertEquals("PUBLISHED", plan(original).get("status").asText(), "only a dispatcher publishes it");
  }

  @Test
  void aNewOrderMakesTheOpenDraftStaleAndItsPublicationIsRefused() throws Exception {
    demand("ambient");
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());

    deliver(
        "planning.on-order-placed",
        new OrderPlaced(
            UUID.randomUUID(), "WPO-LATE", outlet.outletId(), depot, outlet.brandCode(), outlet.districtName(),
            serviceDate, "ambient", new BigDecimal("10"), new BigDecimal("0.1"), 1, OrderStatus.CONFIRMED));

    assertEquals(Boolean.TRUE, openDraft().get("stale"));
    String refused = send(dispatcher, publish(planId, 2L), 409);
    assertTrue(refused.contains("PLN-07"), refused);
  }

  @Test
  void closingTheDayGeneratesADraftAsTheSystem() throws Exception {
    UUID order = demand("ambient");

    deliver("planning.on-orders-closed", new OrdersClosed(depot, serviceDate, List.of(order)));

    Map<String, Object> draft = openDraft();
    assertEquals(Actor.SYSTEM_ID, draft.get("generated_by"));
    assertEquals(Set.of(order), allocatedOrders(plan((UUID) draft.get("plan_id"))));
  }

  @Test
  void thePreviewsAnswerWhatTheCommandsWouldDo() throws Exception {
    UUID order = demand("ambient");
    demand("ambient");
    UUID draft = UUID.fromString(generate(dispatcher, 200).get("planId").asText());

    JsonNode options = mapper.readTree(read(dispatcher, "/api/plans/preview/assignments?order=" + order, 200));
    assertTrue(options.size() > 1);
    assertEquals("SERVED", options.get(0).get("decision").asText(), "feasible places first");
    assertTrue(options.get(options.size() - 1).get("checks").size() > 0, "every place carries its checks");

    send(dispatcher, publish(draft, 1L), 200);
    JsonNode trip = plan(draft).get("trips").get(0);
    String replacement = spareTruck(draft);
    JsonNode preview =
        mapper.readTree(
            read(dispatcher, "/api/plans/preview/interchange?trip=" + trip.get("tripId").asText() + "&vehicle=" + replacement, 200));
    assertTrue(preview.get("feasible").asBoolean(), preview.toString());
    assertEquals(trip.get("vehicleId").asText(), preview.get("currentVehicleId").asText());
    assertEquals("PUBLISHED", plan(draft).get("status").asText(), "a preview changes nothing");
  }

  // ---- fixtures ------------------------------------------------------------

  private UUID publishedPlan() throws Exception {
    UUID planId = UUID.fromString(generate(dispatcher, 200).get("planId").asText());
    send(dispatcher, publish(planId, 1L), 200);
    return planId;
  }

  private JsonNode plan(UUID planId) throws Exception {
    return mapper.readTree(read(dispatcher, "/api/plans/" + planId, 200));
  }

  private Set<UUID> tripIds(UUID planId) throws Exception {
    return StreamSupport.stream(plan(planId).get("trips").spliterator(), false)
        .map(t -> UUID.fromString(t.get("tripId").asText()))
        .collect(Collectors.toSet());
  }

  private JsonNode tripById(UUID planId, UUID tripId) throws Exception {
    for (JsonNode t : plan(planId).get("trips")) {
      if (t.get("tripId").asText().equals(tripId.toString())) {
        return t;
      }
    }
    throw new AssertionError("trip " + tripId + " is not in plan " + planId);
  }

  /** An available ordinary truck the plan does not use. */
  private String spareTruck(UUID planId) throws Exception {
    Set<String> used =
        StreamSupport.stream(plan(planId).get("trips").spliterator(), false)
            .map(t -> t.get("vehicleId").asText())
            .collect(Collectors.toSet());
    return reference.availableVehicles(depot, serviceDate, null).stream()
        .filter(v -> !v.van() && !v.refrigerated() && !used.contains(v.vehicleId()))
        .map(VehicleView::vehicleId)
        .sorted()
        .findFirst()
        .orElseThrow();
  }

  private Map<String, Object> openDraft() {
    return database.asSystem(
        ModuleRole.PLANNING,
        () ->
            database.queryOne(
                "SELECT plan_id, supersedes, stale, generated_by FROM planning.runs"
                    + " WHERE depot_code = ? AND service_date = ? AND status = 'draft'",
                depot,
                java.sql.Date.valueOf(serviceDate)));
  }

  private static String revise(UUID planId, Long version, String reason) {
    return envelope("plan:Revise", version, "{\"planId\":\"" + planId + "\",\"reason\":\"" + reason + "\"}");
  }

  private static String replan(UUID planId, Long version, UUID tripId, String vehicle, String reason) {
    return envelope(
        "plan:Replan", version,
        "{\"planId\":\"" + planId + "\",\"tripId\":\"" + tripId + "\",\"replacementVehicleId\":\"" + vehicle
            + "\",\"reason\":\"" + reason + "\"}");
  }

  /** What the relay does: the consumer, as the system, in a Planning transaction. */
  @SuppressWarnings("unchecked")
  private <E extends DomainEvent> void deliver(String consumer, E event) {
    EventSubscriber<E> subscriber =
        (EventSubscriber<E>)
            subscribers.stream().filter(s -> s.consumerName().equals(consumer)).findFirst().orElseThrow();
    EventEnvelope<E> envelope =
        new EventEnvelope<>(UUID.randomUUID(), event.type(), 1, Instant.now(), "test", Optional.empty(),
            Optional.empty(), event);
    database.asSystem(ModuleRole.PLANNING, () -> subscriber.on(envelope));
  }
}
