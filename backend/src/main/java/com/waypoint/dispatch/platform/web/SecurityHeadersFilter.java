package com.waypoint.dispatch.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Response hardening applied once, at the edge, rather than per controller. */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Referrer-Policy", "same-origin");
    response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
    // F-07: a polled read is revalidated every time (never served unasked) so an
    // unchanged answer can come back as a 304; everything else is never kept.
    response.setHeader(
        "Cache-Control",
        CacheablePaths.cacheable(request.getMethod(), request.getRequestURI()) ? "private, no-cache" : "no-store");
    chain.doFilter(request, response);
  }
}
