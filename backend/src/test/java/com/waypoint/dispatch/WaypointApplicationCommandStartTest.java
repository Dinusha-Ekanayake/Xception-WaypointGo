package com.waypoint.dispatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.waypoint.dispatch.identity.web.McpCredentialFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * {@code migrate}, {@code import-reference} and the other operational commands
 * start the application without a web server, so every bean must be creatable
 * there. A bean that needs Spring MVC stops the deploy's init step before any
 * migration is applied.
 *
 * <p>Needs no database: startup never blocks on one, and no command runs here.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "app.database-url=postgresql://waypoint@127.0.0.1:1/absent",
      "app.relay.enabled=false",
      "app.scheduling.enabled=false"
    })
class WaypointApplicationCommandStartTest {
  @Autowired ApplicationContext context;

  @Test
  void everyBeanIsCreatedWithoutAWebServer() {
    assertFalse(context.getBeanNamesForType(HandlerExceptionResolver.class).length > 0);
    assertNotNull(context.getBean(McpCredentialFilter.class));
  }
}
