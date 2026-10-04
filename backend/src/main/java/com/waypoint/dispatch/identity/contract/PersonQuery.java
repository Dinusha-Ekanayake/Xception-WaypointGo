package com.waypoint.dispatch.identity.contract;

import java.util.Optional;
import java.util.UUID;

/**
 * Names for people, so another module can say who did something without reading
 * Identity's tables. Holds no personal data beyond the name and badge already
 * shown on screen, and never an email.
 */
public interface PersonQuery {

  Optional<PersonView> person(UUID userId);

  /** @param employeeCode the dock badge, for example LDR-00038, when the account has one */
  record PersonView(UUID userId, String displayName, Optional<String> employeeCode) {}
}
