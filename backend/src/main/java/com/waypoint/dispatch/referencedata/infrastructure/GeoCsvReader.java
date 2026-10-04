package com.waypoint.dispatch.referencedata.infrastructure;

import com.waypoint.dispatch.referencedata.domain.Depot;
import com.waypoint.dispatch.referencedata.domain.District;
import com.waypoint.dispatch.referencedata.domain.Outlet;
import com.waypoint.dispatch.referencedata.domain.GeoPoint;
import com.waypoint.dispatch.referencedata.domain.GeoReference;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;

/** Parse the required geography file; the domain checks coverage, identity and precision. */
final class GeoCsvReader {
  private GeoCsvReader() {}

  static GeoReference read(Path file, List<Depot> depots, List<District> districts, List<Outlet> outlets) {
    if (!Files.isRegularFile(file)) throw new GeoReference.Invalid("geo_points.csv is required");
    var rows = new ArrayList<GeoReference.Row>();
    try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        var csv = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build().parse(reader)) {
      if (!csv.getHeaderNames().equals(List.of("kind", "code", "latitude", "longitude", "precision", "source"))) {
        throw new GeoReference.Invalid("invalid geo_points.csv header");
      }
      for (var row : csv) {
        if (row.size() != 6) throw new GeoReference.Invalid("geographic row must have six fields");
        rows.add(new GeoReference.Row(row.get("kind"), row.get("code"),
            new GeoPoint(new BigDecimal(row.get("latitude")), new BigDecimal(row.get("longitude")), row.get("precision")),
            row.get("source")));
      }
    } catch (NumberFormatException e) {
      throw new GeoReference.Invalid("coordinates must be decimal numbers");
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read geographic reference data", e);
    }
    return new GeoReference(rows,
        depots.stream().map(d -> d.code().value()).collect(Collectors.toSet()),
        districts.stream().map(District::name).collect(Collectors.toSet()),
        outlets.stream().map(Outlet::id).collect(Collectors.toSet()));
  }
}
