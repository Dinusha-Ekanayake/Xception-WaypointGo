package com.waypoint.dispatch.ordering.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** Administrator-only preparation checks, answered by Ordering under its own role. */
public interface DemoDayQuery {
  Optional<Instant> latestClose();
  boolean empty(String depotCode, LocalDate day);
}
