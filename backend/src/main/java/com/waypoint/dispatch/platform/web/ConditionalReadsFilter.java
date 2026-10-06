package com.waypoint.dispatch.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * An ETag on every cacheable read, and a 304 when the browser already holds
 * that answer (roadmap F-07). The answer is still computed, so this saves the
 * phone's data and battery rather than the server's work; the poll interval
 * and the driver's kept details are what cut the work.
 *
 * <p>Outside {@link SecurityHeadersFilter}, so the {@code no-store} it sets on
 * everything else keeps this filter from tagging it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class ConditionalReadsFilter extends ShallowEtagHeaderFilter {

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !CacheablePaths.cacheable(request.getMethod(), request.getRequestURI());
  }
}
