package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.application.PlanRecords.Rows;
import com.waypoint.dispatch.planning.application.PlanningProblems.Built;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanStatus;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import com.waypoint.dispatch.planning.domain.RuleSet;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.AllocationRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.TripRow;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * What every draft command does around its decision: find the draft at the
 * version the caller saw, rebuild it against the versions it was stamped with,
 * and store a successor in place of it. Runs inside the command's transaction.
 */
@Component
class PlanningDrafts {
  private static final int MAX_DIFF = 20;

  private final JdbcPlanRepository plans;
  private final PlanningProblems problems;
  private final SecureRandom random = new SecureRandom();

  PlanningDrafts(JdbcPlanRepository plans, PlanningProblems problems) {
    this.plans = plans;
    this.problems = problems;
  }

  /** A draft as stored, and the problem rebuilt at its stamps against today's demand and fuel. */
  record Opened(RunRow row, Built built, RuleSet rules) {}

  static long expectedVersion(Command command) {
    if (command.expectedVersion() == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required to change a plan");
    }
    return command.expectedVersion();
  }

  /** Every dispatcher decision carries a reason someone can read later (rule 8, R-PLN-19). */
  static String reason(com.waypoint.dispatch.platform.messaging.CommandPayload payload) {
    String reason = payload.requiredText("reason").trim();
    if (reason.length() < 3) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "A reason of at least three characters is required");
    }
    return reason;
  }

  /**
   * The draft at {@code expected}. Out of scope and absent look the same. A
   * draft that moved on is refused with what changed, so the dispatcher who
   * lost the race sees the other's decisions rather than a bare conflict (PLN-06).
   */
  Opened open(UUID planId, long expected) {
    RunRow row =
        plans.findRun(planId).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No plan " + planId));
    if (row.status() == PlanStatus.CANCELLED || row.rowVersion() != expected) {
      throw stale(row, expected);
    }
    if (row.status() != PlanStatus.DRAFT) {
      throw new DomainException(
          ErrorCode.CONFLICT, "plan " + planId + " is " + row.status() + "; only a draft changes", List.of("R-PLN-28"));
    }
    RuleSet rules = plans.ruleSet(row.ruleSetId());
    PriorityPolicy policy =
        plans.policy(row.priorityPolicyVersionId())
            .orElseThrow(() -> new IllegalStateException("plan " + planId + " names a missing policy version"));
    Built built =
        problems.build(row.depotCode(), row.serviceDate(), row.referenceVersionId(), rules, policy, row.supersedes());
    return new Opened(row, built, rules);
  }

  /** An override or a deferral on a draft whose demand moved would decide for orders nobody saw (PLN-07). */
  static void requireSameDemand(Opened opened) {
    if (!opened.built().fingerprint().equals(opened.row().demandFingerprint())) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "orders changed since plan " + opened.row().planId() + " was built; generate it again",
          List.of("PLN-07"));
    }
  }

  PlanningRun run(Opened opened) {
    RunRow row = opened.row();
    return PlanRecords.load(
        row, plans.trips(row.planId()), plans.allocations(row.planId()), plans.deferrals(row.planId()), opened.built());
  }

  int nextVersion(String depotCode, java.time.LocalDate serviceDate) {
    return plans.latestPlanVersion(depotCode, serviceDate) + 1;
  }

  UUID newId(Instant now) {
    return UuidV7.generate(now, random);
  }

  /** Cancels the version the caller edited and stores its successor, in one transaction. */
  long replace(Opened opened, PlanningRun next, UUID actor, Instant now, UUID commandId) {
    plans.cancel(opened.row().planId(), opened.row().rowVersion(), now);
    write(next, opened.built(), actor, now, commandId);
    return next.rowVersion();
  }

  void write(PlanningRun run, Built built, UUID actor, Instant now, UUID commandId) {
    Rows rows = PlanRecords.of(run, built, actor, now, () -> newId(now));
    String depot = run.depotCode();
    plans.insertRun(rows.run(), commandId, now);
    plans.insertTrips(run.planId(), depot, rows.trips());
    plans.insertAllocations(run.planId(), depot, rows.allocations());
    plans.insertLegs(run.planId(), depot, rows.legs());
    plans.insertDeferrals(run.planId(), depot, rows.deferrals());
    plans.insertFuel(run.planId(), depot, run.serviceDate(), rows.fuel());
  }

  static Map<String, Object> body(PlanningRun run) {
    return Map.of(
        "planId", run.planId().toString(),
        "planVersion", run.planVersion(),
        "status", run.status().name(),
        "rowVersion", run.rowVersion(),
        "served", run.count(com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision.SERVED),
        "deferred", run.count(com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision.DEFERRED),
        "unservable", run.count(com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision.UNSERVABLE),
        "partial", run.partial());
  }

  private DomainException stale(RunRow row, long expected) {
    StringBuilder message =
        new StringBuilder("plan " + row.planId() + " changed since version " + expected + " was read");
    Optional<RunRow> current =
        plans.latestDraft(row.depotCode(), row.serviceDate()).filter(c -> !c.planId().equals(row.planId()));
    if (current.isPresent()) {
      RunRow c = current.get();
      message.append("; the current draft is ").append(c.planId()).append(" (plan version ")
          .append(c.planVersion()).append(", row version ").append(c.rowVersion()).append(")");
      List<String> diff = diff(row.planId(), c.planId());
      if (!diff.isEmpty()) {
        message.append(". Changed: ").append(String.join("; ", diff));
      }
    }
    return new DomainException(ErrorCode.VERSION_CONFLICT, message.toString(), List.of("PLN-06"));
  }

  /** Each order whose decision or place differs between two versions, as "order: before -> after". */
  private List<String> diff(UUID before, UUID after) {
    Map<UUID, String> a = placements(before);
    Map<UUID, String> b = placements(after);
    List<String> out = new ArrayList<>();
    for (UUID orderId : new TreeSet<>(union(a, b))) {
      String was = a.getOrDefault(orderId, "absent");
      String now = b.getOrDefault(orderId, "absent");
      if (!was.equals(now)) {
        out.add(orderId + ": " + was + " -> " + now);
      }
    }
    if (out.size() > MAX_DIFF) {
      int more = out.size() - MAX_DIFF;
      out = new ArrayList<>(out.subList(0, MAX_DIFF));
      out.add("and " + more + " more");
    }
    return out;
  }

  private Map<UUID, String> placements(UUID planId) {
    Map<UUID, TripRow> trips =
        plans.trips(planId).stream().collect(Collectors.toMap(TripRow::tripId, Function.identity()));
    Map<UUID, String> out = new TreeMap<>();
    for (AllocationRow a : plans.allocations(planId)) {
      String place =
          a.tripId().map(trips::get).map(t -> " on " + t.vehicleId() + " trip " + t.tripNumber()).orElse("");
      out.put(a.orderId(), a.decision().name().toLowerCase(java.util.Locale.ROOT) + place);
    }
    return out;
  }

  private static java.util.Set<UUID> union(Map<UUID, String> a, Map<UUID, String> b) {
    java.util.Set<UUID> keys = new java.util.HashSet<>(a.keySet());
    keys.addAll(b.keySet());
    return keys;
  }
}
