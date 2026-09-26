package com.waypoint.dispatch.platform.db;

import com.waypoint.dispatch.shared.util.Crypto;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Applies versioned SQL migrations with checksum tracking.
 * Mirrors lib/migrate.ts: advisory lock, schema_migrations table, fail on
 * changed checksums. Reads from ../migrations so both stacks share one source.
 */
@Component
public class Migrator {
  private static final Logger log = LoggerFactory.getLogger(Migrator.class);

  private final JdbcTemplate jdbc;
  private final String migrationsDir;

  public Migrator(JdbcTemplate jdbc, @Value("${app.migrations-dir:../migrations}") String migrationsDir) {
    this.jdbc = jdbc;
    this.migrationsDir = migrationsDir;
  }

  public void migrate() {
    Path dir = Paths.get(migrationsDir);
    List<String> names = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.sql")) {
      for (Path p : stream) names.add(p.getFileName().toString());
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read migrations directory: " + dir, e);
    }
    names.sort(String::compareTo);
    var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
    var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
    transaction.executeWithoutResult(status -> {
      jdbc.execute("SELECT pg_advisory_xact_lock(71842001)");
      jdbc.execute(
          "CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, checksum text NOT NULL)");
      for (String name : names) {
        String sql;
        try {
          sql = Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
        } catch (Exception e) {
          throw new IllegalStateException("Cannot read migration " + name, e);
        }
        String checksum = Crypto.sha256Hex(sql);
        List<String> existing = jdbc.queryForList(
            "SELECT checksum FROM schema_migrations WHERE name=?", String.class, name);
        if (!existing.isEmpty()) {
          if (!checksum.equals(existing.get(0))) {
            throw new IllegalStateException("Applied migration changed: " + name);
          }
          continue;
        }
        jdbc.execute(sql);
        jdbc.update("INSERT INTO schema_migrations VALUES (?,?)", name, checksum);
        log.warn("Applied migration {}", name);
      }
    });
  }
}
