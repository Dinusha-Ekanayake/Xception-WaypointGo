package com.waypoint.dispatch.notification.infrastructure;

import com.waypoint.dispatch.notification.application.PushGateway;
import com.waypoint.dispatch.notification.contract.NotificationViews.PushConfigView;
import com.waypoint.dispatch.notification.domain.Delivery;
import com.waypoint.dispatch.notification.domain.Delivery.PushResult;
import com.waypoint.dispatch.notification.infrastructure.WebPushCrypto.VapidKeys;
import com.waypoint.dispatch.platform.config.PushProperties;
import com.waypoint.dispatch.shared.util.Clock;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Web push over HTTP: RFC 8030 delivery of an RFC 8291 encrypted message,
 * signed with VAPID (RFC 8292).
 *
 * <p>With no VAPID keys configured this is off, and says so: {@link #config}
 * reports why, and nothing is sent. Misconfigured keys refuse to start, here or
 * in {@link PushProperties}.
 */
@Component
public class WebPushGateway implements PushGateway {
  private final PushProperties properties;
  private final Clock clock;
  private final Optional<VapidKeys> keys;
  private final HttpClient http;
  private final SecureRandom random = new SecureRandom();

  public WebPushGateway(PushProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
    this.keys = load(properties);
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  private static Optional<VapidKeys> load(PushProperties properties) {
    if (!properties.enabled()) {
      return Optional.empty();
    }
    try {
      return Optional.of(VapidKeys.of(properties.vapidPublicKey(), properties.vapidPrivateKey()));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("app.push: the VAPID keys are unusable: " + e.getMessage(), e);
    }
  }

  @Override
  public PushConfigView config() {
    return keys.map(k -> new PushConfigView(true, Optional.of(k.publicKey()), Optional.<String>empty()))
        .orElseGet(
            () -> new PushConfigView(
                false, Optional.empty(), Optional.of("push is not configured on this server")));
  }

  @Override
  public Response send(Target target, byte[] payload, Duration ttl, boolean urgent) {
    if (keys.isEmpty()) {
      return new Response(PushResult.REJECTED, "push is not configured on this server");
    }
    try {
      byte[] body = WebPushCrypto.encrypt(payload, target.p256dhKey(), target.authSecret(), random);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(target.endpoint()))
              .timeout(properties.timeout())
              .header("TTL", Long.toString(ttl.toSeconds()))
              .header("Urgency", urgent ? "high" : "normal")
              .header("Content-Encoding", "aes128gcm")
              .header("Content-Type", "application/octet-stream")
              .header("Authorization", keys.get().authorization(target.endpoint(), properties.subject(), clock.now()))
              .POST(HttpRequest.BodyPublishers.ofByteArray(body))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      PushResult result = Delivery.classify(response.statusCode());
      return new Response(result, "HTTP " + response.statusCode());
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      // A subscription whose keys cannot encrypt will never work: not worth retrying.
      return new Response(PushResult.REJECTED, "unusable subscription: " + e.getMessage());
    } catch (IOException e) {
      return new Response(PushResult.RETRYABLE, e.getClass().getSimpleName() + ": " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Response(PushResult.RETRYABLE, "interrupted");
    }
  }
}
