package com.waypoint.dispatch.identity.contract;

import com.waypoint.dispatch.shared.domain.Actor;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Validates the person who recorded one queued command on a shared device. */
public interface OperatorQuery {
  Optional<Actor> operatorAt(String sessionToken, UUID userId, Instant recordedAt);
}
