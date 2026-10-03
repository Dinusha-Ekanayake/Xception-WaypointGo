package com.waypoint.dispatch;

import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.OperatorRegistry;
import com.waypoint.dispatch.loading.application.LoadingFixture;
import com.waypoint.dispatch.ordering.application.DeliveryDaySeed;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.config.LoadingProperties;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.referencedata.application.FleetDaySeed;
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
 *   <li>{@code demo-accounts} creates one {@code <role>@waypoint.local} account per
 *       role with {@code SEED_PASSWORD}, and grants the depot roles {@code DEMO_DEPOT}
 *   <li>{@code operator-pin} provisions a loader PIN from a trusted host
 *   <li>{@code loading-fixture} builds a depot-day's loading manifests from its
 *       confirmed orders, for a development demo without a published plan
 *   <li>{@code seed-delivery-day} places the Task 2B peak day as confirmed orders on
 *       the first open operating day, marks its workshop vehicles, and grants
 *       {@code store_manager@waypoint.local} the outlet {@code DEMO_OUTLET}; once per database
 * </ul>
 *
 * <p>Commands can be combined: {@code migrate import-reference demo-accounts}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WaypointApplication implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(WaypointApplication.class);
  private static final List<String> COMMANDS =
      List.of(
          "migrate", "import-reference", "account-create", "account-grant-depot", "demo-accounts",
          "operator-pin", "loading-fixture", "seed-delivery-day");
  private static final List<String> DEMO_ROLES =
      List.of("dispatcher", "loader", "driver", "store_manager", "admin", "auditor");
  private static final List<String> DEMO_DEPOT_ROLES = List.of("dispatcher", "loader", "driver");

  private final Migrator migrator;
  private final ImportReferenceDataHandler referenceImport;
  private final AccountAdminUseCase accounts;
  private final AppProperties properties;
  private final OperatorRegistry operators;
  private final LoadingFixture loadingFixture;
  private final LoadingProperties loadingProperties;
  private final Environment environment;
  private final ReferenceBootstrap referenceBootstrap;
  private final DeliveryDaySeed deliveryDaySeed;
  private final FleetDaySeed fleetDaySeed;

  public WaypointApplication(
      Migrator migrator,
      ImportReferenceDataHandler referenceImport,
      AccountAdminUseCase accounts,
      OperatorRegistry operators,
      AppProperties properties,
      LoadingFixture loadingFixture,
      LoadingProperties loadingProperties,
      Environment environment,
      ReferenceBootstrap referenceBootstrap,
      DeliveryDaySeed deliveryDaySeed,
      FleetDaySeed fleetDaySeed) {
    this.migrator = migrator;
    this.referenceImport = referenceImport;
    this.accounts = accounts;
    this.properties = properties;
    this.operators = operators;
    this.loadingFixture = loadingFixture;
    this.loadingProperties = loadingProperties;
    this.environment = environment;
    this.referenceBootstrap = referenceBootstrap;
    this.deliveryDaySeed = deliveryDaySeed;
    this.fleetDaySeed = fleetDaySeed;
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
    if (!COMMANDS.containsAll(commands)) {
      log.error("Unknown command in {}. Use any of {}, or no argument to serve.", commands, COMMANDS);
      System.exit(2);
    }
    // Several commands may be given at once and always run in this order, so an
    // init step pays for one start of the application instead of one per command.
    if (commands.contains("migrate")) {
      int applied = migrator.migrate();
      log.info(applied == 0 ? "Schema already up to date." : "Applied {} migration(s).", applied);
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
    }
    if (commands.contains("account-create")) {
      var env = System.getenv();
      createAccount(
          required(env, "ACCOUNT_EMAIL"),
          required(env, "ACCOUNT_NAME"),
          required(env, "ACCOUNT_PASSWORD"),
          required(env, "ACCOUNT_ROLE"));
    }
    if (commands.contains("account-grant-depot")) {
      var env = System.getenv();
      grantDepot(required(env, "ACCOUNT_EMAIL"), required(env, "ACCOUNT_DEPOT"));
    }
    if (commands.contains("demo-accounts")) {
      var env = System.getenv();
      String password = required(env, "SEED_PASSWORD");
      String depot = env.getOrDefault("DEMO_DEPOT", "Peliyagoda");
      for (String role : DEMO_ROLES) {
        createAccount(role + "@waypoint.local", "Demo " + role.replace('_', ' '), password, role);
      }
      for (String role : DEMO_DEPOT_ROLES) {
        grantDepot(role + "@waypoint.local", depot);
      }
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
    }
    if (commands.contains("seed-delivery-day")) {
      seedDeliveryDay(System.getenv().getOrDefault("DEMO_OUTLET", "OUT001"));
    }
    System.exit(0);
  }

  void seedDeliveryDay(String outlet) {
    referenceBootstrap.loadCurrentVersion();
    Path data = Path.of(properties.dataDir());
    DeliveryDaySeed.Outcome outcome = deliveryDaySeed.seed(data);
    if (outcome.placed() == 0) {
      log.info("Delivery day already seeded for {} on {}; left unchanged.", outcome.depotCode(), outcome.serviceDate());
    } else {
      int marked = fleetDaySeed.seed(data, outcome.serviceDate());
      log.info(
          "Seeded {} confirmed orders and {} workshop vehicles for {} on {}.",
          outcome.placed(), marked, outcome.depotCode(), outcome.serviceDate());
    }
    // The walkthrough's store manager sees one of the seeded outlets.
    accounts.grantOutlet("store_manager@waypoint.local", outlet);
    log.info("Granted outlet {}.", outlet);
  }

  int buildLoadingFixture(String depot, java.time.LocalDate date) {
    // ApplicationReadyEvent runs after ApplicationRunner, so CLI commands must
    // load the published reference version explicitly before reading it.
    referenceBootstrap.loadCurrentVersion();
    return loadingFixture.build(depot, date);
  }

  private void createAccount(String email, String name, String password, String role) {
    try {
      UUID userId = accounts.createAccount(email, name, password, role);
      log.info("Created account {} as {}.", userId, role);
    } catch (DomainException e) {
      if (e.code() != ErrorCode.CONFLICT) {
        throw e;
      }
      // Idempotent like import-reference, so an init step can run on every start.
      // The existing account is left as it is: its password is not reset.
      log.info("Account for {} already exists; left unchanged.", role);
    }
  }

  private void grantDepot(String email, String depot) {
    accounts.grantDepot(email, depot);
    // The depot only: an email in a log line is personal data.
    log.info("Granted depot {}.", depot);
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
