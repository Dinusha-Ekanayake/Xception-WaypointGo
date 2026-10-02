package com.waypoint.dispatch.shared.error;

/**
 * Stable machine-readable codes. These appear in API responses as {@code code}, so
 * they are part of the contract: clients branch on the code, never on the title.
 */
public enum ErrorCode {
  BAD_REQUEST,
  VALIDATION_FAILED,
  NOT_FOUND,
  CONFLICT,
  VERSION_CONFLICT,
  FORBIDDEN,
  UNAUTHENTICATED,
  CONSTRAINT_VIOLATED,
  PAYLOAD_TOO_LARGE,
  REQUEST_TIMEOUT,
  RATE_LIMITED,
  DEPENDENCY_UNAVAILABLE
}
