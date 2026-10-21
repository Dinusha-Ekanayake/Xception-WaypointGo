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
 * <p>A store asks for its outlets instead ({@code outlet=}), with
 * {@code receipt:Read} on each, and hears the topic of a change that names one of
 * them. Every depot or outlet asked for is checked by policy and by scope before
 * the stream opens: one outside the reader's scope is {@code 403} and an audit
 * row, never a stream that stays silent (deny by default).
 */
@RestController
@RequestMapping("/api/changes")
public class ChangesController {
  private static final Duration STREAM_LIFETIME = Duration.ofMinutes(30);

  private final ChangesStreamPart part;

  public ChangesController(ChangesStreamPart part) {
    this.part = part;
  }

  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(HttpServletRequest request, HttpServletResponse response) {
    // nginx would otherwise buffer the small events and hold them back (issue #118).
    response.setHeader("X-Accel-Buffering", "no");
    SseEmitter emitter = new SseEmitter(STREAM_LIFETIME.toMillis());
    Runnable stop =
        part.open(request, "", (event, data) -> emitter.send(SseEmitter.event().name(event).data(data)));
    emitter.onCompletion(stop);
    emitter.onTimeout(stop);
    emitter.onError(e -> stop.run());
    // Answer at once: without a first event the headers wait for the 25 s ping,
    // and the board polls as if the stream were down until then.
    try {
      emitter.send(SseEmitter.event().name("ping").data(""));
    } catch (java.io.IOException gone) {
      stop.run();
    }
    return emitter;
  }


}
