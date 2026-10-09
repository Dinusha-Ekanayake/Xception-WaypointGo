package com.waypoint.dispatch.platform.storage;

import com.waypoint.dispatch.platform.config.ObjectStoreProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Put, get and delete one object in the S3-compatible store (roadmap M-01),
 * path-style so any S3-compatible service works. A module reaches it through
 * its own port (such as {@code ProofStore}); none of them sees a bucket.
 *
 * <p>Every object is encrypted before it leaves the server and decrypted after
 * it returns ({@link ObjectCipher}, AES-256-GCM with the object's key bound in),
 * so the provider holds only ciphertext and an object moved to another key, or
 * changed, fails to open instead of being served.
 *
 * <p>Every failure is {@code DEPENDENCY_UNAVAILABLE}: the caller says so on
 * screen and the phone keeps the upload queued (rule 9). A missing object is
 * empty, not an error.
 */
@Component
public class S3Objects {
  private static final Pattern KEY = Pattern.compile("[0-9a-z/._-]{1,200}");

  private final ObjectStoreProperties properties;
  private final Metrics metrics;
  private final HttpClient http;
  private final ObjectCipher cipher;

  public S3Objects(ObjectStoreProperties properties, Metrics metrics) {
    this.properties = properties;
    this.metrics = metrics;
    this.cipher = properties.s3() ? new ObjectCipher(properties.encryptionKey()) : null;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  public boolean enabled() {
    return properties.s3();
  }

  /** Returns once the store has the object. */
  public void put(String key, byte[] content, String contentType) {
    HttpResponse<byte[]> r =
        call("PUT", key, cipher.seal(key, content), Map.of("content-type", "application/octet-stream"));
    if (r.statusCode() / 100 != 2) throw unavailable("put", r.statusCode());
  }

  public Optional<byte[]> get(String key) {
    HttpResponse<byte[]> r = call("GET", key, null, Map.of());
    if (r.statusCode() == 404) return Optional.empty();
    if (r.statusCode() != 200) throw unavailable("get", r.statusCode());
    try {
      return Optional.of(cipher.open(key, r.body()));
    } catch (java.security.GeneralSecurityException e) {
      metrics.increment("waypoint.storage.unreadable");
      throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE, "A stored file failed its integrity check");
    }
  }

  /**
   * A row's file, wherever it is: the bytes still in the database, else the
   * object its {@code object_key} names. Null when neither holds it (cleared).
   */
  public byte[] read(byte[] content, String objectKey) {
    if (content != null) return content;
    if (objectKey == null) return null;
    return get(objectKey).orElse(null);
  }

  /** Deleting an object that is not there succeeds, as S3 does. */
  public void delete(String key) {
    HttpResponse<byte[]> r = call("DELETE", key, null, Map.of());
    if (r.statusCode() / 100 != 2 && r.statusCode() != 404) throw unavailable("delete", r.statusCode());
  }

  private HttpResponse<byte[]> call(String method, String key, byte[] body, Map<String, String> extra) {
    if (!enabled()) throw new IllegalStateException("The object store is not configured");
    if (!KEY.matcher(key).matches() || key.contains("..")) throw new IllegalArgumentException("Not an object key: " + key);
    long started = System.nanoTime();
    String status = "error";
    try {
      URI base = URI.create(properties.endpoint().replaceAll("/+$", ""));
      String path = "/" + encode(properties.bucket()) + "/" + encodePath(key);
      byte[] payload = body == null ? new byte[0] : body;
      Map<String, String> headers = SigV4.sign(
          method, base.getAuthority(), path, "", extra, SigV4.hex(SigV4.sha256(payload)), properties.region(),
          properties.accessKeyId(), properties.secretAccessKey(), ZonedDateTime.now());
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(properties.timeout());
      headers.forEach((k, v) -> {
        if (!k.equals("host")) request.header(k, v);
      });
      request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
      HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      status = String.valueOf(response.statusCode());
      return response;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable(method.toLowerCase(java.util.Locale.ROOT), 0);
    } catch (java.io.IOException e) {
      throw unavailable(method.toLowerCase(java.util.Locale.ROOT), 0);
    } finally {
      metrics.record("waypoint.storage.call", (System.nanoTime() - started) / 1_000_000, "method", method, "status", status);
    }
  }

  private static DomainException unavailable(String what, int status) {
    return new DomainException(
        ErrorCode.DEPENDENCY_UNAVAILABLE, "The evidence store is unavailable (" + what + (status > 0 ? " " + status : "") + ")");
  }

  static String encodePath(String key) {
    StringBuilder out = new StringBuilder();
    for (String part : key.split("/", -1)) {
      if (out.length() > 0) out.append('/');
      out.append(encode(part));
    }
    return out.toString();
  }

  static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~");
  }
}
