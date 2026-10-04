package com.waypoint.dispatch.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Gives every request a correlation id, echoed back and attached to each log
 * line, audit row and problem body, so one driver's failed sync can be followed
 * end to end.
 *
 * <p>A client-supplied id is accepted only if it is UUID-shaped. Anything else,
 * including a value carrying a newline, is replaced: the id is written into logs
 * and {@code integration.audit_log}, so an unchecked header is a log-injection
 * path.
 *
 * <p>Runs just after Spring's observation filter, so the id is also recorded on
 * the request's trace span and a trace can be found from a support ticket.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CorrelationIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  /**
   * The same id as a request attribute, for code that takes the id as a parameter
   * instead of reading the logging context: the command endpoint passes it to the
   * bus, which writes it on the audit rows.
   */
  public static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";

  private static final Pattern UUID_SHAPE =
      Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = accept(request.getHeader(HEADER));
    MDC.put(MDC_KEY, correlationId);
    request.setAttribute(ATTRIBUTE, correlationId);
    ServerHttpObservationFilter.findObservationContext(request)
        .ifPresent(context -> context.addHighCardinalityKeyValue(
            io.micrometer.common.KeyValue.of("correlation.id", correlationId)));
    try {
      response.setHeader(HEADER, correlationId);
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  static String accept(String incoming) {
    if (incoming != null && UUID_SHAPE.matcher(incoming).matches()) {
      return incoming.toLowerCase(java.util.Locale.ROOT);
    }
    return UUID.randomUUID().toString();
  }
}
