package com.waypoint.dispatch.platform.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Which policy versions governed an actor at a moment in the past.
 *
 * <p>A port, because platform may not import Identity (the audit read must be
 * able to say what rules a decision was taken under, and the rules are Identity's).
 * Identity supplies the implementation.
 */
public interface PolicyHistory {

  /** One policy attached to the actor, at the version that was its default then. */
  record VersionInForce(String policy, int version) {}

  /**
   * @param currentGeneration the policy generation now, to compare with the one
   *     stamped on an audit row: equal means nothing changed since the decision
   * @param versions the versions reconstructed for the moment asked about. Exact
   *     when the generation is unchanged; otherwise the latest version created
   *     before that moment of each policy attached today, which cannot see an
   *     attachment removed since
   */
  record Snapshot(long currentGeneration, List<VersionInForce> versions) {}

  Snapshot at(UUID actorId, Instant moment);
}
