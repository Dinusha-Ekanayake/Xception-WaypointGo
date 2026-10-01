package com.waypoint.dispatch.platform.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** The startup report is logged and shipped to the log store, so it must never carry a secret. */
class ConfigurationReportTest {

  @Test
  void userinfoIsRedacted() {
    assertEquals(
        "postgresql://***@db:5432/waypoint",
        ConfigurationReport.redactCredentials("postgresql://waypoint:s3cret@db:5432/waypoint"));
  }

  @Test
  void jdbcQueryParameterCredentialsAreRedacted() {
    String redacted =
        ConfigurationReport.redactCredentials(
            "jdbc:postgresql://db:5432/waypoint?user=waypoint&password=s3cret&sslmode=require");
    assertFalse(redacted.contains("s3cret"), redacted);
    assertFalse(redacted.contains("user=waypoint"), redacted);
    assertEquals(
        "jdbc:postgresql://db:5432/waypoint?user=***&password=***&sslmode=require", redacted);
  }

  @Test
  void aPasswordParameterInAnyCaseIsRedacted() {
    assertFalse(
        ConfigurationReport.redactCredentials("postgresql://h/db?sslmode=require&PASSWORD=x1")
            .contains("x1"));
  }

  @Test
  void aUrlWithoutCredentialsIsUnchangedAndBlankIsUnset() {
    assertEquals("postgresql://db/waypoint", ConfigurationReport.redactCredentials("postgresql://db/waypoint"));
    assertEquals("(unset)", ConfigurationReport.redactCredentials(""));
  }
}
