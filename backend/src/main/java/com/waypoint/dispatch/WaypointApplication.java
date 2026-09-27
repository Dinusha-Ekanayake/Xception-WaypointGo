package com.waypoint.dispatch;

import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import java.nio.file.Path;
import java.util.List;
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
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WaypointApplication implements ApplicationRunner {
  private final Migrator migrator;
  private final ImportReferenceDataHandler referenceImport;
  private final AppProperties properties;

  public WaypointApplication(
      Migrator migrator, ImportReferenceDataHandler referenceImport, AppProperties properties) {
    this.migrator = migrator;
    this.referenceImport = referenceImport;
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
    System.out.println(
        "Unknown command " + commands + ". Use: migrate | import-reference, or no argument to serve.");
    System.exit(2);
  }
}
