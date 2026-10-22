package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.ModelViews.ModelStatus;
import com.waypoint.dispatch.intelligence.domain.ModelRegistryPolicy;
import com.waypoint.dispatch.intelligence.domain.ModelRegistryPolicy.Move;
import com.waypoint.dispatch.intelligence.domain.ModelRegistryPolicy.Standing;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository.ModelRow;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies a registry move decided by {@link ModelRegistryPolicy}, inside the
 * transaction of the command or job that decided it (issue #12).
 *
 * <p>One active and one shadow per kind are unique indexes checked per
 * statement, so every model the move changes first leaves the active or shadow
 * standing, then each is set where it goes: active, then shadow. The model the
 * caller named is version-guarded throughout (rule 6); the others were read in
 * the same serializable transaction. Each change is written to
 * {@code ml.model_events} with its actor (rule 8).
 */
final class RegistryMoves {
  private static final SecureRandom RANDOM = new SecureRandom();

  private RegistryMoves() {}

  static Standing standing(ModelRow row) {
    return new Standing(row.id(), row.view().status(), row.view().shadowRole());
  }

  static List<Standing> standings(List<ModelRow> rows) {
    return rows.stream().map(RegistryMoves::standing).toList();
  }

  /**
   * @param kind every model of the kind as read in this transaction
   * @param target the model the caller named, whose version is guarded
   * @param activated the event action written for a model moved to active: {@code activated}, or
   *     {@code rolled_back} for the system's rollback
   * @return the target's new row version
   */
  static long apply(
      JdbcIntelligenceRepository repository, List<ModelRow> kind, Map<UUID, Move> moves, UUID target,
      long expected, UUID actor, Instant now, String activated, Optional<String> reason, Optional<UUID> review) {
    long version = expected;
    for (UUID id : moves.keySet()) {
      ModelStatus current = statusOf(kind, id);
      if (current == ModelStatus.ACTIVE || current == ModelStatus.SHADOW) {
        if (id.equals(target)) {
          repository.move(id, new Move(ModelStatus.REGISTERED, Optional.empty()), Optional.of(version), actor, now);
          version++;
        } else {
          repository.vacate(id, now);
        }
      }
    }
    for (ModelStatus phase : List.of(ModelStatus.ACTIVE, ModelStatus.SHADOW, ModelStatus.REGISTERED)) {
      for (Map.Entry<UUID, Move> e : moves.entrySet()) {
        if (e.getValue().to() != phase) {
          continue;
        }
        UUID id = e.getKey();
        boolean guarded = id.equals(target);
        if (phase != ModelStatus.REGISTERED || statusOf(kind, id) == ModelStatus.REGISTERED) {
          repository.move(id, e.getValue(), guarded ? Optional.of(version) : Optional.empty(), actor, now);
          if (guarded) {
            version++;
          }
        }
        String action = switch (phase) {
          case ACTIVE -> activated;
          case SHADOW -> "shadowed";
          default -> "demoted";
        };
        repository.insertModelEvent(UuidV7.generate(now, RANDOM), id, action, actor, reason,
            "rolled_back".equals(action) ? review : Optional.empty(), now);
      }
    }
    return version;
  }

  private static ModelStatus statusOf(List<ModelRow> kind, UUID id) {
    return kind.stream().filter(r -> r.id().equals(id)).findFirst()
        .map(r -> r.view().status())
        .orElseThrow(() -> new IllegalStateException("model " + id + " is not of this kind"));
  }
}
