package com.waypoint.dispatch.sync.web;

import com.waypoint.dispatch.platform.web.ActorResolver;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.sync.application.OperationsQuery;
import com.waypoint.dispatch.sync.application.SubmitBatchHandler;
import com.waypoint.dispatch.sync.application.SubmitBatchHandler.Outcome;
import com.waypoint.dispatch.sync.contract.SyncCommands.SubmitBatch;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a device sends what it queued offline, and reads back what became of it.
 *
 * <p>Thin: resolve the caller, hand over, translate "at capacity" into 429. The jitter on
 * {@code Retry-After} is the point of it; a regional reconnect told to wait the same five seconds
 * comes back in lockstep and the recovery becomes the outage.
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {
  private final SubmitBatchHandler submit;
  private final OperationsQuery query;
  private final Optional<ActorResolver> actors;

  public SyncController(
      SubmitBatchHandler submit, OperationsQuery query, Optional<ActorResolver> actors) {
    this.submit = submit;
    this.query = query;
    this.actors = actors;
  }

  public record SyncAck(List<Outcome> results) {}

  @PostMapping
  public ResponseEntity<?> submit(@RequestBody SubmitBatch body, HttpServletRequest request) {
    Actor actor = deviceActor(request);
    if (body == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "A batch is required");
    }
    try {
      return ResponseEntity.ok(new SyncAck(submit.submit(
          actor, actors.flatMap(resolver -> Optional.ofNullable(resolver.sessionCredential(request))).orElse(null), body)));
    } catch (SubmitBatchHandler.Busy e) {
      // ApiExceptionHandler answers 429 RATE_LIMITED with this Retry-After, in the
      // same problem body as every other failure.
      throw DomainException.rateLimited(
          "Sync is busy. Your changes are safe on the device; they will be sent shortly.",
          ThreadLocalRandom.current().nextInt(2, 11));
    }
  }

  @GetMapping
  public OperationsQuery.Page since(
      @RequestParam(required = false) String since, HttpServletRequest request) {
    return query.since(actor(request), OperationsQuery.Cursor.parse(since));
  }

  private Actor actor(HttpServletRequest request) {
    return actors
        .flatMap(resolver -> resolver.resolve(request))
        .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED, "Not signed in"));
  }

  private Actor deviceActor(HttpServletRequest request) {
    return actors
        .flatMap(resolver -> resolver.resolveDevice(request))
        .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED, "Not signed in"));
  }
}
