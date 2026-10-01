package com.waypoint.dispatch.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.planning.application.PlanDataQuery;
import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationView;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanStatus;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.contract.PlanViews.TripView;
import com.waypoint.dispatch.planning.domain.ConstraintResult;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import com.waypoint.dispatch.planning.domain.RuleSet;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.AllocationRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.DeferralRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.FuelRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.LegRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.TripRow;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The planning schema against a real PostgreSQL: a published plan cannot be
 * edited in place, one plan is current per depot and day, row-level security
 * decides which depots a dispatcher sees, fuel counts published plans only, and
 * the seeded configuration is exactly what the domain reads.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(
    named = "TEST_DATABASE_URL",
    matches = ".+",
    disabledReason = "Set TEST_DATABASE_URL to a dedicated database to run integration tests")
class PlanningSchemaIntegrationTest {
  static final String OUTLET = "OUT001";
  static final String PASSWORD = "planning-schema-test-password";

  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;
  @Autowired AccountAdminUseCase accounts;
  @Autowired JdbcPlanRepository plans;
  @Autowired PlanDataQuery query;
  @Autowired PlanQuery contract;

  OutletView outlet;
  String depot;
  String otherDepot;
  String vehicle;
  UUID referenceVersion;
  RuleSet rules;
  PriorityPolicy policy;
  Actor dispatcher;
  Actor elsewhere;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", () -> System.getenv("TEST_DATABASE_URL"));
  }

  @BeforeAll
  static void guardAgainstTheApplicationDatabase() {
    String application = System.getenv("DATABASE_URL");
    if (application != null && application.equals(System.getenv("TEST_DATABASE_URL"))) {
      throw new IllegalStateException("TEST_DATABASE_URL must differ from DATABASE_URL");
    }
  }

  @BeforeEach
  void setUp() {
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    referenceVersion = reference.currentVersionId().orElseThrow();
    outlet = reference.outlet(OUTLET, null).orElseThrow();
    depot = outlet.depotCode();
    vehicle =
        database.asSystem(
            ModuleRole.PLANNING,
            () ->
                (String)
                    database
                        .queryOne(
                            "SELECT vehicle_id FROM ref.vehicles WHERE reference_version_id = ?"
                                + " AND depot_code = ? ORDER BY vehicle_id LIMIT 1",
                            referenceVersion,
                            depot)
                        .get("vehicle_id"));
    otherDepot =
        database.asSystem(
            ModuleRole.PLANNING,
            () ->
                (String)
                    database
                        .queryOne(
                            "SELECT depot_code FROM ref.depots WHERE reference_version_id = ?"
                                + " AND depot_code <> ? ORDER BY depot_code LIMIT 1",
                            referenceVersion,
                            depot)
                        .get("depot_code"));
    rules = database.asSystem(ModuleRole.PLANNING, () -> plans.effectiveRuleSet(LocalDate.now()).orElseThrow());
    policy =
        database.asSystem(ModuleRole.PLANNING, () -> plans.effectivePolicy(depot, LocalDate.now()).orElseThrow());

    String run = UUID.randomUUID().toString().substring(0, 8);
    String here = "dsp-" + run + "@planning.test";
    dispatcher = Actor.user(accounts.createAccount(here, "Dispatcher", PASSWORD, "dispatcher"));
    accounts.grantDepot(here, depot);
    String there = "far-" + run + "@planning.test";
    elsewhere = Actor.user(accounts.createAccount(there, "Elsewhere", PASSWORD, "dispatcher"));
    accounts.grantDepot(there, otherDepot);
  }

  // ---- configuration -------------------------------------------------------

  @Test
  void theSeededRuleSetIsExactlyWhatTheDomainReads() {
    Map<String, BigDecimal> booklet = RuleSet.bookletParameters();
    assertEquals(booklet.keySet(), rules.parameters().keySet());
    booklet.forEach(
        (key, value) ->
            assertEquals(0, value.compareTo(rules.parameters().get(key)), key + " differs from the booklet"));
    assertTrue(
        rules.parameters().keySet().containsAll(RuleSet.requiredKeys(Set.of("Fresh", "Style", "Tech"))),
        "a key the domain reads is missing, which would refuse every run (POL-10)");
    assertEquals(PriorityPolicy.DEFAULT_KEYS, policy.keys());
  }

  @Test
  void noRuleSetBeforeTheFirstOneTookEffect() {
    assertTrue(
        database
            .asSystem(ModuleRole.PLANNING, () -> plans.effectiveRuleSet(LocalDate.of(2020, 1, 1)))
            .isEmpty(),
        "an unconfigured date has no rule set, so the run refuses instead of guessing (POL-10)");
  }

  @Test
  void aRuleParameterCannotBeEditedInPlace() {
    assertThrows(
        RuntimeException.class,
        () ->
            database.asSystem(
                ModuleRole.PLANNING,
                () ->
                    database.update(
                        "UPDATE planning.rule_parameters SET parameter_value = 300"
                            + " WHERE rule_set_id = ? AND parameter_key = ?",
                        rules.id(),
                        RuleSet.FRESH_BUDGET_MIN)));
  }

  @Test
  void twoPolicyVersionsCannotClaimTheSameDate() {
    LocalDate from = freshDate();
    assertThrows(
        RuntimeException.class,
        () -> database.asSystem(ModuleRole.PLANNING, () -> insertPolicy(null, from, List.of("FRESH"))),
        "the global version is open-ended, so any later global version overlaps it (POL-04)");
  }

  @Test
  void aDepotCanaryWinsOverTheGlobalPolicyOnItsDepotOnly() {
    LocalDate day = freshDate();
    UUID canary =
        database.asModule(
            ModuleRole.PLANNING,
            dispatcher.userId(),
            () -> insertPolicy(depot, day, List.of("CHILLED", "FRESH", "PRIOR_SKIP")));

    PriorityPolicy here =
        database.asSystem(ModuleRole.PLANNING, () -> plans.effectivePolicy(depot, day).orElseThrow());
    PriorityPolicy there =
        database.asSystem(ModuleRole.PLANNING, () -> plans.effectivePolicy(otherDepot, day).orElseThrow());
    PriorityPolicy dayAfter =
        database.asSystem(ModuleRole.PLANNING, () -> plans.effectivePolicy(depot, day.plusDays(1)).orElseThrow());

    assertEquals(canary, here.id(), "POL-08: the depot-scoped version applies on its depot");
    assertEquals(policy.id(), there.id(), "other depots keep the global version");
    assertEquals(policy.id(), dayAfter.id(), "the canary ends when its range does");
    assertThrows(
        RuntimeException.class,
        () ->
            database.asModule(
                ModuleRole.PLANNING,
                elsewhere.userId(),
                () -> insertPolicy(depot, day.plusDays(2), List.of("FRESH"))),
        "a canary is written only by someone scoped to its depot");
  }

  // ---- immutability ----------------------------------------------------------

  @Test
  void aPublishedPlanCannotBeUpdatedInPlace() {
    LocalDate date = freshDate();
    UUID planId = draftWithOneTrip(date, 1);
    database.asSystem(
        ModuleRole.PLANNING, () -> plans.publish(planId, 1, Actor.SYSTEM_ID, Instant.now()));

    assertThrows(
        RuntimeException.class,
        () ->
            asPlanning(
                () ->
                    database.update(
                        "UPDATE planning.runs SET demand_fingerprint = 'edited' WHERE plan_id = ?", planId)),
        "a published run's content is frozen");
    assertThrows(
        RuntimeException.class,
        () -> asPlanning(() -> database.update("UPDATE planning.trips SET litres = 1 WHERE plan_id = ?", planId)),
        "a published run's trips are frozen");
    assertThrows(
        RuntimeException.class,
        () ->
            asPlanning(
                () ->
                    plans.insertAllocations(
                        planId,
                        depot,
                        List.of(deferred(UUID.randomUUID())))),
        "nothing can be added to a published run");
    assertThrows(
        RuntimeException.class,
        () -> asPlanning(() -> plans.markStale(planId, 2, Instant.now())),
        "only a draft goes stale");

    database.asSystem(ModuleRole.PLANNING, () -> plans.supersede(planId, 2, Instant.now()));
    assertThrows(
        RuntimeException.class,
        () ->
            asPlanning(
                () -> database.update("UPDATE planning.runs SET status = 'published' WHERE plan_id = ?", planId)),
        "superseded is terminal");
  }

  @Test
  void onlyOnePlanIsPublishedPerDepotAndDay() {
    LocalDate date = freshDate();
    UUID first = draftWithOneTrip(date, 1);
    database.asSystem(ModuleRole.PLANNING, () -> plans.publish(first, 1, Actor.SYSTEM_ID, Instant.now()));
    UUID second = draftWithOneTrip(date, 2, Optional.of(first));

    assertThrows(
        RuntimeException.class,
        () -> database.asSystem(ModuleRole.PLANNING, () -> plans.publish(second, 1, Actor.SYSTEM_ID, Instant.now())));

    database.asSystem(
        ModuleRole.PLANNING,
        () -> {
          plans.supersede(first, 2, Instant.now());
          plans.publish(second, 1, Actor.SYSTEM_ID, Instant.now());
        });
    PlanView current = query.publishedPlan(dispatcher, depot, date);
    assertEquals(second, current.planId());
    assertEquals(Optional.of(first), current.supersedes());
  }

  @Test
  void aStaleVersionIsRefused() {
    UUID planId = draftWithOneTrip(freshDate(), 1);
    database.asSystem(ModuleRole.PLANNING, () -> plans.markStale(planId, 1, Instant.now()));

    DomainException stale =
        assertThrows(
            DomainException.class,
            () ->
                database.asSystem(
                    ModuleRole.PLANNING, () -> plans.publish(planId, 1, Actor.SYSTEM_ID, Instant.now())));
    assertEquals(ErrorCode.VERSION_CONFLICT, stale.code());
  }

  // ---- scope -------------------------------------------------------------------

  @Test
  void aDispatcherForAnotherDepotSeesNothing() {
    LocalDate date = freshDate();
    UUID planId = draftWithOneTrip(date, 1);
    database.asSystem(ModuleRole.PLANNING, () -> plans.publish(planId, 1, Actor.SYSTEM_ID, Instant.now()));

    assertEquals(planId, query.plan(dispatcher, planId).planId());
    DomainException hidden = assertThrows(DomainException.class, () -> query.plan(elsewhere, planId));
    assertEquals(ErrorCode.NOT_FOUND, hidden.code(), "out of scope looks like absent, not forbidden");

    long before = deniedReads(elsewhere);
    DomainException denied =
        assertThrows(DomainException.class, () -> query.publishedPlan(elsewhere, depot, date));
    assertEquals(ErrorCode.FORBIDDEN, denied.code());
    assertEquals(before + 1, deniedReads(elsewhere), "a denied read is audited");

    assertTrue(contract.publishedPlan(depot, date).isEmpty(), "a contract read with no actor sees nothing");
    assertEquals(
        Optional.of(planId),
        database.asModule(
            ModuleRole.ORDERING,
            dispatcher.userId(),
            () -> contract.publishedPlan(depot, date).map(PlanView::planId)),
        "a contract read inside another module's transaction reads as that actor");
  }

  @Test
  void aDispatcherCannotWriteAPlanForAnotherDepot() {
    assertThrows(
        RuntimeException.class,
        () ->
            database.asModule(
                ModuleRole.PLANNING,
                elsewhere.userId(),
                () -> plans.insertRun(run(UUID.randomUUID(), freshDate(), 1, Optional.empty()),UUID.randomUUID(), Instant.now())));
  }

  // ---- reads ---------------------------------------------------------------------

  @Test
  void aPlanReadsBackWithStopsInOrderAndEveryCheck() {
    LocalDate date = freshDate();
    UUID planId = draftWithOneTrip(date, 1);

    PlanView view = query.plan(dispatcher, planId);
    assertEquals(PlanStatus.DRAFT, view.status());
    assertEquals(rules.id(), view.ruleSetVersionId());
    assertEquals(policy.id(), view.priorityPolicyVersionId());
    TripView trip = view.trips().get(0);
    assertEquals(List.of(1, 2), trip.stops().stream().map(s -> s.sequence()).toList());
    assertEquals(LocalTime.of(5, 0), trip.stops().get(0).plannedArrival());

    AllocationView deferred =
        view.allocations().stream()
            .filter(a -> a.decision() == AllocationDecision.DEFERRED)
            .findFirst()
            .orElseThrow();
    assertEquals(Optional.of("R-PLN-06"), deferred.bindingRule());
    assertEquals(new BigDecimal("-1.5"), deferred.checks().get(0).slack().orElseThrow());
    assertTrue(contract.draft(planId).isEmpty(), "no ambient actor, no rows");
    assertEquals(
        1, query.deferralsFor(dispatcher, depot, date).size(), "the newest draft decides while none is published");
  }

  @Test
  void fuelCountsPublishedPlansOnly() {
    LocalDate date = freshDate();
    BigDecimal before = query.fuelRemaining(dispatcher, vehicle, date).usedLitres();
    UUID planId = draftWithOneTrip(date, 1);
    assertEquals(0, before.compareTo(query.fuelRemaining(dispatcher, vehicle, date).usedLitres()), "a draft uses no quota");

    database.asSystem(ModuleRole.PLANNING, () -> plans.publish(planId, 1, Actor.SYSTEM_ID, Instant.now()));
    var published = query.fuelRemaining(dispatcher, vehicle, date);
    assertEquals(0, before.add(new BigDecimal("42.500")).compareTo(published.usedLitres()));
    assertEquals(0, published.quotaLitres().subtract(published.usedLitres()).compareTo(published.remainingLitres()));

    database.asSystem(ModuleRole.PLANNING, () -> plans.supersede(planId, 2, Instant.now()));
    assertEquals(
        0,
        before.compareTo(query.fuelRemaining(dispatcher, vehicle, date).usedLitres()),
        "a superseded run's fuel is replaced by its successor's");
  }

  // ---- fixtures ------------------------------------------------------------------

  /** A draft with one trip of two stops, one deferral, legs and fuel, inserted as the system. */
  private UUID draftWithOneTrip(LocalDate date, int planVersion) {
    return draftWithOneTrip(date, planVersion, Optional.empty());
  }

  private UUID draftWithOneTrip(LocalDate date, int planVersion, Optional<UUID> supersedes) {
    UUID planId = UUID.randomUUID();
    UUID tripId = UUID.randomUUID();
    UUID servedA = UUID.randomUUID();
    UUID servedB = UUID.randomUUID();
    UUID deferredOrder = UUID.randomUUID();
    database.asSystem(
        ModuleRole.PLANNING,
        () -> {
          plans.insertRun(run(planId, date, planVersion, supersedes), UUID.randomUUID(), Instant.now());
          plans.insertTrips(
              planId,
              depot,
              List.of(
                  new TripRow(
                      tripId,
                      vehicle,
                      1,
                      outlet.brandCode(),
                      outlet.districtName(),
                      "ambient",
                      new BigDecimal("900.000"),
                      new BigDecimal("6.5000"),
                      new BigDecimal("101.00"),
                      LocalTime.of(3, 30),
                      new BigDecimal("42.500"))));
          plans.insertAllocations(
              planId,
              depot,
              List.of(served(servedB, tripId, 2, LocalTime.of(5, 30)), served(servedA, tripId, 1, LocalTime.of(5, 0)), deferred(deferredOrder)));
          plans.insertLegs(
              planId,
              depot,
              List.of(
                  new LegRow(tripId, 1, Optional.empty(), OUTLET, LocalTime.of(3, 30), LocalTime.of(5, 0), new BigDecimal("90")),
                  new LegRow(tripId, 2, Optional.of(OUTLET), OUTLET, LocalTime.of(5, 20), LocalTime.of(5, 30), new BigDecimal("10"))));
          plans.insertDeferrals(
              planId,
              depot,
              List.of(
                  new DeferralRow(
                      deferredOrder, OUTLET, date, "R-PLN-06", "no vehicle had room", 1, Actor.SYSTEM_ID, Instant.now())));
          plans.insertFuel(planId, depot, date, List.of(new FuelRow(vehicle, new BigDecimal("42.500"))));
        });
    return planId;
  }

  private RunRow run(UUID planId, LocalDate date, int planVersion, Optional<UUID> supersedes) {
    return new RunRow(
        planId,
        depot,
        date,
        planVersion,
        PlanStatus.DRAFT,
        referenceVersion,
        rules.id(),
        policy.id(),
        supersedes,
        "fingerprint-" + planVersion,
        false,
        false,
        "priority-insertion",
        true,
        Instant.now(),
        Actor.SYSTEM_ID,
        Optional.empty(),
        Optional.empty(),
        1);
  }

  private AllocationRow served(UUID orderId, UUID tripId, int sequence, LocalTime arrival) {
    return new AllocationRow(
        orderId,
        OUTLET,
        AllocationDecision.SERVED,
        Optional.of(tripId),
        Optional.of(sequence),
        Optional.of(arrival),
        Optional.of(LocalTime.of(4, 0)),
        Optional.of(LocalTime.of(7, 0)),
        new BigDecimal("15.00"),
        Optional.empty(),
        "served on trip 1",
        List.of(ConstraintResult.pass("R-PLN-06", "VolumeCapacity", "fits", new BigDecimal("2.5"))));
  }

  private AllocationRow deferred(UUID orderId) {
    return new AllocationRow(
        orderId,
        OUTLET,
        AllocationDecision.DEFERRED,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        new BigDecimal("15.00"),
        Optional.of("R-PLN-06"),
        "no vehicle had room",
        List.of(ConstraintResult.fail("R-PLN-06", "VolumeCapacity", "over by 1.5 m3", new BigDecimal("-1.5"))));
  }

  /** A one-day version; a null depot is global. */
  private UUID insertPolicy(String depotCode, LocalDate from, List<String> keys) {
    UUID id = UUID.randomUUID();
    database.update(
        "INSERT INTO planning.policy_versions"
            + " (policy_version_id, kind, keys, depot_code, effective_from, effective_to, note, created_at)"
            + " VALUES (?, 'deferral_priority', ?::jsonb, ?, ?, ?, 'test', now())",
        id,
        "[" + String.join(",", keys.stream().map(k -> "\"" + k + "\"").toList()) + "]",
        depotCode,
        java.sql.Date.valueOf(from),
        java.sql.Date.valueOf(from.plusDays(1)));
    return id;
  }

  private void asPlanning(Runnable work) {
    database.asSystem(ModuleRole.PLANNING, work);
  }

  private static LocalDate freshDate() {
    return LocalDate.of(2031, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 20_000));
  }

  private long deniedReads(Actor actor) {
    return database.asModule(
        ModuleRole.INTEGRATION,
        actor.userId(),
        () ->
            ((Number)
                    database
                        .queryOne(
                            "SELECT count(*) AS n FROM integration.audit_log"
                                + " WHERE actor_id = ? AND action = 'plan:Read' AND decision = 'DENY'",
                            actor.userId())
                        .get("n"))
                .longValue());
  }
}
