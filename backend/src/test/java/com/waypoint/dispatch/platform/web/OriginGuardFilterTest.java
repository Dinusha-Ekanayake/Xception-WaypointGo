package com.waypoint.dispatch.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** A state-changing request must come from the site it was sent to. */
class OriginGuardFilterTest {
  private final AuditLog audit = mock(AuditLog.class);

  @Test
  void aPostFromAnotherSiteIsRefusedAndAudited() throws Exception {
    MockHttpServletResponse response =
        send(filter(), "POST", "loader.waypointgo.live", "https://evil.example", null);

    assertEquals(403, response.getStatus());
    assertEquals("application/problem+json", response.getContentType());
    assertTrue(response.getContentAsString().contains("\"code\":\"FORBIDDEN\""));
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).recordStandalone(entry.capture());
    assertEquals("DENY", entry.getValue().decision());
    assertTrue(entry.getValue().reason().contains("evil.example"), entry.getValue().reason());
  }

  @Test
  void aPostFromTheSameHostPasses() throws Exception {
    assertPasses(send(filter(), "POST", "loader.waypointgo.live", "https://loader.waypointgo.live", null));
    assertPasses(send(filter(), "POST", "localhost:3000", "http://localhost:3000", null));
    // A default port written out is still the same origin.
    assertPasses(send(filter(), "POST", "waypointgo.live", "https://waypointgo.live:443", null));
    verify(audit, never()).recordStandalone(any());
  }

  @Test
  void anotherRolesAddressIsAnotherOrigin() throws Exception {
    assertEquals(
        403,
        send(filter(), "POST", "loader.waypointgo.live", "https://store.waypointgo.live", null)
            .getStatus());
  }

  @Test
  void theHostTheBrowserUsedIsTheForwardedOne() throws Exception {
    MockHttpServletRequest request = request("POST", "backend:8080", "https://waypointgo.live");
    request.addHeader("X-Forwarded-Host", "waypointgo.live");

    assertPasses(send(filter(), request));
  }

  @Test
  void anOpaqueOriginIsNeverOurs() throws Exception {
    assertEquals(403, send(filter(), "POST", "waypointgo.live", "null", null).getStatus());
  }

  @Test
  void withNoOriginTheRefererDecides() throws Exception {
    assertEquals(
        403,
        send(filter(), "POST", "waypointgo.live", null, "https://evil.example/page").getStatus());
    assertPasses(send(filter(), "POST", "waypointgo.live", null, "https://waypointgo.live/orders"));
  }

  @Test
  void aRequestThatIsNotFromAPagePasses() throws Exception {
    assertPasses(send(filter(), "POST", "backend:8080", null, null));
  }

  @Test
  void readsAreNotChecked() throws Exception {
    assertPasses(send(filter(), "GET", "waypointgo.live", "https://evil.example", null));
  }

  @Test
  void aListedOriginIsAllowedBesideTheHost() throws Exception {
    OriginGuardFilter filter = filter("https://console.example.com");

    assertPasses(send(filter, "POST", "waypointgo.live", "https://console.example.com", null));
    assertEquals(
        403, send(filter, "POST", "waypointgo.live", "https://evil.example", null).getStatus());
  }

  @Test
  void theRefusalDoesNotDependOnTheAuditWriteSucceeding() throws Exception {
    doThrow(new IllegalStateException("database is down")).when(audit).recordStandalone(any());

    assertEquals(
        403, send(filter(), "POST", "waypointgo.live", "https://evil.example", null).getStatus());
  }

  @Test
  void anAuthorityIsLowerCaseWithItsDefaultPortDropped() {
    assertEquals("waypointgo.live", OriginGuardFilter.authorityOf("https://WaypointGo.live:443"));
    assertEquals("localhost:3000", OriginGuardFilter.authorityOf("http://localhost:3000/path"));
    assertNull(OriginGuardFilter.authorityOf("null"));
    assertNull(OriginGuardFilter.authorityOf("not a url"));
  }

  // ---- fixtures ----

  private OriginGuardFilter filter(String... allowed) {
    AppProperties defaults = TestProperties.app();
    AppProperties properties =
        new AppProperties(
            defaults.databaseUrl(),
            defaults.dataDir(),
            defaults.migrationsDir(),
            "",
            false,
            List.of(allowed),
            defaults.problemTypeBase(),
            defaults.session(),
            defaults.loginThrottle(),
            defaults.http(),
            defaults.observability());
    return new OriginGuardFilter(properties, audit, new Metrics(new SimpleMeterRegistry()));
  }

  private static MockHttpServletRequest request(String method, String host, String origin) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/commands");
    request.addHeader("Host", host);
    if (origin != null) {
      request.addHeader("Origin", origin);
    }
    return request;
  }

  private static MockHttpServletResponse send(
      OriginGuardFilter filter, String method, String host, String origin, String referer)
      throws Exception {
    MockHttpServletRequest request = request(method, host, origin);
    if (referer != null) {
      request.addHeader("Referer", referer);
    }
    return send(filter, request);
  }

  private static MockHttpServletResponse send(
      OriginGuardFilter filter, MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  private static void assertPasses(MockHttpServletResponse response) {
    assertEquals(200, response.getStatus());
  }
}
