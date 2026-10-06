package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.platform.messaging.ChangeSignals;
import com.waypoint.dispatch.platform.messaging.ChangeWatchers;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The dispatcher's board as server-sent events (roadmap F-07b): an event named
 * {@code change} with a topic and a depot whenever something committed there,
 * and {@code ping} every 25 s. The board re-reads that topic through its usual
 * endpoints and polls only every 15 s as a safety net; a client that hears
 * nothing for longer goes back to its normal polling and says so.
 *
 * <p>Every depot asked for is checked by policy and by scope before the stream
 * opens: one outside the reader's scope is {@code 403} and an audit row, never a
 * stream that stays silent (deny by default).
 */
@RestController
@RequestMapping("/api/changes")
public class ChangesController {
  private static final Duration STREAM_LIFETIME = Duration.ofMinutes(30);
  private static final int MAX_DEPOTS = 20;

  private final ChangeWatchers watchers;
  private final RequestAuthorizer authorizer;

  public ChangesController(ChangeWatchers watchers, RequestAuthorizer authorizer) {
    this.watchers = watchers;
    this.authorizer = authorizer;
  }

  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @RequestParam("depot") List<String> depotParams, HttpServletRequest request, HttpServletResponse response) {
    Set<String> depots = new LinkedHashSet<>();
    for (String value : depotParams) {
      for (String part : value.split(",")) {
        if (!part.isBlank()) depots.add(part.trim());
      }
    }
    if (depots.isEmpty() || depots.size() > MAX_DEPOTS) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Name between 1 and " + MAX_DEPOTS + " depots to watch");
    }
    Actor actor = null;
    for (String depot : depots) {
      actor = authorizer.require(request, "plan:Read", "wpt:plan:depot:" + depot);
    }
    // nginx would otherwise buffer the small events and hold them back (issue #118).
    response.setHeader("X-Accel-Buffering", "no");
    SseEmitter emitter = new SseEmitter(STREAM_LIFETIME.toMillis());
    Runnable stop =
        watchers.open(
            actor,
            "plan:Read",
            depots,
            new ChangeSignals.Listener() {
              @Override
              public void changed(String topic, String depot) throws Exception {
                emitter.send(SseEmitter.event().name("change").data(depot == null ? Map.of("topic", topic) : Map.of("topic", topic, "depot", depot)));
              }

              @Override
              public void heartbeat() throws Exception {
                emitter.send(SseEmitter.event().name("ping").data(""));
              }
            });
    emitter.onCompletion(stop);
    emitter.onTimeout(stop);
    emitter.onError(e -> stop.run());
    return emitter;
  }
}
