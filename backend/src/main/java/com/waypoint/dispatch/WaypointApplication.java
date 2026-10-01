package com.waypoint.dispatch;

import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.OperatorRegistry;
import com.waypoint.dispatch.loading.application.LoadingFixture;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.config.LoadingProperties;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.application.ReferenceBootstrap;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point. Serving is the default. Operational commands run explicitly and
 * never as a side effect of a build, a deployment or a request.
 *
 * <ul>
 *   <li>{@code migrate} applies pending SQL migrations
 *   <li>{@code import-reference} stages, validates and publishes a reference version
 *   <li>{@code account-create} creates an account from a trusted host. The first
 *       administrator cannot come from an endpoint that requires one
 *   <li>{@code operator-pin} provisions a loader PIN from a trusted host
 *   <li>{@code loading-fixture} builds a depot-day's loading manifests from its
 *       confirmed orders, as a stand-in for Planning until #9 publishes plans
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WaypointApplication implements ApplicationRunner {
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
      System.out.println(
          applied == 0 ? "Schema already up to date." : "Applied " + applied + " migration(s).");
      System.exit(0);
    }
    if (commands.contains("import-reference")) {
      var outcome = referenceImport.importFrom(Path.of(properties.dataDir()), null);
      System.out.println(
          outcome.published()
              ? "Published reference version " + outcome.versionId() + " with "
                  + outcome.outlets() + " outlets and " + outcome.vehicles() + " vehicles."
              : "Reference data unchanged; version " + outcome.versionId() + " already holds it.");
      System.exit(0);
    }
    if (commands.contains("account-create")) {
      var env = System.getenv();
      UUID userId =
          accounts.createAccount(
              required(env, "ACCOUNT_EMAIL"),
              required(env, "ACCOUNT_NAME"),
              required(env, "ACCOUNT_PASSWORD"),
              required(env, "ACCOUNT_ROLE"));
      System.out.println("Created account " + userId + " as " + env.get("ACCOUNT_ROLE") + ".");
      System.exit(0);
    }
    if (commands.contains("account-grant-depot")) {
      var env = System.getenv();
      String depot = required(env, "ACCOUNT_DEPOT");
      accounts.grantDepot(required(env, "ACCOUNT_EMAIL"), depot);
      System.out.println("Granted depot " + depot + " to " + env.get("ACCOUNT_EMAIL") + ".");
      System.exit(0);
    }
    if (commands.contains("operator-pin")) {
      var env = System.getenv();
      String code = env.get("OPERATOR_EMPLOYEE_CODE");
      UUID userId = operators.setPin(
          required(env, "OPERATOR_EMAIL"),
          required(env, "OPERATOR_PIN"),
          code == null || code.isBlank() ? null : code.trim());
      System.out.println("Provisioned a loader PIN for account " + userId + ".");
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
      System.out.println(
          trips == 0
              ? "No confirmed orders to load for " + depot + " on " + date + "."
              : "Built " + trips + " loading manifest(s) for " + depot + " on " + date + ".");
      System.exit(0);
    }
    System.out.println(
        "Unknown command "
            + commands
            + ". Use: migrate | import-reference | account-create | account-grant-depot"
            + " | operator-pin | loading-fixture, or no argument to serve.");
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
