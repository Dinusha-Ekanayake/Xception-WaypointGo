package com.waypoint.dispatch.referencedata.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.referencedata.domain.*;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceCache;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceVersionReader;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceVersionWriter;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.DepotCode;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Publishes administrator additions as immutable versions and keeps their source for imports. */
@Component
public class ManagedReferencePublisher {
  private final Database database;
  private final ReferenceVersionReader reader;
  private final ReferenceVersionWriter writer;
  private final ReferenceCache cache;
  private final ReferenceScope scope;
  private final ObjectMapper mapper;

  public ManagedReferencePublisher(Database database, ReferenceVersionReader reader,
      ReferenceVersionWriter writer, ReferenceCache cache, ReferenceScope scope, ObjectMapper mapper) {
    this.database = database;
    this.reader = reader;
    this.writer = writer;
    this.cache = cache;
    this.scope = scope;
    this.mapper = mapper;
  }

  public void lockPublication() {
    database.queryOne("SELECT pg_advisory_xact_lock(74520104)");
  }

  public Map<String, Object> create(String kind, Actor actor, JsonNode payload) {
    lockPublication();
    UUID currentId = reader.currentVersionId().orElseThrow(() ->
        new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Reference data is not published"));
    ReferenceSnapshot current = reader.load(currentId).orElseThrow();
    String id = required(payload, switch (kind) {
      case "depot" -> "code";
      case "outlet" -> "outletId";
      default -> "vehicleId";
    });
    if (!id.matches("[A-Za-z0-9_-]{2,40}")) {
      throw invalid("Identifier must be 2 to 40 letters, digits, underscores or hyphens");
    }
    if (kind.equals("depot")) id = id.toUpperCase(java.util.Locale.ROOT);
    String depot = switch (kind) {
      case "outlet" -> current.district(required(payload, "district"))
          .orElseThrow(() -> invalid("Choose a published district")).depot().value();
      case "vehicle" -> required(payload, "depotCode");
      default -> null;
    };
    if (depot != null) {
      if (current.depot(new DepotCode(depot)).isEmpty()) throw invalid("Choose a published depot");
      scope.requireDepot(actor, "reference:Create" + capital(kind), "wpt:ref:" + kind + ":" + id, depot);
    }
    if (kind.equals("outlet") && payload.hasNonNull("depotCode")
        && !depot.equals(payload.path("depotCode").asText())) {
      throw invalid("The selected district belongs to a different depot");
    }
    if (exists(current, kind, id)) throw new DomainException(ErrorCode.CONFLICT, "Identifier already exists");
    JsonNode normalized = payload.deepCopy();
    if (kind.equals("depot")) ((com.fasterxml.jackson.databind.node.ObjectNode) normalized).put("code", id);
    ReferenceSnapshot next = overlay(current, List.of(new Addition(kind, id, normalized)));
    validate(next);
    database.update("INSERT INTO ref.managed_additions (kind, natural_id, payload, created_by) VALUES (?, ?, ?::jsonb, ?)",
        kind, id, normalized.toString(), actor.userId());
    String previousHash = String.valueOf(database.queryOne(
        "SELECT content_hash FROM ref.reference_versions WHERE reference_version_id = ?", currentId).get("content_hash"));
    String hash = digest(previousHash + "\n" + kind + "\n" + id + "\n" + normalized);
    UUID nextId = writer.insertVersion("admin:" + kind, hash, actor.userId());
    writer.writeRows(nextId, next);
    database.update("INSERT INTO ref.traffic_speed SELECT ?, district_name, hour, monsoon, speed_index "
        + "FROM ref.traffic_speed WHERE reference_version_id = ?", nextId, currentId);
    writer.makeCurrent(nextId);
    ReferenceSnapshot published = reader.load(nextId).orElseThrow();
    database.afterCommit(() -> cache.publish(published));
    return Map.of("id", id, "referenceVersionId", nextId.toString());
  }

  public List<Addition> additions() {
    return database.query("SELECT kind, natural_id, payload::text AS payload FROM ref.managed_additions "
        + "ORDER BY kind, natural_id").stream().map(row -> {
      try {
        return new Addition((String) row.get("kind"), (String) row.get("natural_id"),
            mapper.readTree((String) row.get("payload")));
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new IllegalStateException("Invalid stored reference addition", e);
      }
    }).toList();
  }

  public String importHash(String sourceHash, List<Addition> additions) {
    if (additions.isEmpty()) return sourceHash;
    StringBuilder canonical = new StringBuilder(sourceHash);
    additions.forEach(a -> canonical.append('\n').append(a.kind).append(':')
        .append(a.id).append(':').append(a.payload));
    return digest(canonical.toString());
  }

  public ReferenceSnapshot overlay(ReferenceSnapshot base, List<Addition> additions) {
    List<Depot> depots = new ArrayList<>(base.allDepots());
    List<Outlet> outlets = new ArrayList<>(base.allOutlets());
    List<Vehicle> vehicles = new ArrayList<>(base.allVehicles());
    for (Addition addition : additions) {
      String id = addition.id;
      JsonNode p = addition.payload;
      if (exists(base, addition.kind, id)) {
        throw new DomainException(ErrorCode.CONFLICT, "Source import conflicts with managed " + addition.kind + " " + id);
      }
      try {
        switch (addition.kind) {
          case "depot" -> depots.add(new Depot(new DepotCode(id), required(p, "name"),
              ZoneId.of(text(p, "timezone", "Asia/Colombo")),
              Optional.of(new GeoPoint(decimal(p, "latitude"), decimal(p, "longitude"),
                  required(p, "locationPrecision")))));
          case "vehicle" -> vehicles.add(new Vehicle(id,
              VehicleType.parse(required(p, "type")),
              TemperatureCapability.parse(required(p, "temperature")),
              decimal(p, "weightCapKg"), decimal(p, "volumeCapM3"),
              required(p, "fuelType"), decimal(p, "kmPerL"),
              decimal(p, "weeklyFuelQuotaL"), new DepotCode(required(p, "depotCode"))));
          case "outlet" -> outlets.add(new Outlet(id, required(p, "brand"),
              required(p, "district"), DockType.parse(required(p, "dockType")),
              ParkingConstraint.parse(required(p, "parking")),
              new DeliveryWindow(LocalTime.parse(required(p, "windowOpen")),
                  LocalTime.parse(required(p, "windowClose"))),
              p.hasNonNull("mallOpen") && p.hasNonNull("mallClose")
                  ? Optional.of(new DeliveryWindow(LocalTime.parse(required(p, "mallOpen")),
                      LocalTime.parse(required(p, "mallClose")))) : Optional.empty(),
              base.district(required(p, "district"))
                  .flatMap(District::location).map(GeoPoint::forOutlet)));
          default -> throw invalid("Unknown reference kind");
        }
      } catch (IllegalArgumentException e) {
        throw invalid("Invalid " + addition.kind + " fields: " + e.getMessage());
      }
    }
    return new ReferenceSnapshot(base.versionId(), base.brands(), depots,
        List.copyOf(base.allDistricts()), outlets, vehicles,
        List.copyOf(base.allTravelProfiles()), List.copyOf(base.allAllowances()),
        List.copyOf(base.allDays()));
  }

  public void validate(ReferenceSnapshot snapshot) {
    var expected = new ReferenceValidator.Expectations(snapshot.allOutlets().size(),
        snapshot.allVehicles().size(), snapshot.allDepots().size(), snapshot.allDistricts().size());
    var violations = ReferenceValidator.validate(snapshot, expected);
    if (!violations.isEmpty()) throw new DomainException(ErrorCode.VALIDATION_FAILED,
        "Reference addition violates network rules", violations.stream().map(ReferenceViolation::toString).toList());
  }

  private static boolean exists(ReferenceSnapshot snapshot, String kind, String id) {
    return switch (kind) {
      case "depot" -> snapshot.depot(new DepotCode(id)).isPresent();
      case "outlet" -> snapshot.outlet(id).isPresent();
      case "vehicle" -> snapshot.vehicle(id).isPresent();
      default -> false;
    };
  }

  private static String capital(String value) {
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }

  private static String required(JsonNode p, String field) {
    if (p == null || !p.hasNonNull(field) || p.path(field).asText().isBlank())
      throw invalid(field + " is required");
    return p.path(field).asText().trim();
  }

  private static String text(JsonNode p, String field, String fallback) {
    return p.hasNonNull(field) ? p.path(field).asText() : fallback;
  }

  private static BigDecimal decimal(JsonNode p, String field) {
    try { return new BigDecimal(required(p, field)); }
    catch (NumberFormatException e) { throw invalid(field + " must be numeric"); }
  }

  private static DomainException invalid(String message) {
    return new DomainException(ErrorCode.VALIDATION_FAILED, message);
  }

  private static String digest(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public record Addition(String kind, String id, JsonNode payload) {}
}
