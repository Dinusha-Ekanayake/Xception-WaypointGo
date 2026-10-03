package com.waypoint.dispatch.referencedata.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GeoImportTest {
  @TempDir Path directory;

  private Path dataset() throws Exception {
    Path source = Path.of("../data/General Data");
    try (var paths = Files.list(source)) {
      for (Path file : paths.filter(p -> p.toString().endsWith(".csv")).toList()) {
        Files.copy(file, directory.resolve(file.getFileName()));
      }
    }
    return directory;
  }

  @Test
  void importedOutletsShareTheDistrictCentroidWithoutInventedExactPoints() {
    var snapshot = new CsvReferenceImporter().stage(Path.of("../data")).snapshot();
    assertEquals(12, snapshot.allDistricts().size());
    for (var outlet : snapshot.allOutlets()) {
      var point = outlet.location().orElseThrow();
      var district = snapshot.district(outlet.districtName()).orElseThrow().location().orElseThrow();
      assertEquals("district", point.precision());
      assertEquals(district.latitude(), point.latitude());
      assertEquals(district.longitude(), point.longitude());
    }
    assertTrue(snapshot.allDepots().stream().allMatch(d -> d.location().isPresent()));
  }

  @Test
  void suppliedExactOutletReplacesFallbackAndChangesContentHash() throws Exception {
    Path root = dataset();
    var importer = new CsvReferenceImporter();
    var before = importer.stage(root);
    Files.writeString(root.resolve("geo_points.csv"),
        "outlet,OUT001,6.900000,79.900000,exact,test-fixture\n",
        java.nio.file.StandardOpenOption.APPEND);
    var after = importer.stage(root);
    var point = after.snapshot().outlet("OUT001").orElseThrow().location().orElseThrow();
    assertEquals("exact", point.precision());
    assertEquals(new java.math.BigDecimal("6.900000"), point.latitude());
    assertNotEquals(before.contentHash(), after.contentHash());
    assertEquals(after.contentHash(), importer.stage(root).contentHash());
  }

  @Test
  void missingDistrictAndMissingFileRefuseStaging() throws Exception {
    Path root = dataset();
    Path file = root.resolve("geo_points.csv");
    Files.write(file, Files.readAllLines(file).stream()
        .filter(line -> !line.startsWith("district,Colombo,")).toList());
    assertThrows(IllegalArgumentException.class, () -> new CsvReferenceImporter().stage(root));
    Files.delete(file);
    assertThrows(IllegalArgumentException.class, () -> new CsvReferenceImporter().stage(root));
  }

  @Test
  void wrongHeaderIsRefusedEvenWithNoRows() throws Exception {
    Path root = dataset();
    Files.writeString(root.resolve("geo_points.csv"), "kind,code,latitude,longitude,precision,precision\n");
    assertThrows(IllegalArgumentException.class, () -> new CsvReferenceImporter().stage(root));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "district,Colombo,6.9,79.9,centroid,test",
      "outlet,UNKNOWN,6.9,79.9,exact,test",
      "warehouse,Kandy,6.9,79.9,exact,test",
      "outlet,OUT001,91,79.9,exact,test",
      "outlet,OUT001,6.9,-181,exact,test",
      "outlet,OUT001,,79.9,exact,test",
      "outlet,OUT001,NaN,79.9,exact,test",
      "outlet,OUT001,6.9,79.9,district,test",
      "outlet,OUT001,6.9,79.9,exact,",
      "outlet,OUT001,6.9000001,79.9,exact,test",
      "outlet,OUT001,6.9,79.9,exact,test,unexpected"
  })
  void invalidGeoRowsRefuseTheWholeStage(String row) throws Exception {
    Path root = dataset();
    Files.writeString(root.resolve("geo_points.csv"), row + "\n",
        java.nio.file.StandardOpenOption.APPEND);
    assertThrows(IllegalArgumentException.class, () -> new CsvReferenceImporter().stage(root));
  }
}
