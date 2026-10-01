package com.waypoint.dispatch;

import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
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
 * </ul>
 *
 * <p>Commands can be combined: {@code migrate import-reference demo-accounts}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WaypointApplication implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(WaypointApplication.class);
  private static final List<String> COMMANDS =
      List.of("migrate", "import-reference", "account-create", "account-grant-depot", "demo-accounts");
  private static final List<String> DEMO_ROLES =
      List.of("dispatcher", "loader", "driver", "store_manager", "admin", "auditor");
  private static final List<String> DEMO_DEPOT_ROLES = List.of("dispatcher", "loader", "driver");

  private final Migrator migrator;
  private final ImportReferenceDataHandler referenceImport;
  private final AccountAdminUseCase accounts;
  private final AppProperties properties;

  public WaypointApplication(
      Migrator migrator,
      ImportReferenceDataHandler referenceImport,
      AccountAdminUseCase accounts,
      AppProperties properties) {
    this.migrator = migrator;
    this.referenceImport = referenceImport;
    this.accounts = accounts;
    this.properties = properties;
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
    System.exit(0);
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
}
