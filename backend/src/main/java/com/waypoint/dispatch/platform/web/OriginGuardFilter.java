package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a state-changing request that another site sent.
 *
 * <p>The session cookie is {@code SameSite=Strict}, which already keeps it off a
 * cross-site request in a current browser. This is the second lock: a request
 * that changes state and names an {@code Origin} must name the host it was sent
 * to, or one listed in {@code ALLOWED_ORIGINS}. A browser always sends
 * {@code Origin} on a cross-site POST and a page cannot forge it.
 *
 * <p>The host compared against is the one the browser used. Behind the Next
 * proxy that is {@code X-Forwarded-Host}, which the proxy overwrites on every
 * request and a cross-site page cannot set without a preflight this API never
 * approves. Each role has an address of its own, so comparing with the request's
 * host covers all of them without a list to keep in step.
 *
 * <p>A request with neither {@code Origin} nor {@code Referer} is not from a
 * page: a command line client, a health check, another service. It carries no
 * ambient cookie a victim's browser attached, so it passes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
public class OriginGuardFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(OriginGuardFilter.class);
  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

  private final Set<String> allowed;
  private final String typeBase;
  private final AuditLog audit;
  private final Metrics metrics;

  public OriginGuardFilter(AppProperties properties, AuditLog audit, Metrics metrics) {
    this.allowed =
        properties.allowedOrigins().stream()
            .map(OriginGuardFilter::authorityOf)
            .filter(authority -> authority != null)
            .collect(Collectors.toUnmodifiableSet());
    this.typeBase = properties.problemTypeBase();
    this.audit = audit;
    this.metrics = metrics;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (SAFE.contains(request.getMethod())) {
      chain.doFilter(request, response);
      return;
    }
    String claimed = request.getHeader("Origin");
    if (claimed == null || claimed.isBlank()) {
      claimed = request.getHeader("Referer");
    }
    if (claimed == null || claimed.isBlank() || permitted(claimed, request)) {
      chain.doFilter(request, response);
      return;
    }
    reject(request, response, claimed);
  }

  private boolean permitted(String claimed, HttpServletRequest request) {
    String origin = authorityOf(claimed);
    if (origin == null) {
      // "null", or something that is not a URL: an opaque origin is never ours.
      return false;
    }
    return origin.equals(hostOf(request)) || allowed.contains(origin);
  }

  /** The host the browser addressed, with a default port dropped. */
  private static String hostOf(HttpServletRequest request) {
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

  /** {@code host[:port]} of a URL, lower case, default port dropped. Null when unreadable. */
  static String authorityOf(String url) {
    try {
      URI uri = URI.create(url.trim());
      if (uri.getHost() == null) {
        return null;
      }
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      int port = uri.getPort();
      boolean isDefault =
          port < 0
              || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)
              || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80);
      return isDefault ? host : host + ":" + port;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Written here rather than by the exception handler, because this runs before
   * Spring MVC and the handler never sees it. Same shape as every other problem.
   */
  private void reject(HttpServletRequest request, HttpServletResponse response, String claimed)
      throws IOException {
    metrics.increment("waypoint.request.cross_origin");
    String origin = authorityOf(claimed);
    try {
      // Deny by default is 403 plus an audit row. There is no actor: the point of
      // the check is that the cookie on this request cannot be trusted to name one.
      audit.recordStandalone(
          AuditEntry.denied(
              null,
              null,
              "platform:CrossOriginRequest",
              request.getMethod() + " " + request.getRequestURI(),
              "request came from " + (origin == null ? "an opaque origin" : origin)));
    } catch (RuntimeException e) {
      // The refusal must not depend on the database being up.
      log.warn("Could not audit a refused cross-origin request: {}", e.getMessage());
    }
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    String body =
        """
        {"type":"%sforbidden","title":"Forbidden","status":403,\
        "detail":"This request did not come from this site","instance":"%s","code":"FORBIDDEN",\
        "correlationId":"%s","violations":[]}"""
            .formatted(
                typeBase,
                jsonEscape(request.getRequestURI()),
                correlationId == null ? "" : correlationId);
    response.setStatus(403);
    response.setContentType("application/problem+json");
    response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
  }

  private static String jsonEscape(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      if (c == '"' || c == '\\') {
        out.append('\\').append(c);
      } else if (c < 0x20) {
        out.append(String.format("\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
