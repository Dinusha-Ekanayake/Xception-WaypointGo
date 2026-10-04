package com.waypoint.dispatch.identity.domain.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where a signed-in user may be sent back to (R-IAM-31).
 *
 * <p>HTTPS, or HTTP on a loopback address for a client running on the user's own
 * machine. Nothing else: the sign-in page navigates to this value, so a scheme
 * allowlist is the difference between a redirect and running script.
 */
public final class RedirectUriPolicy {
  public static final int MAX_LENGTH = 2000;
  private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]");

  private RedirectUriPolicy() {}

  /** Whether a client may register this redirect URI at all. */
  public static boolean registrable(String value) {
    URI uri = parse(value);
    return uri != null && ("https".equals(uri.getScheme()) || loopback(uri));
  }

  /**
   * Whether a requested redirect is one the client registered. Exact, except
   * that a loopback client picks its port when it starts (RFC 8252 section 7.3),
   * so for loopback the port is not compared.
   */
  public static boolean matches(List<String> registered, String requested) {
    URI wanted = parse(requested);
    if (wanted == null || !registrable(requested)) {
      return false;
    }
    for (String candidate : registered) {
      if (candidate.equals(requested)) {
        return true;
      }
      URI known = parse(candidate);
      if (known != null && loopback(known) && loopback(wanted) && sameExceptPort(known, wanted)) {
        return true;
      }
    }
    return false;
  }

  /** The host shown to the person signing in, so they can see where they will be sent. */
  public static String displayHost(String value) {
    URI uri = parse(value);
    return uri == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
  }

  private static boolean sameExceptPort(URI a, URI b) {
    return a.getHost().equalsIgnoreCase(b.getHost())
        && String.valueOf(a.getRawPath()).equals(String.valueOf(b.getRawPath()))
        && String.valueOf(a.getRawQuery()).equals(String.valueOf(b.getRawQuery()));
  }

  private static boolean loopback(URI uri) {
    return "http".equals(uri.getScheme()) && LOOPBACK.contains(uri.getHost().toLowerCase(Locale.ROOT));
  }

  /** Absolute, with a host, no fragment and no user info; null for anything else. */
  private static URI parse(String value) {
    if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
      return null;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c <= 0x20 || c >= 0x7f) {
        return null;
      }
    }
    try {
      URI uri = new URI(value);
      if (!uri.isAbsolute()
          || uri.isOpaque()
          || uri.getHost() == null
          || uri.getRawFragment() != null
          || uri.getRawUserInfo() != null) {
        return null;
      }
      return uri;
    } catch (URISyntaxException e) {
      return null;
    }
  }
}
