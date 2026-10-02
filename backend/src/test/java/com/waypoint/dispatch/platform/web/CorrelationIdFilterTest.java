package com.waypoint.dispatch.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The correlation id reaches logs and the audit table, so it must never be raw client text. */
class CorrelationIdFilterTest {

  @Test
  void aUuidShapedHeaderIsKept() {
    String id = UUID.randomUUID().toString();
    assertEquals(id, CorrelationIdFilter.accept(id));
  }

  @Test
  void anInjectedOrOversizedHeaderIsReplaced() {
    for (String hostile :
        new String[] {
          "abc\n2026-10-01 ERROR forged line",
          "x".repeat(10_000),
          "",
          "c-1727770000-deadbeef",
          UUID.randomUUID() + "\r\n"
        }) {
      String accepted = CorrelationIdFilter.accept(hostile);
      assertNotEquals(hostile, accepted);
      UUID.fromString(accepted);
    }
    UUID.fromString(CorrelationIdFilter.accept(null));
  }

  @Test
  void theIdIsEchoedSetForTheRequestAndClearedAfter() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/session");
    request.addHeader(CorrelationIdFilter.HEADER, "not\nvalid");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> during = new AtomicReference<>();

    new CorrelationIdFilter()
        .doFilter(
            request,
            response,
            new MockFilterChain() {
              @Override
              public void doFilter(
                  jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                during.set(MDC.get(CorrelationIdFilter.MDC_KEY));
              }
            });

    String echoed = response.getHeader(CorrelationIdFilter.HEADER);
    assertEquals(during.get(), echoed);
    assertTrue(echoed != null && !echoed.contains("\n"));
    assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
  }
}
