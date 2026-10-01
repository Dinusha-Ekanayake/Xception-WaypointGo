package com.waypoint.dispatch.support;

import java.util.Optional;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The database integration tests run against.
 *
 * <ol>
 *   <li>{@code TEST_DATABASE_URL}, when set: a dedicated, already-created database. CI
 *       and anyone with a local PostgreSQL use this.
 *   <li>Otherwise a throwaway PostgreSQL 16 container, started once per test run and
 *       shared by every integration test class.
 *   <li>Otherwise, with no Docker either, the tests are skipped and say why.
 * </ol>
 *
 * <p>Before this, an unset variable silently skipped every database test, so
 * {@code mvn test} could be green while proving nothing about the database.
 *
 * <p>Use as {@code @ExtendWith(TestDatabase.class)} and register {@link #url()} as
 * {@code app.database-url} in a {@code @DynamicPropertySource}.
 */
public final class TestDatabase implements ExecutionCondition {
  private static final String ENV = "TEST_DATABASE_URL";
  private static PostgreSQLContainer<?> container;

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    return available()
        ? ConditionEvaluationResult.enabled("test database available")
        : ConditionEvaluationResult.disabled(
            "No database: set TEST_DATABASE_URL or start Docker for a throwaway PostgreSQL");
  }

  /** The URL as {@code app.database-url} expects it: {@code postgresql://user:pass@host:port/db}. */
  public static synchronized String url() {
    Optional<String> configured = configured();
    if (configured.isPresent()) {
      guardAgainstTheApplicationDatabase(configured.get());
      return configured.get();
    }
    if (container == null) {
      container = new PostgreSQLContainer<>("postgres:16-bookworm");
      container.start();
      // Stopped by the Testcontainers reaper when the JVM exits.
    }
    return "postgresql://"
        + container.getUsername()
        + ":"
        + container.getPassword()
        + "@"
        + container.getHost()
        + ":"
        + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        + "/"
        + container.getDatabaseName();
  }

  public static boolean available() {
    return configured().isPresent() || dockerAvailable();
  }

  private static Optional<String> configured() {
    String url = System.getenv(ENV);
    return url == null || url.isBlank() ? Optional.empty() : Optional.of(url);
  }

  private static boolean dockerAvailable() {
    try {
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Tests drop and recreate data; pointed at the application database they would destroy it. */
  private static void guardAgainstTheApplicationDatabase(String url) {
    if (url.equals(System.getenv("DATABASE_URL"))) {
      throw new IllegalStateException(
          "TEST_DATABASE_URL must differ from DATABASE_URL; tests destroy data");
    }
  }
}
