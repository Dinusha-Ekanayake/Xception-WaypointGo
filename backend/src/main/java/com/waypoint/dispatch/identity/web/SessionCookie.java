package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.platform.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The session cookie: its name, its flags, and how it is read back.
 *
 * <p>Behind HTTPS the name carries the {@code __Host-} prefix. A browser accepts
 * such a cookie only with {@code Secure}, {@code Path=/} and no {@code Domain},
 * so a sibling subdomain or a plain HTTP response cannot plant one over it. Local
 * development over plain HTTP cannot set a {@code Secure} cookie at all, so there
 * the name is unprefixed and the flag is off.
 *
 * <p>Only the name this instance issues is read back. Accepting the unprefixed
 * name on an HTTPS deployment would throw away what the prefix guarantees.
 */
@Component
public class SessionCookie {
  /** The name over plain HTTP. */
  public static final String PLAIN_NAME = "waypoint_session";

  /** The name behind HTTPS. */
  public static final String SECURE_NAME = "__Host-" + PLAIN_NAME;

  private final boolean secure;
  private final Duration lifetime;

  public SessionCookie(AppProperties properties, SessionRegistry sessions) {
    this.secure = properties.cookieSecure();
    this.lifetime = sessions.absoluteLifetime();
  }

  public String name() {
    return secure ? SECURE_NAME : PLAIN_NAME;
  }

  /** The {@code Set-Cookie} value for a new session, kept as long as the session can last. */
  public String issue(String token) {
    return build(token, lifetime);
  }

  /** The {@code Set-Cookie} value that removes it, with the same attributes it was set with. */
  public String cleared() {
    return build("", Duration.ZERO);
  }

  /** The token the caller sent, or null. */
  public String read(HttpServletRequest request) {
    if (request.getCookies() == null) {
      return null;
    }
    String name = name();
    for (Cookie cookie : request.getCookies()) {
      if (name.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  private String build(String value, Duration maxAge) {
    return ResponseCookie.from(name(), value)
        .httpOnly(true)
        .secure(secure)
        .path("/")
        .sameSite("Strict")
        .maxAge(maxAge)
        .build()
        .toString();
  }
}
