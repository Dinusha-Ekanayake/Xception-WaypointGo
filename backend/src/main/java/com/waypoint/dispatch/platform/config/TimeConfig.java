package com.waypoint.dispatch.platform.config;

import com.waypoint.dispatch.shared.util.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time as an injected dependency.
 *
 * <p>Architecture rule 2: the domain never reads the system clock. Registering
 * it here is what lets a test supply a fixed instant and a cutoff rule be
 * verified deterministically instead of at whatever time the suite happens to
 * run.
 */
@Configuration
public class TimeConfig {

  @Bean
  public Clock clock() {
    return Clock.system();
  }
}
