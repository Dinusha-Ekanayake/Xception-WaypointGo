package com.waypoint.dispatch.platform.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * One kind of live signal a tab may hear (docs/issues/live-refresh/PLAN.md, phase
 * 2): the change stream, the unread count, vehicle positions. Each module's web
 * layer offers its own part, so the platform never reaches into a module; the
 * part's own endpoint and the tab's one connection ({@link LiveStreamController})
 * both open it, with the same parameters, policy and scope checks.
 */
public interface LiveStreamPart {

  /** The part's name in {@code /api/live/stream?parts=}. */
  String name();

  /**
   * Checks the request (its parameters under {@code prefix}, policy and scope)
   * and starts sending. Throws exactly as the part's own endpoint does, before
   * anything is sent.
   *
   * @param prefix put before each of the part's parameter names: empty on its own
   *     endpoint, the part's name and a dot on the tab's connection
   * @return what stops it
   */
  Runnable open(HttpServletRequest request, String prefix, Sink sink);

  /** Where the part sends its named events. */
  @FunctionalInterface
  interface Sink {
    void send(String event, Object data) throws java.io.IOException;
  }

  /** Every value of a parameter, split on commas, blanks dropped. */
  static java.util.Set<String> values(HttpServletRequest request, String name) {
    java.util.Set<String> out = new java.util.LinkedHashSet<>();
    String[] raw = request.getParameterValues(name);
    if (raw == null) return out;
    for (String value : raw) {
      for (String part : value.split(",")) {
        if (!part.isBlank()) out.add(part.trim());
      }
    }
    return out;
  }
}
