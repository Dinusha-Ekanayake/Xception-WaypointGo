package com.waypoint.dispatch.warehouse.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.platform.config.WarehouseProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import com.waypoint.dispatch.warehouse.domain.RetryPolicy;
import com.waypoint.dispatch.warehouse.domain.WebhookSignature;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcInboundEventRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcInboundEventRepository.InboundEvent;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcPlacementRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The inbound webhook inbox: land first, verify, process later (MODULES
 * "Inbound", SEC-14, SEC-18, SEC-19).
 *
 * <p>Every request is stored, verified or not, so an attack or a misconfigured
 * sender is visible rather than silently dropped. An unverified event is stored
 * {@code quarantined} and a {@code CHECK} constraint keeps it out of every
 * processing state. An unknown type is quarantined too, never ignored. The
 * request returns as soon as the row commits; {@link #run} processes it.
 *
 * <p>Expected request: headers {@code X-Warehouse-Timestamp} (epoch seconds) and
 * {@code X-Warehouse-Signature} ({@code sha256=<hex>}), body
 * {@code {"id", "type", "data"}}. Known types: {@code order.status_changed}
 * ({@code data.order_id}, {@code data.status}) and {@code stock.changed}, which
 * is acknowledged and not stored because Waypoint keeps no stock (R-STK-04).
 */
@Component
public class InboundEvents implements ScheduledJob {
  public static final String SOURCE = "warehouse";
  static final Set<String> KNOWN_TYPES = Set.of("order.status_changed", "stock.changed");

  private final Database database;
  private final JdbcInboundEventRepository inbox;
  private final JdbcPlacementRepository placements;
  private final WarehouseReconciler reconciler;
  private final WarehouseProperties properties;
  private final ObjectMapper mapper;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public InboundEvents(
      Database database,
      JdbcInboundEventRepository inbox,
      JdbcPlacementRepository placements,
      WarehouseReconciler reconciler,
      WarehouseProperties properties,
      ObjectMapper mapper,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.inbox = inbox;
    this.placements = placements;
    this.reconciler = reconciler;
    this.properties = properties;
    this.mapper = mapper;
    this.metrics = metrics;
    this.clock = clock;
  }

  public enum Landing {
    ACCEPTED,
    DUPLICATE,
    UNVERIFIED
  }

  public boolean enabled() {
    return properties.webhookEnabled();
  }

  /** Stores one webhook request. Called with no actor: the signature is the authentication. */
  public Landing land(byte[] rawBody, String timestamp, String signature) {
    Instant now = clock.now();
    Optional<String> problem =
        WebhookSignature.problem(
            properties.webhookSecretBytes(), timestamp, signature, rawBody, now, properties.webhookWindow());
    JsonNode body = parse(rawBody);
    String sourceEventId =
        body != null && body.hasNonNull("id") ? body.get("id").asText() : "sha256:" + sha256(rawBody);
    String type = body != null && body.hasNonNull("type") ? body.get("type").asText() : "unparseable";
    String payload = body != null ? body.toString() : mapper.createObjectNode()
        .put("raw", new String(rawBody, StandardCharsets.UTF_8)).toString();
    boolean verified = problem.isEmpty();
    String status = verified && KNOWN_TYPES.contains(type) ? "received" : "quarantined";

    boolean landed =
        database.asSystem(ModuleRole.WAREHOUSE, () ->
            inbox.land(UuidV7.generate(now, random), SOURCE, sourceEventId, type, payload, verified,
                problem.orElse(null), status, now));
    if (!landed) {
      metrics.increment("waypoint.warehouse.inbound", "result", "duplicate");
      return Landing.DUPLICATE;
    }
    if (!verified) {
      metrics.increment("waypoint.warehouse.inbound", "result", "unverified");
      return Landing.UNVERIFIED;
    }
    metrics.increment("waypoint.warehouse.inbound", "result",
        "received".equals(status) ? "accepted" : "quarantined_type");
    return Landing.ACCEPTED;
  }

  // ---- processing -----------------------------------------------------------

  @Override
  public String name() {
    return "warehouse.inbound-events";
  }

  @Override
  public String cron() {
    return "*/20 * * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.WAREHOUSE;
  }

  @Override
  public void run(Instant now) {
    List<InboundEvent> due = database.asSystem(ModuleRole.WAREHOUSE, () -> inbox.due(now, 50));
    for (InboundEvent event : due) {
      process(event, now);
    }
  }

  void process(InboundEvent event, Instant now) {
    if (!event.signatureVerified()) {
      return; // also impossible by CHECK; kept so the code says it too
    }
    try {
      JsonNode data = mapper.readTree(event.payload()).path("data");
      switch (event.eventType()) {
        case "order.status_changed" -> {
          String ref = data.path("order_id").asText(null);
          String status = data.path("status").asText(null);
          var placement = ref == null ? Optional.<com.waypoint.dispatch.warehouse.domain.Placement>empty()
              : database.asSystem(ModuleRole.WAREHOUSE, () -> placements.findByWarehouseRef(ref));
          if (placement.isEmpty() || status == null) {
            // STK-06: a reservation Waypoint does not know is never applied.
            database.asSystem(ModuleRole.WAREHOUSE, () ->
                inbox.mark(event.inboundEventId(), "quarantined", "unknown warehouse order " + ref, now));
            metrics.increment("waypoint.warehouse.inbound", "result", "unknown_reservation");
            return;
          }
          reconciler.observed(placement.get(), status, now);
        }
        case "stock.changed" -> { }
        default -> {
          database.asSystem(ModuleRole.WAREHOUSE, () ->
              inbox.mark(event.inboundEventId(), "quarantined", "unknown event type", now));
          return;
        }
      }
      database.asSystem(ModuleRole.WAREHOUSE, () -> inbox.mark(event.inboundEventId(), "processed", null, now));
    } catch (IOException | RuntimeException e) {
      String error = e.toString();
      if (event.attempts() >= 10) {
        database.asSystem(ModuleRole.WAREHOUSE, () -> inbox.mark(event.inboundEventId(), "dead", error, now));
      } else {
        database.asSystem(ModuleRole.WAREHOUSE, () ->
            inbox.retryAt(event.inboundEventId(), RetryPolicy.nextAttempt(now, event.attempts()), error));
      }
    }
  }

  private JsonNode parse(byte[] raw) {
    try {
      JsonNode node = mapper.readTree(raw);
      return node != null && node.isObject() ? node : null;
    } catch (IOException e) {
      return null;
    }
  }

  private static String sha256(byte[] raw) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
