package com.waypoint.dispatch.identity.domain.oauth;

import static org.junit.jupiter.api.Assertions.*;

import com.waypoint.dispatch.identity.domain.oauth.CodeExchangePolicy.Issued;
import com.waypoint.dispatch.identity.domain.oauth.CodeExchangePolicy.Presented;
import com.waypoint.dispatch.identity.domain.oauth.CodeExchangePolicy.Refusal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** R-IAM-31, SEC-34 and SEC-35: the rules that need no database. */
class OAuthRulesTest {
  private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
  private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
  private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
  private static final UUID CLIENT = UUID.randomUUID();
  private static final String REDIRECT = "https://chat.example/callback";

  @Test
  void onlyHttpsAndLoopbackHttpRedirectsCanBeRegistered() {
    for (String ok : List.of(
        "https://chat.example/callback", "https://chat.example/cb?x=1", "http://127.0.0.1:33418/callback",
        "http://localhost/callback", "http://[::1]:8080/cb")) {
      assertTrue(RedirectUriPolicy.registrable(ok), ok);
    }
    for (String refused : List.of(
        "http://chat.example/callback", "javascript:alert(1)", "data:text/html,x", "cursor://callback",
        "https://chat.example/cb#fragment", "https://user:pw@chat.example/cb", "/relative", "https:///nohost",
        "https://chat.example/a b", "", "http://127.0.0.1.evil.example/cb")) {
      assertFalse(RedirectUriPolicy.registrable(refused), refused);
    }
    assertFalse(RedirectUriPolicy.registrable(null));
    assertFalse(RedirectUriPolicy.registrable("https://chat.example/" + "a".repeat(RedirectUriPolicy.MAX_LENGTH)));
  }

  @Test
  void aRedirectMustBeTheRegisteredOneExceptForALoopbackPort() {
    List<String> registered = List.of("https://chat.example/callback", "http://127.0.0.1/callback");
    assertTrue(RedirectUriPolicy.matches(registered, "https://chat.example/callback"));
    assertTrue(RedirectUriPolicy.matches(registered, "http://127.0.0.1:51234/callback"));
    assertFalse(RedirectUriPolicy.matches(registered, "https://chat.example/callback/"));
    assertFalse(RedirectUriPolicy.matches(registered, "https://chat.example:8443/callback"));
    assertFalse(RedirectUriPolicy.matches(registered, "https://evil.example/callback"));
    assertFalse(RedirectUriPolicy.matches(registered, "http://127.0.0.1:51234/other"));
    assertFalse(RedirectUriPolicy.matches(registered, "http://localhost:51234/callback"));
    assertFalse(RedirectUriPolicy.matches(registered, null));
    assertEquals("chat.example", RedirectUriPolicy.displayHost("https://Chat.Example/callback"));
  }

  @Test
  void pkceAcceptsOnlyTheVerifierBehindTheChallenge() {
    // The example pair from RFC 7636 appendix B.
    assertEquals(CHALLENGE, Pkce.challengeOf(VERIFIER));
    assertTrue(Pkce.verifies(VERIFIER, CHALLENGE));
    assertFalse(Pkce.verifies(VERIFIER + "x", CHALLENGE));
    assertFalse(Pkce.verifies("short", CHALLENGE));
    assertFalse(Pkce.verifies(null, CHALLENGE));
    // The plain method would make the challenge its own verifier.
    assertFalse(Pkce.verifies(CHALLENGE, CHALLENGE));
    assertFalse(Pkce.validChallenge("not a challenge"));
    assertFalse(Pkce.validChallenge(null));
  }

  @Test
  void aCodeIsExchangedOnceInTimeByItsOwnClientRedirectAndVerifier() {
    Issued issued = new Issued(CLIENT, REDIRECT, CHALLENGE, NOW.plusSeconds(120), null);
    Presented right = new Presented(CLIENT, REDIRECT, VERIFIER);

    assertEquals(Optional.empty(), CodeExchangePolicy.refusal(issued, right, NOW));
    assertEquals(Optional.of(Refusal.EXPIRED), CodeExchangePolicy.refusal(issued, right, NOW.plusSeconds(120)));
    assertEquals(
        Optional.of(Refusal.WRONG_CLIENT),
        CodeExchangePolicy.refusal(issued, new Presented(UUID.randomUUID(), REDIRECT, VERIFIER), NOW));
    assertEquals(
        Optional.of(Refusal.WRONG_REDIRECT),
        CodeExchangePolicy.refusal(issued, new Presented(CLIENT, REDIRECT + "/", VERIFIER), NOW));
    assertEquals(
        Optional.of(Refusal.WRONG_VERIFIER),
        CodeExchangePolicy.refusal(issued, new Presented(CLIENT, REDIRECT, VERIFIER + "x"), NOW));
    // A replay is reported first, whatever else is wrong, because it revokes.
    Issued spent = new Issued(CLIENT, REDIRECT, CHALLENGE, NOW.minusSeconds(1), NOW.minusSeconds(60));
    assertEquals(
        Optional.of(Refusal.REPLAYED),
        CodeExchangePolicy.refusal(spent, new Presented(UUID.randomUUID(), "x", "y"), NOW));
  }
}
