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

  /**
   * Every active account's display name, so text can be masked before it leaves
   * for a language model (R-ML-31). Names only: no id, email or role.
   */
  java.util.List<String> activeNames();

  /** @param employeeCode the dock badge, for example LDR-00038, when the account has one */
  record PersonView(UUID userId, String displayName, Optional<String> employeeCode) {}
}
