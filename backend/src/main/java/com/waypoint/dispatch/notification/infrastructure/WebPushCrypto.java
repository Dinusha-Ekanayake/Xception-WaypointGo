package com.waypoint.dispatch.notification.infrastructure;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The two pieces of cryptography web push needs, on the JDK alone.
 *
 * <ul>
 *   <li><b>Message encryption</b>, RFC 8291 with the {@code aes128gcm} content
 *       coding of RFC 8188: only the subscribed browser can read a push, not the
 *       push service carrying it.
 *   <li><b>VAPID</b>, RFC 8292: an ES256 token that tells the push service which
 *       application server is sending.
 * </ul>
 *
 * <p>Written here rather than taken from a library because the common Java one
 * brings an older BouncyCastle that clashes with the one already on the
 * classpath. It is checked against the RFC 8291 appendix vector.
 */
public final class WebPushCrypto {
  /** One record holds the whole message: a push payload is limited to 4 KB anyway. */
  static final int RECORD_SIZE = 4096;

  private static final Base64.Decoder B64 = Base64.getUrlDecoder();
  private static final Base64.Encoder B64_OUT = Base64.getUrlEncoder().withoutPadding();
  private static final ECParameterSpec P256 = p256();

  private WebPushCrypto() {}

  // ---- encryption, RFC 8291 -----------------------------------------------------

  /** Encrypts with a fresh key pair and salt, as every real push must. */
  public static byte[] encrypt(byte[] plaintext, String uaPublicKey, String authSecret, SecureRandom random)
      throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"), random);
    byte[] salt = new byte[16];
    random.nextBytes(salt);
    return encrypt(plaintext, decode(uaPublicKey), decode(authSecret), generator.generateKeyPair(), salt);
  }

  /** The deterministic core, with the server's key pair and salt given: for the RFC vector. */
  static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt)
      throws GeneralSecurityException {
    if (uaPublic.length != 65 || uaPublic[0] != 4) {
      throw new GeneralSecurityException("the subscription key is not an uncompressed P-256 point");
    }
    if (authSecret.length != 16) {
      throw new GeneralSecurityException("the subscription auth secret is not 16 bytes");
    }
    byte[] asPublic = encodePoint((ECPublicKey) asKeys.getPublic());

    KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
    agreement.init(asKeys.getPrivate());
    agreement.doPhase(publicKey(uaPublic), true);
    byte[] ecdhSecret = agreement.generateSecret();

    byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
    byte[] ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32);
    byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
    byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

    // One record, so it is also the last: the delimiter is 0x02 and no padding follows.
    byte[] padded = Arrays.copyOf(plaintext, plaintext.length + 1);
    padded[plaintext.length] = 2;
    if (padded.length + 16 > RECORD_SIZE) {
      throw new GeneralSecurityException("a push payload must fit one record of " + RECORD_SIZE + " bytes");
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
    byte[] ciphertext = cipher.doFinal(padded);

    ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
    header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
    return concat(header.array(), ciphertext);
  }

  /** HKDF-SHA256 (RFC 5869), extract then expand, for at most one block of output. */
  static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
    byte[] prk = hmac(salt, ikm);
    return Arrays.copyOf(hmac(prk, concat(info, new byte[] {1})), length);
  }

  private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data);
  }

  // ---- VAPID, RFC 8292 -----------------------------------------------------------

  /** The VAPID signing key, from the base64url public point and private scalar. */
  public record VapidKeys(String publicKey, ECPrivateKey privateKey, ECPublicKey verifyKey) {

    public static VapidKeys of(String publicKey, String privateKey) throws GeneralSecurityException {
      byte[] point = decode(publicKey);
      ECPublicKey verify = WebPushCrypto.publicKey(point);
      ECPrivateKey sign = WebPushCrypto.privateKey(decode(privateKey));
      VapidKeys keys = new VapidKeys(B64_OUT.encodeToString(point), sign, verify);
      // A private key that does not belong to the public key would sign tokens
      // every push service rejects. Proving the pair now refuses to start instead.
      String probe = keys.token("https://push.example", "mailto:probe@example.invalid", Instant.EPOCH);
      if (!keys.verifies(probe)) {
        throw new GeneralSecurityException("the VAPID private key does not match the public key");
      }
      return keys;
    }

    /** The {@code Authorization} header value for one push service. */
    public String authorization(String endpoint, String subject, Instant now) throws GeneralSecurityException {
      return "vapid t=" + token(audience(endpoint), subject, now) + ", k=" + publicKey;
    }

    /** Valid for 12 hours, half the 24 hours RFC 8292 allows. */
    String token(String audience, String subject, Instant now) throws GeneralSecurityException {
      String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
      String claims =
          b64("{\"aud\":\"" + json(audience) + "\",\"exp\":" + now.plusSeconds(12 * 3600).getEpochSecond()
              + ",\"sub\":\"" + json(subject) + "\"}");
      Signature es256 = Signature.getInstance("SHA256withECDSAinP1363Format");
      es256.initSign(privateKey);
      es256.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
      return header + "." + claims + "." + B64_OUT.encodeToString(es256.sign());
    }

    boolean verifies(String token) throws GeneralSecurityException {
      int dot = token.lastIndexOf('.');
      Signature es256 = Signature.getInstance("SHA256withECDSAinP1363Format");
      es256.initVerify(verifyKey);
      es256.update(token.substring(0, dot).getBytes(StandardCharsets.US_ASCII));
      return es256.verify(B64.decode(token.substring(dot + 1)));
    }

    /** The origin of the push service: scheme, host and a port only when it is not the default. */
    static String audience(String endpoint) {
      URI uri = URI.create(endpoint);
      return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    private static String b64(String text) {
      return B64_OUT.encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String json(String text) {
      return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
  }

  // ---- keys --------------------------------------------------------------------------

  static byte[] decode(String base64url) {
    return B64.decode(base64url.strip().replace('+', '-').replace('/', '_').replace("=", ""));
  }

  static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
    if (uncompressed.length != 65 || uncompressed[0] != 4) {
      throw new GeneralSecurityException("not an uncompressed P-256 point");
    }
    BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
    BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
    return (ECPublicKey)
        KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
  }

  static ECPrivateKey privateKey(byte[] scalar) throws GeneralSecurityException {
    if (scalar.length != 32) {
      throw new GeneralSecurityException("not a 32-byte P-256 private key");
    }
    return (ECPrivateKey)
        KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), P256));
  }

  static byte[] encodePoint(ECPublicKey key) {
    byte[] out = new byte[65];
    out[0] = 4;
    unsigned(key.getW().getAffineX(), out, 1);
    unsigned(key.getW().getAffineY(), out, 33);
    return out;
  }

  private static void unsigned(BigInteger value, byte[] out, int offset) {
    byte[] raw = value.toByteArray();
    int skip = raw.length > 32 ? raw.length - 32 : 0;
    int length = raw.length - skip;
    System.arraycopy(raw, skip, out, offset + 32 - length, length);
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  private static ECParameterSpec p256() {
    try {
      AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
      parameters.init(new ECGenParameterSpec("secp256r1"));
      return parameters.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("this JDK has no P-256", e);
    }
  }
}
