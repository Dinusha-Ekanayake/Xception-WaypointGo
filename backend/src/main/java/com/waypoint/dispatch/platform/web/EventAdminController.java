package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.platform.messaging.DeadLetterQuery;
import com.waypoint.dispatch.platform.messaging.DeadLetterQuery.DeadLetterView;
import com.waypoint.dispatch.platform.messaging.ReplayEventHandler;
import com.waypoint.dispatch.shared.domain.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads for the event backbone. The only write, replaying a dead letter, is the
 * command {@code platform:ReplayEvent} through the command endpoint.
 */
@RestController
@RequestMapping("/api/platform/events")
public class EventAdminController {
  private final DeadLetterQuery deadLetters;
  private final RequestAuthorizer authorizer;

  public EventAdminController(DeadLetterQuery deadLetters, RequestAuthorizer authorizer) {
    this.deadLetters = deadLetters;
    this.authorizer = authorizer;
  }

  /**
   * Dead-lettered events, newest first. Read under the replay action: whoever
   * may replay a dead letter is who needs to see them, and nobody else does.
   */
  @GetMapping("/dead")
  public Page<DeadLetterView> dead(
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    var actor = authorizer.require(request, ReplayEventHandler.ACTION, "wpt:platform:event:*");
    return deadLetters.deadLetters(actor, cursor, limit);
  }
}
