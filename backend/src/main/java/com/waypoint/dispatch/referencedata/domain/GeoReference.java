package com.waypoint.dispatch.referencedata.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Pure geographic coverage and precision policy. Run before publishing any rows. */
public final class GeoReference {
  public static final class Invalid extends IllegalArgumentException {
    public Invalid(String detail) { super("R-REF-02: " + detail); }
  }

  public record Row(String kind, String code, GeoPoint point, String source) {}
  private final Map<String, GeoPoint> points;

  public GeoReference(List<Row> rows, Set<String> depots, Set<String> districts, Set<String> outlets) {
    Map<String, GeoPoint> checked = new HashMap<>();
    for (Row row : rows) {
      Set<String> known = switch (row.kind()) {
        case "depot" -> depots;
        case "district" -> districts;
        case "outlet" -> outlets;
        default -> throw invalid("unknown kind " + row.kind());
      };
      if (!known.contains(row.code())) throw invalid("unknown " + row.kind() + " " + row.code());
      if (row.source() == null || row.source().isBlank()) throw invalid("missing source for " + row.code());
      Set<String> allowed = switch (row.kind()) {
        case "depot" -> Set.of("exact", "approximate");
        case "district" -> Set.of("centroid");
        default -> Set.of("exact");
      };
      if (row.point() == null || !allowed.contains(row.point().precision())) {
        throw invalid("invalid precision for " + row.kind() + " " + row.code());
      }
      if (checked.putIfAbsent(row.kind() + ":" + row.code(), row.point()) != null) {
        throw invalid("duplicate " + row.kind() + " " + row.code());
      }
    }
    for (String depot : depots) require(checked, "depot", depot);
    for (String district : districts) require(checked, "district", district);
    points = Map.copyOf(checked);
  }

  public GeoPoint depot(String code) { return require(points, "depot", code); }
  public GeoPoint district(String code) { return require(points, "district", code); }
  public GeoPoint outlet(String code, String district) {
    return Optional.ofNullable(points.get("outlet:" + code)).orElseGet(() -> district(district).forOutlet());
  }

  private static GeoPoint require(Map<String, GeoPoint> points, String kind, String code) {
    GeoPoint point = points.get(kind + ":" + code);
    if (point == null) throw invalid("missing " + kind + " " + code);
    return point;
  }

  private static Invalid invalid(String detail) {
    return new Invalid(detail);
  }
}
