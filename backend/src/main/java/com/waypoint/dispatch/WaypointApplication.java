package com.waypoint.dispatch;

import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.OperatorRegistry;
import com.waypoint.dispatch.loading.application.LoadingFixture;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.config.LoadingProperties;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.application.ReferenceBootstrap;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.env.Environment;

/**
 * Entry point. Serving is the default. Operational commands run explicitly and
 * never as a side effect of a build, a deployment or a request.
 *
 * <ul>
 *   <li>{@code migrate} applies pending SQL migrations
 *   <li>{@code import-reference} stages, validates and publishes a reference version
 *   <li>{@code account-create} creates an account from a trusted host. The first
 *       administrator cannot come from an endpoint that requires one
 *   <li>{@code account-grant-depot} grants an account a depot scope
 *   <li>{@code operator-pin} provisions a loader PIN from a trusted host
 *   <li>{@code loading-fixture} builds a depot-day's loading manifests from its
 *       confirmed orders, for a development demo without a published plan
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WaypointApplication implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(WaypointApplication.class);

  private final Migrator migrator;
  private final ImportReferenceDataHandler referenceImport;
  private final AccountAdminUseCase accounts;
  private final OperatorRegistry operators;
  private final AppProperties properties;
  private final LoadingFixture loadingFixture;
  private final LoadingProperties loadingProperties;
  private final Environment environment;
  private final ReferenceBootstrap referenceBootstrap;

  public WaypointApplication(
      Migrator migrator,
      ImportReferenceDataHandler referenceImport,
      AccountAdminUseCase accounts,
      OperatorRegistry operators,
      AppProperties properties,
      LoadingFixture loadingFixture,
      LoadingProperties loadingProperties,
      Environment environment,
      ReferenceBootstrap referenceBootstrap) {
    this.migrator = migrator;
    this.referenceImport = referenceImport;
    this.accounts = accounts;
    this.operators = operators;
    this.properties = properties;
    this.loadingFixture = loadingFixture;
    this.loadingProperties = loadingProperties;
    this.environment = environment;
    this.referenceBootstrap = referenceBootstrap;
  }

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(WaypointApplication.class);
    boolean hasCommand = java.util.Arrays.stream(args).anyMatch(a -> !a.startsWith("--"));
    if (hasCommand) {
      app.setWebApplicationType(WebApplicationType.NONE);
    }
    app.run(args);
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> commands = args.getNonOptionArgs();
    if (commands.isEmpty()) {
      return;
    }
    if (commands.contains("migrate")) {
      int applied = migrator.migrate();
      log.info(applied == 0 ? "Schema already up to date." : "Applied {} migration(s).", applied);
      System.exit(0);
    }
    if (commands.contains("import-reference")) {
      var outcome = referenceImport.importFrom(Path.of(properties.dataDir()), null);
      if (outcome.published()) {
        log.info(
            "Published reference version {} with {} outlets and {} vehicles.",
            outcome.versionId(),
            outcome.outlets(),
            outcome.vehicles());
      } else {
        log.info("Reference data unchanged; version {} already holds it.", outcome.versionId());
      }
      System.exit(0);
    }
    if (commands.contains("account-create")) {
      var env = System.getenv();
      String role = required(env, "ACCOUNT_ROLE");
      try {
        UUID userId =
            accounts.createAccount(
                required(env, "ACCOUNT_EMAIL"),
                required(env, "ACCOUNT_NAME"),
                required(env, "ACCOUNT_PASSWORD"),
                role);
        log.info("Created account {} as {}.", userId, role);
      } catch (DomainException e) {
        if (e.code() != ErrorCode.CONFLICT) {
          throw e;
        }
        // Idempotent like import-reference, so an init step can run on every start.
        // The existing account is left as it is: its password is not reset.
        log.info("Account already exists; left unchanged.");
      }
      System.exit(0);
    }
    if (commands.contains("account-grant-depot")) {
      var env = System.getenv();
      String depot = required(env, "ACCOUNT_DEPOT");
      accounts.grantDepot(required(env, "ACCOUNT_EMAIL"), depot);
      // The depot only: an email in a log line is personal data.
      log.info("Granted depot {}.", depot);
      System.exit(0);
    }
    if (commands.contains("operator-pin")) {
      var env = System.getenv();
      String code = env.get("OPERATOR_EMPLOYEE_CODE");
      UUID userId = operators.setPin(
          required(env, "OPERATOR_EMAIL"),
          required(env, "OPERATOR_PIN"),
          code == null || code.isBlank() ? null : code.trim());
      // The account id only: an email in a log line is personal data.
      log.info("Provisioned a loader PIN for account {}.", userId);
      System.exit(0);
    }
    if (commands.contains("loading-fixture")) {
      if (environment.acceptsProfiles("prod", "production")) {
        throw new IllegalStateException("loading-fixture is disabled in production profiles");
      }
      if (!loadingProperties.fixtureEnabled()) {
        throw new IllegalStateException(
            "loading-fixture requires LOADING_FIXTURE_ENABLED=true in a development environment");
      }
      String depot = required(args, "depot", "LOADING_DEPOT");
      java.time.LocalDate date = java.time.LocalDate.parse(required(args, "date", "LOADING_DATE"));
      int trips = buildLoadingFixture(depot, date);
      if (trips == 0) {
        log.info("No confirmed orders to load for {} on {}.", depot, date);
      } else {
        log.info("Built {} loading manifest(s) for {} on {}.", trips, depot, date);
      }
      System.exit(0);
    }
    log.error(
        "Unknown command {}. Use: migrate | import-reference | account-create |"
            + " account-grant-depot | operator-pin | loading-fixture, or no argument to serve.",
        commands);
    System.exit(2);
  }

  int buildLoadingFixture(String depot, java.time.LocalDate date) {
    // ApplicationReadyEvent runs after ApplicationRunner, so CLI commands must
    // load the published reference version explicitly before reading it.
    referenceBootstrap.loadCurrentVersion();
    return loadingFixture.build(depot, date);
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must be set for this command");
    }
    return value;
  }

  private static String required(ApplicationArguments args, String option, String environment) {
    List<String> values = args.getOptionValues(option);
    if (values != null && !values.isEmpty() && !values.get(0).isBlank()) {
      return values.get(0);
    }
    return required(System.getenv(), environment);
  }
}
