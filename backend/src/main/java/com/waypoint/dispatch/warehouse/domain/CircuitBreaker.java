package com.waypoint.dispatch.warehouse.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Stops calling the warehouse while it is failing, so a placement answers
 * {@code Unavailable} at once instead of holding a store manager and a database
 * connection for the whole timeout (STK-04).
 *
 * <p>Closed counts consecutive failures; at the threshold it opens. Open refuses
 * every call until {@code openFor} has passed, then lets exactly one trial call
 * through (half open). The trial's outcome closes or reopens it. Time is a
 * parameter, so every transition is testable without waiting.
 */
public final class CircuitBreaker {

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN;

    public String code() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  private final int failureThreshold;
  private final Duration openFor;

  private State state = State.CLOSED;
  private int consecutiveFailures;
  private Instant openedAt;
  private boolean trialInFlight;

  public CircuitBreaker(int failureThreshold, Duration openFor) {
    if (failureThreshold < 1) {
      throw new IllegalArgumentException("failureThreshold must be at least 1");
    }
    if (openFor.isNegative() || openFor.isZero()) {
      throw new IllegalArgumentException("openFor must be positive");
    }
    this.failureThreshold = failureThreshold;
    this.openFor = openFor;
  }

  /** @return true when a call may be made now; a half-open circuit admits one trial */
  public synchronized boolean allow(Instant now) {
    if (state == State.OPEN && !now.isBefore(openedAt.plus(openFor))) {
      state = State.HALF_OPEN;
      trialInFlight = false;
    }
    return switch (state) {
      case CLOSED -> true;
      case OPEN -> false;
      case HALF_OPEN -> {
        if (trialInFlight) {
          yield false;
        }
        trialInFlight = true;
        yield true;
      }
    };
  }

  public synchronized void onSuccess() {
    state = State.CLOSED;
    consecutiveFailures = 0;
    trialInFlight = false;
  }

  public synchronized void onFailure(Instant now) {
    consecutiveFailures++;
    if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
      state = State.OPEN;
      openedAt = now;
      trialInFlight = false;
    }
  }

  /** The state as of {@code now}, without admitting a call. */
  public synchronized State state(Instant now) {
    if (state == State.OPEN && !now.isBefore(openedAt.plus(openFor))) {
      return State.HALF_OPEN;
    }
    return state;
  }
}
