package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.McpAccessHandler;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Moves a validated dedicated credential into request context, never into a browser cookie. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class McpCredentialFilter extends OncePerRequestFilter {
  public static final String CREDENTIAL = McpCredentialFilter.class.getName() + ".credential";
  private final SessionCookie cookie;
  private final McpAccessHandler access;
  // Looked up per refusal: the operational commands (migrate, import-reference)
  // start without Spring MVC, where this bean is created but no resolver exists.
  private final ObjectProvider<HandlerExceptionResolver> errors;

  public McpCredentialFilter(SessionCookie cookie, McpAccessHandler access,
      @Qualifier("handlerExceptionResolver") ObjectProvider<HandlerExceptionResolver> errors) {
    this.cookie = cookie;
    this.access = access;
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    String browser = cookie.read(request);
    if (header == null && (browser == null || !browser.startsWith("mcp."))) {
      chain.doFilter(request, response);
      return;
    }
    response.setHeader("Cache-Control", "no-store");
    try {
      if (header != null && (!header.startsWith("Bearer ") || browser != null)) {
        throw new DomainException(ErrorCode.UNAUTHENTICATED, "Use one dedicated MCP bearer credential");
      }
      String token = header == null ? browser : header.substring(7);
      access.require(token, request.getMethod(), request.getRequestURI());
      if (!"/api/mcp/session/end".equals(request.getRequestURI())) {
        access.requireResource(token, request.getHeader("X-Waypoint-Mcp-Resource"));
      }
      request.setAttribute(CREDENTIAL, token);
      chain.doFilter(request, response);
    } catch (RuntimeException error) {
      if (errors.getObject().resolveException(request, response, null, error) == null) {
        throw error;
      }
    }
  }
}
