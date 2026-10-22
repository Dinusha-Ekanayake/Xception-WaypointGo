package com.waypoint.dispatch.intelligence.domain;

import com.waypoint.dispatch.intelligence.contract.ModelViews.ModelStatus;
import com.waypoint.dispatch.intelligence.contract.ModelViews.ShadowRole;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The legal moves of the model registry, and what each does to the other models
 * of the kind (issue #12). At most one model of a kind is active and at most one
 * is the shadow (R-ML-03, R-ML-40).
 *
 * <ul>
 *   <li><b>Shadow</b> a registered model: it becomes the {@code CANDIDATE} shadow;
 *       a shadow already there returns to registered.
 *   <li><b>Activate</b> a registered or shadow model: it becomes active, the
 *       model it replaces becomes the {@code REPLACED} shadow (the champion stays
 *       the challenger, R-ML-42), and any other shadow returns to registered.
 *   <li><b>Roll back</b>: only a {@code REPLACED} shadow, back to active; the
 *       model that replaced it returns to registered. The system's one move, and
 *       only ever backwards.
 * </ul>
 *
 * <p>The status {@code CHECK} in SQL restricts values, not moves; this decides
 * the moves. Pure.
 */
public final class ModelRegistryPolicy {
  private ModelRegistryPolicy() {}

  /** One model of the kind as it stands. */
  public record Standing(UUID id, ModelStatus status, Optional<ShadowRole> role) {
    public static Standing of(UUID id, ModelStatus status) {
      return new Standing(id, status, Optional.empty());
    }
  }

  /** Where a model moves to. */
  public record Move(ModelStatus to, Optional<ShadowRole> role) {
    static Move registered() {
      return new Move(ModelStatus.REGISTERED, Optional.empty());
    }

    static Move active() {
      return new Move(ModelStatus.ACTIVE, Optional.empty());
    }

    static Move shadow(ShadowRole role) {
      return new Move(ModelStatus.SHADOW, Optional.of(role));
    }
  }

  /** Every model a move changes, the target first. Empty when there is nothing to do. */
  public static Map<UUID, Move> shadow(Standing target, List<Standing> kind) {
    switch (target.status()) {
      case RETIRED -> throw refused("A retired model cannot be made the shadow");
      case ACTIVE -> throw refused("The active model cannot also be the shadow; activate another model first");
      case SHADOW -> {
        return Map.of();
      }
      default -> { }
    }
    Map<UUID, Move> out = new LinkedHashMap<>();
    out.put(target.id(), Move.shadow(ShadowRole.CANDIDATE));
    for (Standing other : kind) {
      if (!other.id().equals(target.id()) && other.status() == ModelStatus.SHADOW) {
        out.put(other.id(), Move.registered());
      }
    }
    return out;
  }

  public static Map<UUID, Move> activate(Standing target, List<Standing> kind) {
    switch (target.status()) {
      case RETIRED -> throw refused("A retired model cannot be activated", "R-ML-03");
      case ACTIVE -> {
        return Map.of();
      }
      default -> { }
    }
    Map<UUID, Move> out = new LinkedHashMap<>();
    out.put(target.id(), Move.active());
    for (Standing other : kind) {
      if (other.id().equals(target.id())) {
        continue;
      }
      if (other.status() == ModelStatus.ACTIVE) {
        out.put(other.id(), Move.shadow(ShadowRole.REPLACED));
      } else if (other.status() == ModelStatus.SHADOW) {
        out.put(other.id(), Move.registered());
      }
    }
    return out;
  }

  /** R-ML-42: the replaced model back to active, the one that replaced it to registered. */
  public static Map<UUID, Move> rollBack(Standing active, Standing shadow) {
    if (active.status() != ModelStatus.ACTIVE) {
      throw refused("Only the active model is rolled back");
    }
    if (shadow.status() != ModelStatus.SHADOW || shadow.role().orElse(null) != ShadowRole.REPLACED) {
      throw refused("Only the model a promotion replaced is put back; a candidate is promoted by a person");
    }
    Map<UUID, Move> out = new LinkedHashMap<>();
    out.put(shadow.id(), Move.active());
    out.put(active.id(), Move.registered());
    return out;
  }

  private static DomainException refused(String message) {
    return refused(message, "R-ML-40");
  }

  private static DomainException refused(String message, String rule) {
    return new DomainException(ErrorCode.CONFLICT, message, List.of(rule));
  }
}
