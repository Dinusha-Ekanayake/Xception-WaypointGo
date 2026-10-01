package com.waypoint.dispatch.identity.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.support.TestProperties;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The session cookie's name and attributes, behind HTTPS and over plain HTTP. */
class SessionCookieTest {

  @Test
  void behindHttpsTheCookieIsHostPrefixedSecureStrictAndLastsAsLongAsTheSession() {
    String header = cookie(true).issue("token-value");

    assertTrue(header.startsWith("__Host-waypoint_session=token-value;"), header);
    assertTrue(header.contains("Secure"), header);
    assertTrue(header.contains("HttpOnly"), header);
    assertTrue(header.contains("SameSite=Strict"), header);
    assertTrue(header.contains("Path=/"), header);
    assertTrue(header.contains("Max-Age=43200"), header);
    // __Host- is refused by the browser if a Domain is present.
    assertFalse(header.contains("Domain"), header);
  }

  @Test
  void overPlainHttpTheCookieIsUnprefixedBecauseABrowserWouldRefuseTheSecureOne() {
    String header = cookie(false).issue("token-value");

    assertTrue(header.startsWith("waypoint_session=token-value;"), header);
    assertFalse(header.contains("Secure"), header);
    assertTrue(header.contains("SameSite=Strict"), header);
  }

  @Test
  void theSignOutCookieCarriesTheSameAttributesSoTheBrowserReplacesTheRightOne() {
    String header = cookie(true).cleared();

    assertTrue(header.startsWith("__Host-waypoint_session=;"), header);
    assertTrue(header.contains("Max-Age=0"), header);
    assertTrue(header.contains("SameSite=Strict"), header);
    assertTrue(header.contains("Secure"), header);
  }

  @Test
  void behindHttpsAnUnprefixedCookieIsNotASession() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("waypoint_session", "planted-by-a-sibling-subdomain"));

    assertNull(cookie(true).read(request));

    request.setCookies(
        new Cookie("waypoint_session", "planted"), new Cookie("__Host-waypoint_session", "real"));
    assertEquals("real", cookie(true).read(request));
  }

  private static SessionCookie cookie(boolean secure) {
    AppProperties defaults = TestProperties.app();
    AppProperties properties =
        new AppProperties(
            defaults.databaseUrl(),
            defaults.dataDir(),
            defaults.migrationsDir(),
            "",
            secure,
            List.of(),
            defaults.problemTypeBase(),
            defaults.session(),
            defaults.loginThrottle(),
            defaults.http(),
            defaults.observability());
    SessionRegistry sessions = mock(SessionRegistry.class);
    when(sessions.absoluteLifetime()).thenReturn(Duration.ofHours(12));
    return new SessionCookie(properties, sessions);
  }
}
