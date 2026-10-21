package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One live connection per browser tab (live refresh, phase 2): the parts a tab
 * asks for ({@code parts=changes,unread,positions}) as named events on a single
 * server-sent event stream, so two tabs never exhaust the browser's six
 * connections per host. A part's parameters come with its name and a dot
 * ({@code changes.depot=KDY}, {@code positions.date=2026-10-21}). Each part is
 * checked as on its own endpoint; one refused refuses the connection and opens
 * none, so a tab never looks live on a part it may not hear.
 */
@RestController
@RequestMapping("/api/live")
public class LiveStreamController {
  private static final Duration STREAM_LIFETIME = Duration.ofMinutes(30);

  private final Map<String, LiveStreamPart> parts;

  public LiveStreamController(List<LiveStreamPart> parts) {
    this.parts = parts.stream().collect(Collectors.toMap(LiveStreamPart::name, Function.identity()));
  }

  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(HttpServletRequest request, HttpServletResponse response) {
    Set<String> asked = LiveStreamPart.values(request, "parts");
    if (asked.isEmpty() || !parts.keySet().containsAll(asked)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "parts is one or more of " + parts.keySet());
    }
    // nginx would otherwise buffer the small events and hold them back (issue #118).
    response.setHeader("X-Accel-Buffering", "no");
    SseEmitter emitter = new SseEmitter(STREAM_LIFETIME.toMillis());
    LiveStreamPart.Sink sink = (event, data) -> {
      synchronized (emitter) {
        emitter.send(SseEmitter.event().name(event).data(data));
      }
    };
    List<Runnable> stops = new ArrayList<>();
    try {
      for (String name : asked) {
        stops.add(parts.get(name).open(request, name + ".", sink));
      }
    } catch (RuntimeException refused) {
      stops.forEach(Runnable::run);
      throw refused;
    }
    Runnable stopAll = () -> stops.forEach(Runnable::run);
    emitter.onCompletion(stopAll);
    emitter.onTimeout(stopAll);
    emitter.onError(e -> stopAll.run());
    // Answer at once, so the tab counts as live without waiting for the first ping.
    try {
      sink.send("ping", "");
    } catch (java.io.IOException gone) {
      stopAll.run();
    }
    return emitter;
  }
}
