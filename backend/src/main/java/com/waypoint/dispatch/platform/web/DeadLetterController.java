package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.platform.messaging.DeadLetterQuery;
import com.waypoint.dispatch.platform.messaging.DeadLetterQuery.DeadLetterView;
import com.waypoint.dispatch.shared.domain.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading the dead-letter queue. Replaying an event is a command
 * ({@code platform.replay-event}) through {@code POST /api/commands}, never an
 * endpoint here.
 *
 * <p>Administrators only: the grant is {@code platform:ReplayEvent}, which is what
 * a replay needs as well, so nobody can see a dead letter they could not act on.
 */
@RestController
@RequestMapping("/api/platform/dead-letters")
public class DeadLetterController {
  private final DeadLetterQuery deadLetters;
  private final RequestAuthorizer authorizer;

  public DeadLetterController(DeadLetterQuery deadLetters, RequestAuthorizer authorizer) {
    this.deadLetters = deadLetters;
    this.authorizer = authorizer;
  }

  @GetMapping
  public Page<DeadLetterView> list(
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") int limit,
      HttpServletRequest request) {
    var actor = authorizer.require(request, DeadLetterQuery.READ, "wpt:platform:event:*");
    return deadLetters.list(actor, Optional.ofNullable(cursor), limit);
  }

  @GetMapping("/{eventId}")
  public DeadLetterView one(@PathVariable UUID eventId, HttpServletRequest request) {
    var actor = authorizer.require(request, DeadLetterQuery.READ, "wpt:platform:event:" + eventId);
    return deadLetters.get(actor, eventId);
  }
}
