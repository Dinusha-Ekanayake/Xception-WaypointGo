package com.waypoint.dispatch.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The public address a request was sent to.
 *
 * <p>One definition, used where the answer has to agree: the cross-site check
 * compares a page's {@code Origin} with it, and the OAuth metadata names its
 * endpoints from it. Behind the Next proxy the host is {@code X-Forwarded-Host},
 * which the proxy overwrites on every request; each role address and the preview
 * are then covered without a list to keep in step.
 */
public final class RequestOrigin {
  private static final Pattern HOST =
      Pattern.compile("[a-z0-9.-]+(:[0-9]{1,5})?|\\[[0-9a-f:]+\\](:[0-9]{1,5})?");

  private RequestOrigin() {}

  /** The host the browser addressed, lower case, with a default port dropped. Null when absent. */
  public static String host(HttpServletRequest request) {
    String host = request.getHeader("X-Forwarded-Host");
    if (host == null || host.isBlank()) {
      host = request.getHeader("Host");
    }
    if (host == null || host.isBlank()) {
      return null;
    }
    // A chain of proxies appends; the first value is the one the browser used.
    host = host.split(",")[0].trim().toLowerCase(Locale.ROOT);
    if (host.endsWith(":80") || host.endsWith(":443")) {
      host = host.substring(0, host.lastIndexOf(':'));
    }
    return host;
  }

  /**
   * {@code scheme://host[:port]} as the caller addressed it, or null when the
   * host is missing or is not a host name. The scheme is the one Tomcat resolved
   * from a trusted proxy's {@code X-Forwarded-Proto} (server.forward-headers-strategy).
   */
  public static String of(HttpServletRequest request) {
    String host = host(request);
    if (host == null || !HOST.matcher(host).matches()) {
      return null;
    }
    return ("https".equalsIgnoreCase(request.getScheme()) ? "https" : "http") + "://" + host;
  }
}
