package com.waypoint.dispatch.shared.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;

/**
 * A depot's stable identity. Decision D2: depots are identified by their natural
 * code, never by a generated number that could differ between environments on
 * re-import.
 */
public record DepotCode(String value) {
  public DepotCode {
    if (value == null || value.isBlank()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Depot code must not be blank");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
