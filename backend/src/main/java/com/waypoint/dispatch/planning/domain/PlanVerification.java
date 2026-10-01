package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.Constraint.Candidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Whether a whole plan holds, the check behind both {@code ValidatingEngine} and
 * the publication gate. One definition, so "the engine accepted it" and "the
 * dispatcher may publish it" can never disagree.
 *
 * <p>Beyond the per-vehicle registry it checks what the supplied validator
 * checks across the plan: every order has exactly one decision, a served order
 * is on exactly one trip of the vehicle it names, and a deferral names its rule.
 */
public final class PlanVerification {
  private PlanVerification() {}

  /** A failed rule, with the vehicle or order it concerns. */
  public record Violation(String ruleId, String subject, String reason) {}

  public static List<Violation> verify(
      AllocationResult result, Set<UUID> demand, PlanContext context, ConstraintRegistry registry) {
    List<Violation> out = new ArrayList<>();

    Map<UUID, String> carriedBy = new HashMap<>();
    for (VehicleDay day : result.days()) {
      for (Trip t : day.trips()) {
        for (PlanOrder o : t.orders()) {
          String previous = carriedBy.put(o.orderId(), day.vehicleId());
          if (previous != null) {
            out.add(new Violation("R-PLN-05", o.orderRef(), "carried by " + previous + " and " + day.vehicleId()));
          }
        }
      }
    }

    for (VehicleDay day : result.days()) {
      for (ConstraintResult r : registry.evaluate(new Candidate(day, context, Set.of()))) {
        if (!r.passed()) {
          out.add(new Violation(r.ruleId(), day.vehicleId(), r.reason()));
        }
      }
    }

    Set<UUID> decided = new HashSet<>();
    for (OrderDecision d : result.decisions()) {
      if (!decided.add(d.orderId())) {
        out.add(new Violation("R-PLN-05", d.orderId().toString(), "has more than one decision"));
      }
      boolean carried = carriedBy.containsKey(d.orderId());
      if (d.decision() == AllocationDecision.SERVED) {
        if (!carried || !d.vehicleId().map(carriedBy.get(d.orderId())::equals).orElse(false)) {
          out.add(new Violation("R-PLN-05", d.orderId().toString(), "is served but not on the trip it names"));
        }
      } else {
        if (carried) {
          out.add(new Violation("R-PLN-05", d.orderId().toString(), "is " + d.decision() + " but still on a trip"));
        }
        if (d.bindingRule().isEmpty()) {
          out.add(new Violation("R-PLN-19", d.orderId().toString(), "is " + d.decision() + " without a binding rule"));
        }
      }
    }
    for (UUID id : demand) {
      if (!decided.contains(id)) {
        out.add(new Violation("PLN-07", id.toString(), "is in the demand but has no decision"));
      }
    }
    for (UUID id : decided) {
      if (!demand.contains(id)) {
        out.add(new Violation("PLN-07", id.toString(), "has a decision but is not in the demand"));
      }
    }
    return out;
  }
}
