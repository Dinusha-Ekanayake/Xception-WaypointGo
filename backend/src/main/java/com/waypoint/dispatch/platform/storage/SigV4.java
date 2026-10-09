package com.waypoint.dispatch.platform.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 for S3, written out rather than taken from the AWS SDK
 * (roadmap M-01): four calls do not justify the SDK, and every S3-compatible
 * store (R2, B2, Garage, MinIO, AWS) accepts this signature. Pure: the time is
 * a parameter, so the test checks it against AWS's published example.
 */
public final class SigV4 {
  static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
  static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
  public static final String UNSIGNED = "UNSIGNED-PAYLOAD";

  private SigV4() {}

  /**
   * The headers to send: {@code host}, {@code x-amz-date}, {@code x-amz-content-sha256},
   * any {@code extra} headers, and {@code Authorization}.
   *
   * @param path the URI path, already encoded, starting with /
   * @param query the canonical query string, sorted and encoded, or empty
   * @param payloadSha256 the payload's hex SHA-256
   */
  public static Map<String, String> sign(
      String method, String host, String path, String query, Map<String, String> extra, String payloadSha256,
      String region, String accessKeyId, String secret, ZonedDateTime when) {
    ZonedDateTime utc = when.withZoneSameInstant(ZoneOffset.UTC);
    String stamp = STAMP.format(utc);
    String day = DAY.format(utc);
    TreeMap<String, String> headers = new TreeMap<>();
    headers.put("host", host);
    headers.put("x-amz-content-sha256", payloadSha256);
    headers.put("x-amz-date", stamp);
    extra.forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.strip()));
    StringBuilder canonicalHeaders = new StringBuilder();
    headers.forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v).append('\n'));
    String signedHeaders = String.join(";", headers.keySet());
    String canonical = method + "\n" + path + "\n" + query + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadSha256;
    String scope = day + "/" + region + "/s3/aws4_request";
    String toSign = "AWS4-HMAC-SHA256\n" + stamp + "\n" + scope + "\n" + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    byte[] key = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), day);
    key = hmac(key, region);
    key = hmac(key, "s3");
    key = hmac(key, "aws4_request");
    String signature = hex(hmac(key, toSign));
    Map<String, String> out = new LinkedHashMap<>(headers);
    out.put("authorization", "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope
        + ",SignedHeaders=" + signedHeaders + ",Signature=" + signature);
    return out;
  }

  public static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  public static String hex(byte[] bytes) {
    return java.util.HexFormat.of().formatHex(bytes);
  }

  private static byte[] hmac(byte[] key, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
    }
  }
}
