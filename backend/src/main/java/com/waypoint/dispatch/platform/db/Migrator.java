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
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.config.DataConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
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
 *
 * <p>It has a connection of its own, as the owner: {@code MIGRATION_DATABASE_URL}
 * when set, otherwise {@code DATABASE_URL}. The pool cannot be used, because the
 * pool runs as waypoint_app, which owns nothing and may create nothing. When the
 * two URLs name different logins, migrate also gives waypoint_app the password
 * the pool will log in with, so the running process needs no other credential.
 */
@Component
public class Migrator {
  private static final Logger log = LoggerFactory.getLogger(Migrator.class);
  private static final long LOCK_KEY = 8_314_552_071L;

  private final String migrationsDir;
  private final String ownerUrl;
  private final String runtimeUrl;

  public Migrator(AppProperties properties) {
    this.migrationsDir = properties.migrationsDir();
    this.runtimeUrl = properties.databaseUrl().trim();
    String migrationUrl = properties.migrationDatabaseUrl();
    this.ownerUrl = migrationUrl == null || migrationUrl.isBlank() ? runtimeUrl : migrationUrl.trim();
  }

  /** Returns the number of migrations applied by this run. */
  public int migrate() {
    List<Path> files = discover();
    if (files.isEmpty()) {
      throw new IllegalStateException("No migrations found in " + migrationsDir);
    }
    SingleConnectionDataSource owner = ownerConnection();
    try {
      JdbcTemplate jdbc = new JdbcTemplate(owner);
      TransactionTemplate transactions =
          new TransactionTemplate(new DataSourceTransactionManager(owner));
      transactions.setIsolationLevel(TransactionTemplate.ISOLATION_SERIALIZABLE);
      return transactions.execute(status -> apply(jdbc, files));
    } finally {
      owner.destroy();
    }
  }

  private int apply(JdbcTemplate jdbc, List<Path> files) {
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
      Map<String, Object> existing = existing(jdbc, name);
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
          "INSERT INTO public.schema_migrations(filename, checksum) VALUES (?, ?)", name, checksum);
      applied++;
    }
    provisionRuntimeLogin(jdbc);
    return applied;
  }

  /**
   * Lets the pool log in as waypoint_app with the password in {@code DATABASE_URL}.
   *
   * <p>Only when the deployment asks for it by giving migrate a separate owner
   * URL. The password is a setting of the deployment, not of the schema, so it
   * cannot live in a migration file; setting it here on every run also makes a
   * rotated secret take effect with the next deploy. The statement is built by
   * the server's own {@code format('%L')}, so the value is quoted correctly
   * whatever it contains.
   */
  private void provisionRuntimeLogin(JdbcTemplate jdbc) {
    String runtimeUser = DataConfig.username(runtimeUrl);
    if (ownerUrl.equals(runtimeUrl) || runtimeUser == null) {
      return;
    }
    if (runtimeUser.equals(DataConfig.username(ownerUrl))) {
      return;
    }
    String password = DataConfig.password(runtimeUrl);
    if (!DataConfig.RUNTIME_ROLE.equals(runtimeUser) || password == null || password.isBlank()) {
      throw new IllegalStateException(
          "With MIGRATION_DATABASE_URL set, DATABASE_URL must log in as "
              + DataConfig.RUNTIME_ROLE
              + " with a password.");
    }
    String statement =
        jdbc.queryForObject(
            "SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', ?::text, ?::text)",
            String.class,
            runtimeUser,
            password);
    jdbc.execute(statement);
    log.info("Runtime login {} can sign in with the configured password.", runtimeUser);
  }

  private SingleConnectionDataSource ownerConnection() {
    SingleConnectionDataSource owner = new SingleConnectionDataSource();
    owner.setUrl(DataConfig.toJdbcUrl(ownerUrl));
    String username = DataConfig.username(ownerUrl);
    String password = DataConfig.password(ownerUrl);
    if (username != null) {
      owner.setUsername(username);
    }
    if (password != null) {
      owner.setPassword(password);
    }
    // The transaction manager asks for the connection twice; it must get the same one back.
    owner.setSuppressClose(true);
    return owner;
  }

  private static Map<String, Object> existing(JdbcTemplate jdbc, String name) {
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
