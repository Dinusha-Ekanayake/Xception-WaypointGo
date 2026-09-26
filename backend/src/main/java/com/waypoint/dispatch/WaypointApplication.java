package com.waypoint.dispatch;

import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.service.DispatchService;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. Running the web server is the default; operational commands
 * mirror the npm scripts without ever migrating/seeding on boot or requests:
 *
 * <ul>
 *   <li>{@code migrate} - apply pending SQL migrations (npm run db:migrate)</li>
 *   <li>{@code seed} - insert demo accounts + scenarios (npm run db:seed)</li>
 * </ul>
 */
@SpringBootApplication
public class WaypointApplication implements ApplicationRunner {
  private final com.waypoint.dispatch.identity.application.AccountAdminUseCase accounts;
  private final Migrator migrator;
  private final DispatchService service;

  public WaypointApplication(Migrator migrator, DispatchService service, com.waypoint.dispatch.identity.application.AccountAdminUseCase accounts) {
    this.accounts = accounts;
    this.migrator = migrator;
    this.service = service;
  }

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(WaypointApplication.class);
    if (java.util.Arrays.stream(args).anyMatch(a -> !a.startsWith("--"))) {
      app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
    }
    app.run(args);
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> commands = args.getNonOptionArgs();
    if (commands.size() == 1 && commands.get(0).startsWith("account-")) {
      accounts.execute(commands.get(0), System.getenv());
      System.out.println("Account change recorded; prior sessions revoked.");
      System.exit(0);
    }
    if (commands.contains("migrate")) {
      migrator.migrate();
      System.out.println("Database migrations applied.");
      System.exit(0);
    }
    if (commands.contains("seed")) {
      service.seed();
      System.out.println("Demo seed initialized; existing records preserved.");
      System.exit(0);
    }
    if (!commands.isEmpty()) {
      System.out.println("Unknown command " + commands + ". Use: migrate | seed (or no args to serve).");
      System.exit(2);
    }
  }
}
