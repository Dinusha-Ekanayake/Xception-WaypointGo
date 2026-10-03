package com.waypoint.dispatch.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.notification.infrastructure.WebPushCrypto.VapidKeys;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** The push message encryption against RFC 8291 Appendix A, and the VAPID token's shape. */
class WebPushEncryptionTest {
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  // RFC 8291 Appendix A.
  private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
  private static final String AS_PUBLIC =
      "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
  private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
  private static final String UA_PUBLIC =
      "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
  private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
  private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
  private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
  private static final String HEADER =
      "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
  private static final String CIPHERTEXT =
      "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ";

  @Test
  void theRfcVectorEncryptsToTheRfcMessage() throws Exception {
    KeyPair as =
        new KeyPair(
            WebPushCrypto.publicKey(WebPushCrypto.decode(AS_PUBLIC)),
            WebPushCrypto.privateKey(WebPushCrypto.decode(AS_PRIVATE)));

    byte[] body =
        WebPushCrypto.encrypt(
            PLAINTEXT.getBytes(StandardCharsets.UTF_8),
            WebPushCrypto.decode(UA_PUBLIC),
            WebPushCrypto.decode(AUTH),
            as,
            WebPushCrypto.decode(SALT));

    assertEquals(HEADER + CIPHERTEXT, B64.encodeToString(Arrays.copyOf(body, 86)) + B64.encodeToString(Arrays.copyOfRange(body, 86, body.length)));
  }

  @Test
  void theRfcIntermediateKeysComeOutOfHkdf() throws Exception {
    assertEquals(
        "oIhVW04MRdy2XN9CiKLxTg",
        B64.encodeToString(
            WebPushCrypto.hkdf(
                WebPushCrypto.decode(SALT),
                WebPushCrypto.decode("S4lYMb_L0FxCeq0WhDx813KgSYqU26kOyzWUdsXYyrg"),
                "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII),
                16)));
  }

  /** A browser holding the subscription's private key reads what a fresh key pair encrypted. */
  @Test
  void theSubscribedBrowserCanDecryptARealPush() throws Exception {
    SecureRandom random = new SecureRandom();
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair browser = generator.generateKeyPair();
    byte[] auth = new byte[16];
    random.nextBytes(auth);
    byte[] message = "{\"title\":\"Order deferred\"}".getBytes(StandardCharsets.UTF_8);

    byte[] body =
        WebPushCrypto.encrypt(
            message,
            B64.encodeToString(WebPushCrypto.encodePoint((ECPublicKey) browser.getPublic())),
            B64.encodeToString(auth),
            random);

    assertArrayEquals(message, decrypt(body, browser, auth));
  }

  @Test
  void aSubscriptionKeyThatIsNotAPointIsRefused() {
    assertThrows(
        GeneralSecurityException.class,
        () -> WebPushCrypto.encrypt(new byte[] {1}, B64.encodeToString(new byte[33]), AUTH, new SecureRandom()));
  }

  @Test
  void theVapidTokenIsAnEs256JwtForThePushServiceOrigin() throws Exception {
    VapidKeys keys = VapidKeys.of(AS_PUBLIC, AS_PRIVATE);
    Instant now = Instant.parse("2026-10-02T08:00:00Z");

    String header = keys.authorization("https://fcm.googleapis.com/fcm/send/abc", "mailto:ops@example.com", now);

    assertTrue(header.startsWith("vapid t="));
    assertTrue(header.endsWith(", k=" + AS_PUBLIC));
    String token = header.substring("vapid t=".length(), header.indexOf(", k="));
    String[] parts = token.split("\\.");
    assertEquals(3, parts.length);
    ObjectMapper json = new ObjectMapper();
    JsonNode head = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
    JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
    assertEquals("ES256", head.get("alg").asText());
    assertEquals("https://fcm.googleapis.com", claims.get("aud").asText());
    assertEquals("mailto:ops@example.com", claims.get("sub").asText());
    assertEquals(now.plusSeconds(12 * 3600).getEpochSecond(), claims.get("exp").asLong());
    assertEquals(64, Base64.getUrlDecoder().decode(parts[2]).length, "ES256 is the raw 64-byte R||S form");
    assertTrue(keys.verifies(token));
  }

  @Test
  void aPrivateKeyFromAnotherPairRefusesToLoad() {
    assertThrows(GeneralSecurityException.class, () -> VapidKeys.of(AS_PUBLIC, UA_PRIVATE));
  }

  @Test
  void theAudienceKeepsOnlyANonDefaultPort() {
    assertEquals("https://push.example:8443", VapidKeys.audience("https://push.example:8443/x/y"));
    assertEquals("https://updates.push.services.mozilla.com", VapidKeys.audience("https://updates.push.services.mozilla.com/wpush/v2/z"));
    assertFalse(VapidKeys.audience("https://a.example/p").endsWith("/p"));
  }

  /** The receiving half of RFC 8291, as a browser runs it. */
  private static byte[] decrypt(byte[] body, KeyPair browser, byte[] auth) throws Exception {
    byte[] salt = Arrays.copyOf(body, 16);
    int idLength = body[20];
    byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + idLength);
    byte[] ciphertext = Arrays.copyOfRange(body, 21 + idLength, body.length);
    KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
    agreement.init((ECPrivateKey) browser.getPrivate());
    agreement.doPhase(WebPushCrypto.publicKey(asPublic), true);
    byte[] uaPublic = WebPushCrypto.encodePoint((ECPublicKey) browser.getPublic());
    byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
    byte[] ikm = WebPushCrypto.hkdf(auth, agreement.generateSecret(), keyInfo, 32);
    byte[] cek = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
    byte[] nonce = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
    byte[] padded = cipher.doFinal(ciphertext);
    assertEquals(2, padded[padded.length - 1], "the last record ends with the 0x02 delimiter");
    return Arrays.copyOf(padded, padded.length - 1);
  }

  private static byte[] concat(byte[]... parts) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }
}
