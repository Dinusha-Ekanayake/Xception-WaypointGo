package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The fleet side of the delivery-day seed (#114): the Task 2B peak day's
 * vehicles that are not available (in the workshop), set for the seeded day so
 * the dispatcher plans with the scenario's reduced fleet.
 *
 * <p>Never replaces a status someone already set for that day. Each row's
 * reason starts {@code Seed:}, which is how {@code seed-scenarios.sql} finds
 * seeded closures too.
 */
@Component
public class FleetDaySeed {
  private final Database database;

  public FleetDaySeed(Database database) {
    this.database = database;
  }

  /** @return the vehicles marked unavailable */
  public int seed(Path dataDir, LocalDate serviceDate) {
    List<String[]> rows = read(dataDir.resolve("Test Data").resolve("task2b_peak_day_fleet.csv"));
    return database.asSystem(
        ModuleRole.REF,
        () -> {
          int marked = 0;
          for (String[] row : rows) {
            String status = row[2];
            if ("available".equals(status)) {
              continue;
            }
            marked +=
                database.update(
                    """
                    INSERT INTO ref.vehicle_day_status (vehicle_id, service_date, status, reason, set_by)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (vehicle_id, service_date) DO NOTHING
                    """,
                    row[1],
                    java.sql.Date.valueOf(serviceDate),
                    status,
                    "Seed: Task 2B peak day " + row[0],
                    Actor.SYSTEM_ID);
          }
          return marked;
        });
  }

  /** {@code scenario,vehicle_id,status}, header skipped. */
  private static List<String[]> read(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
          .skip(1)
          .filter(line -> !line.isBlank())
          .map(line -> line.trim().split(",", -1))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
