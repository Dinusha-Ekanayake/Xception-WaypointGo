package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.contract.ModelViews.ModelStatus;
import com.waypoint.dispatch.intelligence.contract.ModelViews.ShadowRole;
import com.waypoint.dispatch.intelligence.domain.ModelRegistryPolicy.Move;
import com.waypoint.dispatch.intelligence.domain.ModelRegistryPolicy.Standing;
import com.waypoint.dispatch.shared.error.DomainException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #12: the registry's moves, and what each does to the other models of the kind. */
class ModelRegistryPolicyTest {
  private static final Standing ACTIVE = Standing.of(UUID.randomUUID(), ModelStatus.ACTIVE);
  private static final Standing CANDIDATE =
      new Standing(UUID.randomUUID(), ModelStatus.SHADOW, Optional.of(ShadowRole.CANDIDATE));
  private static final Standing REPLACED =
      new Standing(UUID.randomUUID(), ModelStatus.SHADOW, Optional.of(ShadowRole.REPLACED));
  private static final Standing REGISTERED = Standing.of(UUID.randomUUID(), ModelStatus.REGISTERED);
  private static final Standing RETIRED = Standing.of(UUID.randomUUID(), ModelStatus.RETIRED);

  private static Move shadow(ShadowRole role) {
    return new Move(ModelStatus.SHADOW, Optional.of(role));
  }

  private static final Move TO_ACTIVE = new Move(ModelStatus.ACTIVE, Optional.empty());
  private static final Move TO_REGISTERED = new Move(ModelStatus.REGISTERED, Optional.empty());

  @Test
  void aRegisteredModelBecomesTheCandidateShadowAndTheShadowBeforeItReturnsToRegistered() {
    Map<UUID, Move> moves = ModelRegistryPolicy.shadow(REGISTERED, List.of(ACTIVE, CANDIDATE, REGISTERED));
    assertEquals(Map.of(REGISTERED.id(), shadow(ShadowRole.CANDIDATE), CANDIDATE.id(), TO_REGISTERED), moves);
    assertEquals(REGISTERED.id(), moves.keySet().iterator().next(), "the model named first");
  }

  @Test
  void shadowingTheShadowChangesNothingAndTheActiveOrARetiredModelIsRefused() {
    assertTrue(ModelRegistryPolicy.shadow(CANDIDATE, List.of(ACTIVE, CANDIDATE)).isEmpty());
    assertEquals("R-ML-40", rule(() -> ModelRegistryPolicy.shadow(ACTIVE, List.of(ACTIVE))));
    assertEquals("R-ML-40", rule(() -> ModelRegistryPolicy.shadow(RETIRED, List.of(ACTIVE))));
  }

  @Test
  void promotingTheShadowKeepsTheModelItReplacesAsTheChallenger() {
    Map<UUID, Move> moves = ModelRegistryPolicy.activate(CANDIDATE, List.of(ACTIVE, CANDIDATE, REGISTERED));
    assertEquals(Map.of(CANDIDATE.id(), TO_ACTIVE, ACTIVE.id(), shadow(ShadowRole.REPLACED)), moves);
  }

  @Test
  void activatingAnotherModelSendsTheShadowBackAndTheOldActiveBecomesTheShadow() {
    Map<UUID, Move> moves = ModelRegistryPolicy.activate(REGISTERED, List.of(ACTIVE, CANDIDATE, REGISTERED));
    assertEquals(Map.of(REGISTERED.id(), TO_ACTIVE, ACTIVE.id(), shadow(ShadowRole.REPLACED),
        CANDIDATE.id(), TO_REGISTERED), moves);
  }

  @Test
  void theFirstActivationHasNoChallengerAndActivatingTheActiveChangesNothing() {
    assertEquals(Map.of(REGISTERED.id(), TO_ACTIVE), ModelRegistryPolicy.activate(REGISTERED, List.of(REGISTERED)));
    assertTrue(ModelRegistryPolicy.activate(ACTIVE, List.of(ACTIVE, CANDIDATE)).isEmpty());
    assertEquals("R-ML-03", rule(() -> ModelRegistryPolicy.activate(RETIRED, List.of(ACTIVE))));
  }

  @Test
  void onlyTheReplacedModelIsPutBackAndTheNewerOneReturnsToRegistered() {
    assertEquals(Map.of(REPLACED.id(), TO_ACTIVE, ACTIVE.id(), TO_REGISTERED),
        ModelRegistryPolicy.rollBack(ACTIVE, REPLACED));
    assertEquals("R-ML-40", rule(() -> ModelRegistryPolicy.rollBack(ACTIVE, CANDIDATE)),
        "a candidate is promoted by a person, never by the system");
    assertEquals("R-ML-40", rule(() -> ModelRegistryPolicy.rollBack(REGISTERED, REPLACED)));
  }

  private static String rule(Runnable move) {
    return assertThrows(DomainException.class, move::run).rules().get(0);
  }
}
