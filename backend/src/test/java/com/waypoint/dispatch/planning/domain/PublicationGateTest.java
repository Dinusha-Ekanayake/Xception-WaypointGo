package com.waypoint.dispatch.planning.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.planning.domain.PlanVerification.Violation;
import com.waypoint.dispatch.planning.domain.PlanningRun.Stamps;
import com.waypoint.dispatch.planning.domain.PublicationGate.InForce;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PLN-07, PLN-14, POL-02: a draft is published only under what built it. */
class PublicationGateTest {
  final UUID plan = UUID.randomUUID();
  final Stamps stamps = new Stamps(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

  InForce same() {
    return new InForce(
        "fp", Optional.of(stamps.referenceVersionId()), Optional.of(stamps.ruleSetId()),
        Optional.of(stamps.policyVersionId()));
  }

  List<String> rules(InForce now, boolean stale) {
    return PublicationGate.check(plan, stamps, "fp", stale, now).stream().map(Violation::ruleId).toList();
  }

  @Test
  void anUnchangedDraftPasses() {
    assertTrue(rules(same(), false).isEmpty());
  }

  @Test
  void changedDemandBlocks() {
    InForce now = new InForce("other", same().referenceVersionId(), same().ruleSetId(), same().policyVersionId());
    assertEquals(List.of("PLN-07"), rules(now, false));
  }

  @Test
  void aStaleDraftBlocks() {
    assertEquals(List.of("PLN-07"), rules(same(), true));
  }

  @Test
  void aNewReferenceVersionBlocks() {
    InForce now = new InForce("fp", Optional.of(UUID.randomUUID()), same().ruleSetId(), same().policyVersionId());
    assertEquals(List.of("PLN-14"), rules(now, false));
  }

  @Test
  void aChangedRuleSetOrPolicyBlocksAndEveryReasonIsReported() {
    InForce now = new InForce("other", same().referenceVersionId(), Optional.empty(), Optional.of(UUID.randomUUID()));
    assertEquals(List.of("PLN-07", "POL-02", "POL-02"), rules(now, false));
  }

  @Test
  void theFingerprintIgnoresOrderButNotVersions() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    String one = DemandFingerprint.of(Map.of(a, 1L, b, 1L));
    assertEquals(one, DemandFingerprint.of(new java.util.LinkedHashMap<>(Map.of(b, 1L, a, 1L))));
    assertNotEquals(one, DemandFingerprint.of(Map.of(a, 1L, b, 2L)), "an amended order changes the demand");
    assertNotEquals(one, DemandFingerprint.of(Map.of(a, 1L)), "a cancelled order changes the demand");
  }
}
