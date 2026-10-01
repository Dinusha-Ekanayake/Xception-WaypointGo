package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.domain.PlanVerification.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether a draft was built on what is in force now. A draft is a decision
 * about specific demand under specific rules; if either moved, publishing it
 * would publish a decision nobody made (PLN-07, PLN-14, POL-02). The plan
 * itself is then re-checked by {@link PlanVerification}.
 */
public final class PublicationGate {
  private PublicationGate() {}

  /** What is in force at the moment of publication. Empty means nothing is. */
  public record InForce(
      String demandFingerprint,
      Optional<UUID> referenceVersionId,
      Optional<UUID> ruleSetId,
      Optional<UUID> policyVersionId) {}

  public static List<Violation> check(PlanningRun run, InForce now) {
    return check(run.planId(), run.stamps(), run.demandFingerprint(), run.stale(), now);
  }

  /**
   * The same check on a draft's header alone, so a draft whose demand moved is
   * refused with every reason at once, before anything tries to rebuild it.
   */
  public static List<Violation> check(
      UUID planId, PlanningRun.Stamps stamps, String demandFingerprint, boolean stale, InForce now) {
    List<Violation> out = new ArrayList<>();
    String plan = "plan " + planId;
    if (stale) {
      out.add(new Violation("PLN-07", plan, "the draft was marked stale; generate it again"));
    }
    if (!demandFingerprint.equals(now.demandFingerprint())) {
      out.add(new Violation("PLN-07", plan, "orders changed since the draft was built; generate it again"));
    }
    if (!now.referenceVersionId().equals(Optional.of(stamps.referenceVersionId()))) {
      out.add(
          new Violation(
              "PLN-14", plan,
              "built on reference version " + stamps.referenceVersionId() + ", now "
                  + now.referenceVersionId().map(UUID::toString).orElse("none")));
    }
    if (!now.ruleSetId().equals(Optional.of(stamps.ruleSetId()))) {
      out.add(
          new Violation(
              "POL-02", plan,
              "built under rule set " + stamps.ruleSetId() + ", now "
                  + now.ruleSetId().map(UUID::toString).orElse("none")));
    }
    if (!now.policyVersionId().equals(Optional.of(stamps.policyVersionId()))) {
      out.add(
          new Violation(
              "POL-02", plan,
              "built under priority policy " + stamps.policyVersionId() + ", now "
                  + now.policyVersionId().map(UUID::toString).orElse("none")));
    }
    return out;
  }
}
