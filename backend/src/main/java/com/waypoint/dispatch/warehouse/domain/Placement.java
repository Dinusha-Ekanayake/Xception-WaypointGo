package com.waypoint.dispatch.warehouse.domain;

import com.waypoint.dispatch.warehouse.contract.StockPort.PlacementResult;
import com.waypoint.dispatch.warehouse.contract.StockPort.StockLine;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher.Attempt;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Waypoint's memory of one placement at the warehouse: what it asked for, every
 * time it asked, and what it was told. The warehouse keeps no client reference,
 * so this record is what turns a retry into a lookup (R-STK-11).
 *
 * @param lines what Waypoint currently wants placed
 * @param attempts every {@code POST /orders} sent, with its lines, for {@link OrphanMatcher}
 * @param result the answer to replay when the same request comes again
 * @param compensateOnly Waypoint no longer wants this placement: a lost attempt
 *     is looked for and cancelled, never sent again
 */
public record Placement(
    UUID placementId,
    String orderRef,
    UUID orderId,
    String depotCode,
    String warehouseCode,
    List<StockLine> lines,
    List<Attempt> attempts,
    State state,
    boolean compensateOnly,
    Optional<String> warehouseOrderRef,
    Optional<String> warehouseStatus,
    Optional<Instant> expiresAt,
    Optional<PlacementResult> result,
    int retryCount,
    Optional<Instant> nextAttemptAt,
    Instant createdAt,
    long rowVersion) {

  public Placement {
    lines = List.copyOf(lines);
    attempts = List.copyOf(attempts);
  }

  public enum State {
    QUEUED,
    ATTEMPTING,
    UNKNOWN,
    PLACED,
    PARTIAL,
    REJECTED,
    AMBIGUOUS,
    RELEASED,
    MERGED;

    public String code() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static State of(String code) {
      return valueOf(code.toUpperCase(java.util.Locale.ROOT));
    }

    /** A warehouse order may exist that this record cannot yet name. */
    public boolean outcomeUnknown() {
      return this == ATTEMPTING || this == UNKNOWN;
    }
  }

  /** What a synchronous placement request should do, given this record. */
  public enum OnRequest {
    /** Nothing is held and nothing is in doubt: send it. */
    SEND,
    /** The same request already succeeded: answer what the warehouse said then. */
    REPLAY,
    /** An earlier attempt's outcome is unknown: sending now could double-reserve. */
    WAIT
  }

  public static OnRequest onRequest(Optional<Placement> existing, List<StockLine> lines) {
    if (existing.isEmpty()) {
      return OnRequest.SEND;
    }
    Placement p = existing.get();
    return switch (p.state()) {
      case QUEUED, REJECTED, RELEASED -> OnRequest.SEND;
      case PLACED, PARTIAL ->
          OrphanMatcher.fingerprint(p.lines()).equals(OrphanMatcher.fingerprint(lines))
                  && p.result().isPresent()
              ? OnRequest.REPLAY
              : OnRequest.WAIT;
      case ATTEMPTING, UNKNOWN, AMBIGUOUS, MERGED -> OnRequest.WAIT;
    };
  }

  /** Placements still waiting on the retry job count as stock unknown. */
  public boolean awaitingWarehouse() {
    return state == State.QUEUED || state.outcomeUnknown();
  }

  /** Every attempt so far, plus one sent at {@code now} with the current lines. */
  public List<Attempt> withAttempt(Instant now) {
    List<Attempt> next = new java.util.ArrayList<>(attempts);
    next.add(new Attempt(lines, now));
    return next;
  }
}
