package com.waypoint.dispatch.platform.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Gives every request a correlation id, echoed back and attached to each log
 * line, so one driver's failed sync can be followed end to end.
 */
@Component
public class CorrelationIdFilter implements Filter {
  public static final String HEADER = "X-Correlation-Id";

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String incoming = ((HttpServletRequest) request).getHeader(HEADER);
    String correlationId =
        incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;
    MDC.put("correlationId", correlationId);
    try {
      ((HttpServletResponse) response).setHeader(HEADER, correlationId);
      chain.doFilter(request, response);
    } finally {
      MDC.remove("correlationId");
    }
  }
}
