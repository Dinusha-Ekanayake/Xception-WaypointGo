package com.waypoint.dispatch.platform.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.waypoint.dispatch.platform.config.DirectoryLocator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies the SQL files in the migrations directory, in order, exactly once.
 *
 * <p>Forward only and checksummed: an applied file may never be edited, because a
 * database that has already run it cannot be un-run. Editing one is caught here
 * rather than discovered as drift between environments.
 *
 * <p>Runs under a transaction-scoped advisory lock, so two instances starting at
 * once cannot both apply the same file. It is an explicit command and never runs
 * on boot or on a request.
 */
@Component
public class Migrator {
  private static final Logger log = LoggerFactory.getLogger(Migrator.class);
  private static final long LOCK_KEY = 8_314_552_071L;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final String migrationsDir;

  public Migrator(
      JdbcTemplate jdbc,
      TransactionTemplate serializableTransactions,
      @Value("${app.migrations-dir:../migrations}") String migrationsDir) {
    this.jdbc = jdbc;
    this.transactions = serializableTransactions;
    this.migrationsDir = migrationsDir;
  }

  /** Returns the number of migrations applied by this run. */
  public int migrate() {
    List<Path> files = discover();
    if (files.isEmpty()) {
      throw new IllegalStateException("No migrations found in " + migrationsDir);
    }
    return transactions.execute(
        status -> {
          jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", LOCK_KEY);
          jdbc.execute(
              """
              CREATE TABLE IF NOT EXISTS public.schema_migrations (
                  filename    text PRIMARY KEY,
                  checksum    text        NOT NULL,
                  applied_at  timestamptz NOT NULL DEFAULT now()
              )
              """);
          int applied = 0;
          for (Path file : files) {
            String name = file.getFileName().toString();
            String sql = read(file);
            String checksum = sha256(sql);
            Map<String, Object> existing = existing(name);
            if (existing != null) {
              String recorded = String.valueOf(existing.get("checksum"));
              if (!recorded.equals(checksum)) {
                throw new IllegalStateException(
                    "Migration " + name + " changed after it was applied. Applied migrations are"
                        + " immutable: add a new numbered file instead.");
              }
              continue;
            }
            log.info("Applying migration {}", name);
            jdbc.execute(sql);
            jdbc.update(
                "INSERT INTO public.schema_migrations(filename, checksum) VALUES (?, ?)",
                name,
                checksum);
            applied++;
          }
          return applied;
        });
  }

  private Map<String, Object> existing(String name) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT checksum FROM public.schema_migrations WHERE filename = ?", name);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private List<Path> discover() {
    Path dir = DirectoryLocator.resolve(migrationsDir, "migrations");
    try (var stream = Files.list(dir)) {
      return stream
          .filter(p -> p.getFileName().toString().endsWith(".sql"))
          .sorted(Comparator.comparing(p -> p.getFileName().toString()))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
